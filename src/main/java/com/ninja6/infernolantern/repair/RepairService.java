package com.ninja6.infernolantern.repair;

import com.ninja6.infernolantern.catalog.CatalogService;
import com.ninja6.infernolantern.repository.CommitPermit;
import com.ninja6.infernolantern.repository.RepairPayment;
import com.ninja6.infernolantern.repository.RepositoryException;
import com.ninja6.infernolantern.repository.SqliteRepository;
import com.ninja6.infernolantern.scheduling.WorldScheduler;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import static com.ninja6.infernolantern.repository.RepositoryRecords.*;

/** Revision-bound repair quotes and one journaled, owner-thread inventory debit. */
public final class RepairService {
    public enum Outcome { REJECTED, COMMITTED, RESTORED, QUARANTINED }

    /** All methods except committed run on the owner. Require current permissions and a revocable idle-combat proof. */
    @FunctionalInterface
    public interface Access {
        Authorization authorize(TowerSnapshot observed, long now);
        default void committed(Operation receipt) {}
    }

    public record Authorization(UUID actor, UUID session, CommitPermit permit) {
        public Authorization {
            Objects.requireNonNull(actor);
            Objects.requireNonNull(session);
            Objects.requireNonNull(permit);
        }

        boolean owns(RepairPayment payment) {
            return actor.equals(payment.contributor()) && session.equals(payment.session());
        }
    }

    /** A single captured slot. Inspect and change on the owner; release is thread-safe and touches no native state. */
    public interface Inventory {
        String resource();
        int offered();
        RepairPayment quote(int items);
        boolean sourceMatches(RepairPayment payment);
        CommitPermit slotPermit();
        boolean debit(RepairPayment payment);
        boolean debitMatches(RepairPayment payment);
        boolean restore(RepairPayment payment);
        void release();
    }

    public record Details(String resource, int items, double currentHp, double maximumHp, double resultingHp,
                          BigDecimal hpGain, boolean partialFinalItem, double expansionPercent,
                          BigDecimal hpToExpansion, Map<String,Double> rates) {}

    public final class Preview {
        private final UUID operation = UUID.randomUUID();
        private final CatalogService.Snapshot settings;
        private final TowerSnapshot observed;
        private final Inventory inventory;
        private final Access access;
        private final RepairPayment payment;
        private final Details details;
        private final CommitPermit menuPermit = new CommitPermit();
        private final AtomicBoolean decided = new AtomicBoolean();

        private Preview(CatalogService.Snapshot settings, TowerSnapshot observed, Inventory inventory,
                        Access access, RepairPayment payment, Details details) {
            this.settings = settings;
            this.observed = observed;
            this.inventory = inventory;
            this.access = access;
            this.payment = payment;
            this.details = details;
        }

        public UUID operation() { return operation; }
        public Details details() { return details; }

        /** Confirm this exact, displayed cost and gain. One callback can admit an operation. */
        public CompletableFuture<Outcome> confirm() {
            if (!decided.compareAndSet(false,true)) return done(Outcome.REJECTED);
            return confirmPreview(this).whenComplete((result,failure) -> inventory.release());
        }

        /** Close/revoke this menu, including one awaiting database admission. */
        public void cancel() {
            menuPermit.revoke();
            if (decided.compareAndSet(false,true)) inventory.release();
        }
    }

    private final SqliteRepository repository;
    private final CatalogService catalog;
    private final WorldScheduler scheduler;
    private final LongSupplier clock;

    public RepairService(SqliteRepository repository, CatalogService catalog, WorldScheduler scheduler, LongSupplier clock) {
        this.repository = Objects.requireNonNull(repository);
        this.catalog = Objects.requireNonNull(catalog);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.clock = Objects.requireNonNull(clock);
    }

