package com.ninja6.infernolantern.runtime;

import com.ninja6.infernolantern.catalog.CatalogService;
import com.ninja6.infernolantern.pack.PackPlan;
import com.ninja6.infernolantern.prototype.PrototypeLedger.CleanupInspection;
import com.ninja6.infernolantern.repository.CommitPermit;
import com.ninja6.infernolantern.repository.RepositoryException;
import com.ninja6.infernolantern.repository.SqliteRepository;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Owns one catalog-only native store and serializes startup and reload. */
public final class NativeCatalogRuntime implements AutoCloseable {
    public enum Result { PUBLISHED, REJECTED, UNAVAILABLE, BUSY }

    public record JavaPack(PackPlan plan, CommitPermit availability) {}
    private enum State { OPENING, CLEANUP_BLOCKED, UNAVAILABLE, UNPUBLISHED, PUBLISHED, CLOSED }
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

    public NativeCatalogRuntime(Path directory, CleanupInspection cleanup, Executor io, WorldScheduler scheduler) {
        this(directory, cleanup, io, scheduler, BundledModels::load,
                () -> SqliteRepository.openCatalogOnly(directory.resolve("native-runtime/lanterns.db"), QUEUE_CAPACITY));
    }

    NativeCatalogRuntime(Path directory, CleanupInspection cleanup, Executor io, WorldScheduler scheduler,
            Supplier<BundledModels> models, Supplier<CompletableFuture<SqliteRepository>> open) {
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
            return open.get();
        });
        service = opening.thenCombine(validation, (repository, validator) ->
                new CatalogService(repository, scheduler, validator, snapshot -> CompletableFuture.completedFuture(null)));
        startup = service.handle((catalog, failure) -> {
            if (state == State.CLOSED) return CompletableFuture.completedFuture(Result.UNAVAILABLE);
            if (failure != null) {
                if (state != State.CLEANUP_BLOCKED) setState(State.UNAVAILABLE);
                return CompletableFuture.completedFuture(Result.UNAVAILABLE);
            }
            return publish(lifetime, () -> CompletableFuture.completedFuture(true));
        }).thenCompose(result -> result);
    }

    public CompletableFuture<Result> startup() { return startup; }

    /** After publication, reuse the verified bytes and model inventory without filesystem work. */
    public synchronized JavaPack javaPack(URI url) {
        if (state != State.PUBLISHED) return null;
        return new JavaPack(validation.join().javaDefault(url), CommitPermit.allOf(lifetime));
    }

    public CompletableFuture<Result> reload(CommitPermit authority, Supplier<CompletableFuture<Boolean>> recheck) {
        if (state == State.CLOSED || state == State.UNAVAILABLE || state == State.CLEANUP_BLOCKED) {
            return CompletableFuture.completedFuture(Result.UNAVAILABLE);
        }
        if (state == State.OPENING || !busy.compareAndSet(false, true)) return CompletableFuture.completedFuture(Result.BUSY);
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

    private static boolean storageFailure(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) {
            failure = failure.getCause();
        }
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
            case UNAVAILABLE -> "storage or bundled assets unavailable";
            case UNPUBLISHED -> "no published catalog for this startup";
            case PUBLISHED -> "catalog published";
            case CLOSED -> "closed";
        } + "; native gameplay unavailable";
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
        service.thenAccept(CatalogService::close);
        return opening.handle((repository, failure) -> repository == null
                ? CompletableFuture.<Void>completedFuture(null) : repository.closeAsync()).thenCompose(result -> result);
    }

    @Override
    public void close() { stop(); }
}
