package com.ninja6.infernolantern.runtime;

import com.ninja6.infernolantern.catalog.CatalogService;
import com.ninja6.infernolantern.pack.PackPlan;
import com.ninja6.infernolantern.prototype.PrototypeLedger.CleanupInspection;
import com.ninja6.infernolantern.repository.CommitPermit;
import com.ninja6.infernolantern.repository.RepositoryException;
import com.ninja6.infernolantern.repository.RepositoryRecords.Cell;
import com.ninja6.infernolantern.repository.SqliteRepository;
import com.ninja6.infernolantern.repository.SqliteRepository.HeldReservations;
import com.ninja6.infernolantern.scheduling.WorldScheduler;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Owns one catalog-only native store and serializes startup and reload. */
public final class NativeCatalogRuntime implements AutoCloseable {
    public enum Result { PUBLISHED, REJECTED, UNAVAILABLE, BUSY }

    public record JavaPack(PackPlan plan, CommitPermit availability, boolean localOverride) {
        public JavaPack(PackPlan plan, CommitPermit availability) {
            this(plan, availability, false);
        }
    }
    private enum State {
        OPENING, CLEANUP_BLOCKED, SIDECARS_BLOCKED, JOURNAL_BLOCKED, RECORDS_BLOCKED, UNAVAILABLE,
        UNPUBLISHED, PUBLISHED, CLOSED
    }
    private enum ReservationStatus {
        UNKNOWN, EMPTY, GUARDED
    }
    private static final int MAX_CATALOG_BYTES = 1_048_576;
    private static final int QUEUE_CAPACITY = 64;

    private final Path config;
    private final CleanupInspection cleanup;
    private final Executor io;
    private final CommitPermit lifetime = new CommitPermit();
    private final AtomicBoolean busy = new AtomicBoolean(true);
    private final CompletableFuture<BundledModels> validation;
    private final CompletableFuture<SqliteRepository> opening;
    private final CompletableFuture<CatalogService> service;
    private final CompletableFuture<Result> startup;
    private volatile State state = State.OPENING;
    private volatile ReservationStatus reservationStatus = ReservationStatus.UNKNOWN;
    private HeldReservations heldReservations;
    private volatile CompletableFuture<Void> heldClosed = CompletableFuture.completedFuture(null);

    public NativeCatalogRuntime(Path directory, CleanupInspection cleanup, Executor io, WorldScheduler scheduler) {
        this(directory, cleanup, io, scheduler, false);
    }

    public NativeCatalogRuntime(Path directory, CleanupInspection cleanup, Executor io, WorldScheduler scheduler,
            boolean localJavaOverride) {
        this(directory, cleanup, io, scheduler, localJavaOverride, null);
    }

    public NativeCatalogRuntime(Path directory, CleanupInspection cleanup, Executor io, WorldScheduler scheduler,
            boolean localJavaOverride, Function<Set<Cell>, CompletableFuture<Boolean>> publishReservations) {
        this(directory, cleanup, io, scheduler,
                () -> BundledModels.load(localJavaOverride ? directory.resolve(JavaTextureOverride.FILE) : null),
                observer -> {
                    Path store = directory.resolve("native-runtime/lanterns.db");
                    return publishReservations == null ? SqliteRepository.openCatalogOnly(store, QUEUE_CAPACITY)
                            : SqliteRepository.openCatalogOnly(store, QUEUE_CAPACITY, observer);
                }, publishReservations);
    }

    NativeCatalogRuntime(Path directory, CleanupInspection cleanup, Executor io, WorldScheduler scheduler,
            Supplier<BundledModels> models, Supplier<CompletableFuture<SqliteRepository>> open) {
        this(directory, cleanup, io, scheduler, models, observer -> open.get(), null);
    }

