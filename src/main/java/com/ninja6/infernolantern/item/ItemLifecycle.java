package com.ninja6.infernolantern.item;

import com.ninja6.infernolantern.repository.*;
import com.ninja6.infernolantern.scheduling.WorldScheduler;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.ninja6.infernolantern.repository.RepositoryRecords.*;

/** Journaled claims, not a transaction spanning player/entity/chunk saves. */
public final class ItemLifecycle implements AutoCloseable {
    public enum Action { DROP, PICKUP, LOSS, PLACE }
    public enum Outcome { COMMITTED, REJECTED, QUARANTINED }
    public record Result(Outcome outcome, UUID operation) {}
    /** All values captured on the owning scheduler. Effects contain bounded immutable source evidence. */
    public record Request(UUID operation, Action action, ItemToken token, int amount,
                          UUID groundEntity, WorldScheduler.Anchor anchor, State placement,
                          Document effects) {
        public Request {
            Objects.requireNonNull(operation); Objects.requireNonNull(action); Objects.requireNonNull(token);
            Objects.requireNonNull(anchor); Objects.requireNonNull(effects);
            if (amount != 1) throw new IllegalArgumentException("Unique items must have amount one");
            if ((action == Action.DROP || action == Action.PICKUP || action == Action.LOSS) != (groundEntity != null))
                throw new IllegalArgumentException("Ground entity identity required");
            if ((action == Action.PLACE) != (placement != null)) throw new IllegalArgumentException("Placement snapshot required");
        }
    }
    public record Claim(Request request, Operation operation, ItemToken resultingToken) {}
    /** Runs exclusively through WorldScheduler. Revalidate exact source/session/action before effects. */
    public interface Effects {
        boolean apply(Claim claim);
        boolean verify(Claim claim);
    }
    public static final String REJECTION = "This tower copy is no longer valid or is already placed. Please contact an admin.";
    private final SqliteRepository repository;
    private final WorldScheduler scheduler;
    private final Set<UUID> admitted = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    public ItemLifecycle(SqliteRepository repository, WorldScheduler scheduler) {
        this.repository = Objects.requireNonNull(repository); this.scheduler = Objects.requireNonNull(scheduler);
    }
    public CompletableFuture<Result> execute(Request request, Effects effects) {
        Objects.requireNonNull(request); Objects.requireNonNull(effects);
        if (closed || !admitted.add(request.operation())) return CompletableFuture.completedFuture(new Result(Outcome.REJECTED, request.operation()));
        AtomicBoolean intentAttempted = new AtomicBoolean();
        // Existing receipts must never cause physical replay, including after reconnect/restart.
        return repository.operation(request.operation()).thenCompose(existing -> {
            if (existing.isPresent()) return CompletableFuture.completedFuture(new Result(
                    existing.get().phase() == Phase.COMPLETE && sameReceipt(request, existing.get()) ? Outcome.COMMITTED : Outcome.QUARANTINED, request.operation()));
            return repository.tower(request.token().tower()).thenCompose(found -> {
                if (closed || found.isEmpty() || !matches(request, found.get()))
                    return CompletableFuture.completedFuture(new Result(Outcome.REJECTED, request.operation()));
                TowerSnapshot before = found.get(); State after = after(request, before.state());
                Intent intent = new Intent(request.operation(), request.token().tower(), before.rowVersion(),
                        request.token().generation(), before.state().tower().deploymentRevision(),
                        "item-" + request.action().name().toLowerCase(Locale.ROOT), after, request.effects());
                intentAttempted.set(true);
                return repository.beginIntent(intent).thenCompose(operation -> {
                    Claim claim = new Claim(request, operation, token(after.tower()));
                    return scheduler.supply(request.anchor(), () -> !closed && effects.apply(claim))
                            .handle((applied, failure) -> failure == null && Boolean.TRUE.equals(applied))
                            .thenCompose(applied -> applied ? scheduler.supply(request.anchor(), () -> !closed && effects.verify(claim))
                                    .handle((verified, failure) -> failure == null && Boolean.TRUE.equals(verified))
                                    .thenCompose(verified -> verified ? finish(operation) : quarantine(operation)) : quarantine(operation));
                });
            });
        }).handle((result, failure) -> {
            if (failure == null) return result;
            Throwable cause = failure;
            while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
            return new Result(intentAttempted.get() && !(cause instanceof RepositoryException.Conflict)
                    ? Outcome.QUARANTINED : Outcome.REJECTED, request.operation());
        })
                .whenComplete((result, failure) -> admitted.remove(request.operation()));
    }
    private CompletableFuture<Result> finish(Operation operation) {
        return repository.advanceOperation(operation.intent().id(), operation.rowVersion(), Phase.INTENT,
                Phase.EFFECTS_APPLIED, "Owner scheduler verified named item effects; independent save boundaries remain")
                .thenCompose(applied -> repository.completeOperation(applied.intent().id(), applied.rowVersion(),
                        "Item effect verification complete; not a player/chunk save transaction"))
                .thenApply(done -> new Result(Outcome.COMMITTED, done.intent().id()))
                .exceptionallyCompose(failure -> quarantineLatest(operation));
    }
    private CompletableFuture<Result> quarantineLatest(Operation operation) {
        return repository.operation(operation.intent().id()).thenCompose(latest -> {
            if (latest.isPresent() && latest.get().phase() == Phase.COMPLETE)
                return CompletableFuture.completedFuture(new Result(Outcome.COMMITTED, operation.intent().id()));
            return quarantine(latest.orElse(operation));
        }).exceptionally(failure -> new Result(Outcome.QUARANTINED, operation.intent().id()));
    }
    private CompletableFuture<Result> quarantine(Operation operation) {
        if (operation.phase() == Phase.RECONCILING)
            return CompletableFuture.completedFuture(new Result(Outcome.QUARANTINED, operation.intent().id()));
        return repository.advanceOperation(operation.intent().id(), operation.rowVersion(), operation.phase(), Phase.RECONCILING,
                "Source/session/entity/chunk changed or physical outcome is uncertain; no automatic replacement")
                .handle((ignored, failure) -> new Result(Outcome.QUARANTINED, operation.intent().id()));
    }
    /** Unexpected external removal is evidence for reconciliation, never proof of intentional loss. */
    public CompletableFuture<Result> unexpectedRemoval(Request request) {
        if (request.action() != Action.LOSS) throw new IllegalArgumentException("Loss observation required");
        return execute(request, new Effects() {
            @Override public boolean apply(Claim claim) { return false; }
            @Override public boolean verify(Claim claim) { return false; }
        });
    }
    private static boolean sameReceipt(Request r, Operation op) {
        return op.intent().kind().equals("item-" + r.action().name().toLowerCase(Locale.ROOT))
                && op.intent().effects().equals(r.effects())
                && matches(r, new TowerSnapshot(op.before(), op.intent().expectedRowVersion(), null))
                && after(r, op.before()).equals(op.intent().after());
    }
    private static boolean matches(Request r, TowerSnapshot snapshot) {
        Tower t = snapshot.state().tower(); ItemToken token = r.token();
        if (!snapshot.mutationReady() || !token.equals(token(t))) return false;
        if (r.action() == Action.DROP || r.action() == Action.PLACE) return t.form() == Form.PORTABLE;
        Ground g = snapshot.state().ground();
        return t.form() == Form.GROUND && g != null && g.entity().equals(r.groundEntity()) && g.world().equals(r.anchor().world());
    }
    private static State after(Request r, State before) {
        Tower t = before.tower(); long generation = Math.incrementExact(t.generation());
        if (r.action() == Action.PLACE) {
            State placed = r.placement(); Tower p = placed.tower();
            if (p.form() != Form.COMPACT || p.controller() != null || !p.position().world().equals(r.anchor().world())
                    || (p.position().x() >> 4) != r.anchor().chunkX() || (p.position().z() >> 4) != r.anchor().chunkZ() || !p.id().equals(t.id())
                    || !p.variantId().equals(t.variantId()) || p.generation() != generation
                    || p.deploymentRevision() != Math.incrementExact(t.deploymentRevision())
                    || p.hp() != t.hp() || p.maximumHp() != t.maximumHp() || p.expandAfter() != t.expandAfter()
                    || p.repairAfter() != t.repairAfter() || p.light() != t.light() || p.defence() != t.defence())
                throw new IllegalArgumentException("Placement must preserve authoritative state and consume generation");
            return placed;
        }
        Form form = switch (r.action()) { case DROP -> Form.GROUND; case PICKUP -> Form.PORTABLE; case LOSS -> Form.LOST; default -> throw new IllegalStateException(); };
        Tower next = new Tower(t.id(), t.variantId(), t.hp(), t.maximumHp(), form, generation,
                t.deploymentRevision(), null, null, t.light(), t.defence(), t.expandAfter(), t.repairAfter());
        Ground ground = form == Form.GROUND ? new Ground(r.groundEntity(), r.anchor().world(), r.anchor().chunkX(), r.anchor().chunkZ(), r.operation(), "active") : null;
        return new State(next, ground, List.of());
    }
    public static ItemToken token(Tower tower) { return new ItemToken(ItemToken.SCHEMA, tower.variantId(), tower.id(), tower.generation()); }
    @Override public void close() { closed = true; }
}
