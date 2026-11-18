package com.ninja6.infernolantern.item;

import com.ninja6.infernolantern.repository.*;
import com.ninja6.infernolantern.recovery.RecoveryEnvelope;
import com.ninja6.infernolantern.scheduling.WorldScheduler;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.ninja6.infernolantern.repository.RepositoryRecords.*;

/** Disposable harness issuance only; no award provenance, eligibility or quantity contract. */
public final class FixtureDelivery implements AutoCloseable {
    public static final String VARIANT = "prototype.item-fixture";
    public record Request(UUID tower, UUID operation, WorldScheduler.Anchor anchor, Document evidence) {
        public Request { Objects.requireNonNull(tower); Objects.requireNonNull(operation); Objects.requireNonNull(anchor); Objects.requireNonNull(evidence); }
        public ItemToken token() { return new ItemToken(1, VARIANT, tower, 1); }
    }
    public interface Effects {
        boolean stage();
        boolean verify();
        boolean activate();
    }
    private final SqliteRepository repository;
    private final WorldScheduler scheduler;
    private volatile boolean closed;
    public FixtureDelivery(SqliteRepository repository, WorldScheduler scheduler) { this.repository = repository; this.scheduler = scheduler; }
    public CompletableFuture<ItemLifecycle.Result> issue(Request request, Effects effects) {
        AtomicBoolean attempted = new AtomicBoolean();
        if (closed) return CompletableFuture.completedFuture(result(request, ItemLifecycle.Outcome.REJECTED));
        Tower initial = new Tower(request.tower(), VARIANT, 0, 100, Form.PORTABLE, 0, 0, null, null, false, false, 0, 0);
        Tower delivered = new Tower(request.tower(), VARIANT, 0, 100, Form.PORTABLE, 1, 0, null, null, false, false, 0, 0);
        State after = new State(delivered, null, List.of());
        Intent intent = new Intent(request.operation(), request.tower(), 0, 0, 0, "fixture-item-delivery", after,
                RecoveryEnvelope.fixture("fixture-item-delivery",request.anchor(),request.evidence(),request.operation()));
        return repository.operation(request.operation()).thenCompose(existing -> {
            if (existing.isPresent()) return CompletableFuture.completedFuture(result(request, ItemLifecycle.Outcome.QUARANTINED));
            return repository.createTower(initial).thenCompose(tower -> {
                if (closed) return CompletableFuture.completedFuture(result(request, ItemLifecycle.Outcome.REJECTED));
                attempted.set(true);
                return repository.beginIntent(intent).thenCompose(operation -> scheduler.supply(request.anchor(), () -> !closed && effects.stage())
                        .handle((ok, failure) -> failure == null && Boolean.TRUE.equals(ok))
                        .thenCompose(staged -> staged ? scheduler.supply(request.anchor(), () -> !closed && effects.verify())
                            .handle((ok, failure) -> failure == null && Boolean.TRUE.equals(ok))
                            .thenCompose(verified -> verified ? complete(request, operation, after, effects) : quarantine(request, operation))
                            : quarantine(request, operation)));
            });
        }).handle((value, failure) -> failure == null ? value : result(request, attempted.get() ? ItemLifecycle.Outcome.QUARANTINED : ItemLifecycle.Outcome.REJECTED));
    }
    private CompletableFuture<ItemLifecycle.Result> complete(Request request, Operation operation, State after, Effects effects) {
        return repository.advanceOperation(request.operation(), operation.rowVersion(), Phase.INTENT, Phase.EFFECTS_APPLIED, "Exact unavailable fixture item verified on owner scheduler")
                .thenCompose(applied -> repository.completeOperation(request.operation(), applied.rowVersion(), "Fixture row committed before portable activation; saves remain independent"))
                .thenCompose(done -> repository.tower(request.tower()))
                .thenCompose(found -> {
                    if (found.isEmpty() || !found.get().mutationReady() || !found.get().state().equals(after))
                        return CompletableFuture.completedFuture(false);
                    return scheduler.supply(request.anchor(), () -> !closed && effects.activate());
                }).handle((activated, failure) -> failure == null && Boolean.TRUE.equals(activated))
                .thenCompose(activated -> activated ? CompletableFuture.completedFuture(result(request, ItemLifecycle.Outcome.COMMITTED)) : freezeActivation(request, after));
    }
    private CompletableFuture<ItemLifecycle.Result> freezeActivation(Request request, State after) {
        return repository.operation(request.operation()).thenCompose(found -> {
            if (found.isPresent() && found.get().pending()) return quarantine(request, found.get());
            return repository.tower(request.tower()).thenCompose(row -> {
                if (row.isEmpty() || !row.get().mutationReady() || !row.get().state().equals(after))
                    return CompletableFuture.completedFuture(result(request, ItemLifecycle.Outcome.QUARANTINED));
                UUID review = UUID.nameUUIDFromBytes((request.operation() + ":activation").getBytes(StandardCharsets.UTF_8));
                Intent intent = new Intent(review, request.tower(), row.get().rowVersion(), 1, 0, "fixture-activation-review", after,
                        RecoveryEnvelope.fixture("fixture-activation-review",request.anchor(),request.evidence(),request.operation()));
                return repository.beginIntent(intent).thenCompose(operation -> quarantine(request, operation));
            });
        }).exceptionally(failure -> result(request, ItemLifecycle.Outcome.QUARANTINED));
    }
    private CompletableFuture<ItemLifecycle.Result> quarantine(Request request, Operation operation) {
        return repository.advanceOperation(operation.intent().id(), operation.rowVersion(), operation.phase(), Phase.RECONCILING,
                "Unavailable fixture delivery requires inspection; do not refund or activate automatically")
                .handle((ignored, failure) -> result(request, ItemLifecycle.Outcome.QUARANTINED));
    }
    private static ItemLifecycle.Result result(Request r, ItemLifecycle.Outcome outcome) { return new ItemLifecycle.Result(outcome, r.operation()); }
    @Override public void close() { closed = true; }
}