    private NativeCatalogRuntime(Path directory, CleanupInspection cleanup, Executor io, WorldScheduler scheduler,
            Supplier<BundledModels> models,
            Function<Consumer<HeldReservations>, CompletableFuture<SqliteRepository>> open,
            Function<Set<Cell>, CompletableFuture<Boolean>> publishReservations) {
        config = Objects.requireNonNull(directory).resolve("catalog.yml");
        this.cleanup = Objects.requireNonNull(cleanup);
        this.io = Objects.requireNonNull(io);
        validation = CompletableFuture.supplyAsync(() -> {
            lifetime.requireValid();
            if (!cleanup.complete()) {
                setState(State.CLEANUP_BLOCKED);
                throw new IllegalStateException("Prototype cleanup is incomplete or unknown");
            }
            return models.get();
        }, io);
        opening = validation.thenCompose(validator -> {
            lifetime.requireValid();
            return open.apply(held -> acceptReservations(held, publishReservations));
        });
        service = opening.thenCombine(validation, (repository, validator) ->
                new CatalogService(repository, scheduler, validator, snapshot -> CompletableFuture.completedFuture(null)));
        startup = service.handle((catalog, failure) -> {
            if (state == State.CLOSED) return CompletableFuture.completedFuture(Result.UNAVAILABLE);
            if (failure != null) {
                if (state != State.CLEANUP_BLOCKED) setState(startupFailure(failure));
                return CompletableFuture.completedFuture(Result.UNAVAILABLE);
            }
            return publish(lifetime, () -> CompletableFuture.completedFuture(true));
        }).thenCompose(result -> result);
    }

    public CompletableFuture<Result> startup() { return startup; }

    private synchronized void acceptReservations(HeldReservations held,
            Function<Set<Cell>, CompletableFuture<Boolean>> publishReservations) {
        if (state == State.CLOSED) {
            heldClosed = held.closeAsync();
            return;
        }
        heldReservations = held;
        try {
            publishReservations.apply(held.cells()).whenComplete((installed, failure) ->
                    reservationsPublished(held, failure == null && Boolean.TRUE.equals(installed)));
        } catch (RuntimeException unavailable) {
            releaseReservations();
        }
    }

    private synchronized void reservationsPublished(HeldReservations held, boolean installed) {
        if (heldReservations != held) return;
        if (!installed) {
            releaseReservations();
            return;
        }
        reservationStatus = held.cells().isEmpty() ? ReservationStatus.EMPTY : ReservationStatus.GUARDED;
    }

    private void releaseReservations() {
        reservationStatus = ReservationStatus.UNKNOWN;
        if (heldReservations != null) {
            heldClosed = heldReservations.closeAsync();
            heldReservations = null;
        }
    }

    /** After publication, reuse the verified bytes and model inventory without filesystem work. */
    public synchronized JavaPack javaPack(URI url, URI overrideUrl, boolean required) {
        if (state != State.PUBLISHED) return null;
        var packs = validation.join();
        var plan = packs.javaPlan(url, overrideUrl, required);
        return plan == null ? null : new JavaPack(plan, CommitPermit.allOf(lifetime), overrideUrl != null && packs.localOverride());
    }

    public synchronized JavaPack javaCombinedPack() {
        if (state != State.PUBLISHED) return null;
        var plan = validation.join().javaCombinedPlan();
        return plan == null ? null : new JavaPack(plan, CommitPermit.allOf(lifetime));
    }

    public CompletableFuture<Result> reload(CommitPermit authority, Supplier<CompletableFuture<Boolean>> recheck) {
        State current = state;
        if (current == State.OPENING) return CompletableFuture.completedFuture(Result.BUSY);
        if (current != State.PUBLISHED && current != State.UNPUBLISHED) {
            return CompletableFuture.completedFuture(Result.UNAVAILABLE);
        }
        if (!busy.compareAndSet(false, true)) return CompletableFuture.completedFuture(Result.BUSY);
        return publish(CommitPermit.allOf(lifetime, authority), recheck);
    }

    private CompletableFuture<Result> publish(CommitPermit permit, Supplier<CompletableFuture<Boolean>> recheck) {
        return CompletableFuture.supplyAsync(() -> read(permit), io)
                .thenCompose(text -> recheck.get().thenApply(allowed -> {
                    if (!allowed) throw new IllegalStateException("Reload authority changed");
                    return text;
                }))
                .thenComposeAsync(text -> service.thenCompose(catalog -> catalog.reloadYaml(text, permit)), io)
                .handle((result, failure) -> {
                    try {
                        if (state == State.CLOSED) return Result.UNAVAILABLE;
                        if (storageFailure(failure)) {
                            setState(State.UNAVAILABLE);
                            return Result.UNAVAILABLE;
                        }
                        if (failure != null || !result.published() || !result.effectsReconciled()) {
                            if (state == State.OPENING) setState(State.UNPUBLISHED);
                            return Result.REJECTED;
                        }
                        return setState(State.PUBLISHED) ? Result.PUBLISHED : Result.UNAVAILABLE;
                    } finally {
                        busy.set(false);
                    }
                });
    }