    public CompletableFuture<Optional<Preview>> preview(CatalogService.Snapshot settings, TowerSnapshot observed,
                                                         Inventory inventory, Access access) {
        var tower = observed.state().tower();
        if (tower.position() == null) {
            inventory.release();
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return repository.tower(tower.id()).thenCompose(current -> scheduler.<Optional<Preview>>supply(anchor(tower), () -> {
            if (current.isEmpty() || !observed.equals(current.get())) return Optional.empty();
            long now = clock.getAsLong();
            if (!eligible(settings, observed, now)) return Optional.empty();
            Authorization authorization = access.authorize(observed, now);
            if (authorization == null) return Optional.empty();
            var definition = settings.entries().get(tower.variantId()).definition();
            var rates = definition.repair().resources();
            String resource = inventory.resource();
            Double rate = rates.get(resource);
            if (rate == null) return Optional.empty();
            var quote = RepairQuote.calculate(tower.hp(), tower.maximumHp(), rate, inventory.offered()).orElse(null);
            if (quote == null) return Optional.empty();
            var payment = inventory.quote(quote.items());
            if (payment == null || !authorization.owns(payment) || payment.items() != quote.items() || !payment.resource().equals(resource)
                    || !inventory.sourceMatches(payment) || inventory.slotPermit() == null
                    || !catalog.isPublished(settings)) return Optional.empty();
            BigDecimal remaining = BigDecimal.valueOf(tower.maximumHp())
                    .multiply(BigDecimal.valueOf(definition.expansionPercent())).movePointLeft(2)
                    .subtract(BigDecimal.valueOf(tower.hp())).max(BigDecimal.ZERO);
            var details = new Details(resource, quote.items(), tower.hp(), tower.maximumHp(), quote.resultingHp(),
                    quote.hpGain(), quote.partialFinalItem(), definition.expansionPercent(), remaining, rates);
            return Optional.of(new Preview(settings, observed, inventory, access, payment, details));
        })).whenComplete((preview, failure) -> {
            if (failure != null || preview.isEmpty()) inventory.release();
        });
    }

    private record Admission(long now, CommitPermit permit) {}

    private CompletableFuture<Outcome> confirmPreview(Preview p) {
        return scheduler.supply(anchor(p.observed.state().tower()), () -> authorize(p, false))
                .handle((admission,failure) -> failure == null ? admission : null)
                .thenCompose(admission -> admission == null ? done(Outcome.REJECTED)
                        : repository.beginRepair(p.operation, p.observed, p.settings.revision(), p.payment,
                                admission.now(), admission.permit())
                        .handle((operation,failure) -> {
                            if (failure == null) return debit(p, operation);
                            return knownRejection(failure) ? done(Outcome.REJECTED) : quarantine(p.operation);
                        }).thenCompose(result -> result));
    }

    private CompletableFuture<Outcome> debit(Preview p, Operation operation) {
        return scheduler.supply(anchor(p.observed.state().tower()), () -> {
            Admission admission = authorize(p, false);
            if (admission == null) return false;
            admission.permit().requireValid();
            return p.inventory.debit(p.payment) && p.inventory.debitMatches(p.payment);
        }).handle((applied,failure) -> failure == null && Boolean.TRUE.equals(applied))
                .thenCompose(applied -> applied
                        ? repository.advanceOperation(p.operation, operation.rowVersion(), Phase.INTENT, Phase.EFFECTS_APPLIED,
                                "Exact inventory debit observed; player save remains independent")
                            .thenCompose(verified -> commit(p, verified))
                        : restore(p, operation))
                .exceptionallyCompose(failure -> quarantine(p.operation));
    }

    private CompletableFuture<Outcome> commit(Preview p, Operation operation) {
        return scheduler.supply(anchor(p.observed.state().tower()), () -> authorize(p, true))
                .handle((admission,failure) -> failure == null ? admission : null)
                .thenCompose(admission -> admission == null ? restore(p, operation)
                        : repository.settleRepair(p.operation, operation.rowVersion(), p.settings.revision(),
                                false, clock, admission.permit())
                                .handle((receipt,failure) -> {
                                    if (failure != null) return knownRejection(failure)
                                            ? restore(p, operation) : quarantine(p.operation);
                                    try {
                                        p.access.committed(receipt);
                                    } catch (RuntimeException unavailable) {
                                        // The operation is already durable. A receipt callback cannot change its outcome.
                                    }
                                    return done(Outcome.COMMITTED);
                                }).thenCompose(result -> result));
    }

    private CompletableFuture<Outcome> restore(Preview p, Operation operation) {
        return repository.advanceOperation(p.operation, operation.rowVersion(), operation.phase(), Phase.RECONCILING,
                "Repair needs exact owner-side restoration proof").thenCompose(pending ->
                scheduler.supply(anchor(p.observed.state().tower()), () -> p.inventory.restore(p.payment))
                        .thenCompose(restored -> restored
                                ? repository.settleRepair(p.operation, pending.rowVersion(), p.settings.revision(),
                                        true, null, null).thenApply(ignored -> Outcome.RESTORED)
                                : done(Outcome.QUARANTINED)))
                .exceptionallyCompose(failure -> quarantine(p.operation));
    }

    private CompletableFuture<Outcome> quarantine(UUID operation) {
        return repository.operation(operation).thenCompose(found -> {
            Operation row = found.orElse(null);
            if (row == null || !row.pending() || row.phase() == Phase.RECONCILING) return done(Outcome.QUARANTINED);
            return repository.advanceOperation(operation, row.rowVersion(), row.phase(), Phase.RECONCILING,
                    "Repair save/effect outcome unproved").thenApply(ignored -> Outcome.QUARANTINED);
        }).exceptionally(failure -> Outcome.QUARANTINED);
    }

    private Admission authorize(Preview p, boolean debited) {
        long now = clock.getAsLong();
        if (!eligible(p.settings, p.observed, now)) return null;
        Authorization access = p.access.authorize(p.observed, now);
        CommitPermit slot = p.inventory.slotPermit();
        if (access == null || !access.owns(p.payment) || slot == null || (!debited && !p.inventory.sourceMatches(p.payment))
                || (debited && !p.inventory.debitMatches(p.payment)) || !catalog.isPublished(p.settings)) return null;
        return new Admission(now, CommitPermit.allOf(access.permit(), slot, p.menuPermit));
    }

    private boolean eligible(CatalogService.Snapshot settings, TowerSnapshot observed, long now) {
        Tower t = observed.state().tower();
        var entry = settings.entries().get(t.variantId());
        return now >= 0 && now >= t.repairAfter() && observed.mutationReady()
                && (t.form() == Form.COMPACT || t.form() == Form.EXPANDED && t.hp() > 0)
                && t.hp() < t.maximumHp() && entry != null && entry.status() == VariantStatus.ACTIVE
                && entry.definition().repair() != null && catalog.isPublished(settings);
    }

    private static boolean knownRejection(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        return failure.getSuppressed().length == 0 && (failure instanceof RepositoryException.Conflict
                || failure instanceof RepositoryException.CatalogSuperseded
                || failure instanceof RepositoryException.CommitRevoked);
    }

    private static CompletableFuture<Outcome> done(Outcome result) {
        return CompletableFuture.completedFuture(result);
    }

    private static WorldScheduler.Anchor anchor(Tower tower) {
        Position position = tower.position();
        return new WorldScheduler.Anchor(position.world(), position.x() >> 4, position.z() >> 4);
    }
}