    private static Throwable unwrap(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure;
    }

    private static State startupFailure(Throwable failure) {
        if (!(unwrap(failure) instanceof RepositoryException.NativeStartupHold hold)) return State.UNAVAILABLE;
        return switch (hold.reason()) {
            case SIDECARS -> State.SIDECARS_BLOCKED;
            case PENDING_OPERATIONS -> State.JOURNAL_BLOCKED;
            case RECORDED_STATE -> State.RECORDS_BLOCKED;
        };
    }

    private static boolean storageFailure(Throwable failure) {
        failure = unwrap(failure);
        if (failure instanceof SQLException) return true;
        return failure instanceof RepositoryException && (!(failure instanceof RepositoryException.Conflict
                || failure instanceof RepositoryException.CommitRevoked) || failure.getSuppressed().length != 0);
    }

    private String read(CommitPermit permit) {
        permit.requireValid();
        if (!Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS)) {
            throw new CompletionException(new IOException("Catalog must be a regular file"));
        }
        try {
            if (config.getFileSystem().supportedFileAttributeViews().contains("unix")
                    && !Integer.valueOf(1).equals(Files.getAttribute(config, "unix:nlink", LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("Catalog must not share a hard link");
            }
            try (var input = Files.newInputStream(config, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = input.readNBytes(MAX_CATALOG_BYTES + 1);
                if (bytes.length > MAX_CATALOG_BYTES) throw new IOException("Catalog exceeds its size limit");
                return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            }
        } catch (IOException failure) {
            throw new CompletionException(failure);
        }
    }

    public String status() {
        return switch (state) {
            case OPENING -> "opening";
            case CLEANUP_BLOCKED -> cleanupStatus();
            case SIDECARS_BLOCKED -> "blocked by native SQLite sidecars; preserve the database and sidecars together"
                    + reservationStatus();
            case JOURNAL_BLOCKED -> "blocked by pending native operations; preserve the database and matching saves for recovery review"
                    + reservationStatus();
            case RECORDS_BLOCKED -> "blocked by recorded native gameplay evidence; preserve the database and matching saves for review"
                    + reservationStatus();
            case UNAVAILABLE -> "storage or bundled assets unavailable";
            case UNPUBLISHED -> "no published catalog for this startup";
            case PUBLISHED -> "catalog published";
            case CLOSED -> "closed";
        } + "; native gameplay unavailable";
    }

    private String reservationStatus() {
        return switch (reservationStatus) {
            case UNKNOWN -> "; native reservation coverage unknown";
            case EMPTY -> "; no reserved cells recorded at inspection";
            case GUARDED -> "; recorded native cells guarded against direct player edits";
        };
    }

    private String cleanupStatus() {
        if (!cleanup.cellsKnown()) {
            return "blocked by prototype cleanup evidence; cells could not be identified safely";
        }
        if (cleanup.cells().isEmpty()) return "blocked by prototype cleanup evidence; staged manifest retained";
        return "blocked by prototype cleanup evidence; recorded cells remain guarded";
    }

    private synchronized boolean setState(State next) {
        if (state == State.CLOSED) return false;
        if (next == State.UNAVAILABLE) lifetime.revoke();
        state = next;
        return true;
    }

    public synchronized CompletableFuture<Void> stop() {
        state = State.CLOSED;
        lifetime.revoke();
        releaseReservations();
        service.thenAccept(CatalogService::close);
        return opening.handle((repository, failure) -> repository == null
                ? CompletableFuture.<Void>completedFuture(null) : repository.closeAsync())
                .thenCompose(result -> result).thenCompose(ignored -> heldClosed);
    }

    @Override
    public void close() { stop(); }
}
