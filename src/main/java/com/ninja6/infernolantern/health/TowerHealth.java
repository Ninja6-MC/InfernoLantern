package com.ninja6.infernolantern.health;

import com.ninja6.infernolantern.catalog.CatalogService;
import com.ninja6.infernolantern.repository.CommitPermit;
import com.ninja6.infernolantern.repository.RepositoryException;
import com.ninja6.infernolantern.repository.SqliteRepository;
import com.ninja6.infernolantern.scheduling.WorldScheduler;
import com.ninja6.infernolantern.structure.StructureLifecycle;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

import static com.ninja6.infernolantern.repository.RepositoryRecords.*;

/** Owner-authorized non-blast damage and journaled defeat. Native event routing is external. */
public final class TowerHealth {
    public record Damage(double amount, long repairCoolingMillis) {
        public Damage {
            if (!Double.isFinite(amount) || amount <= 0 || repairCoolingMillis < 0) {
                throw new IllegalArgumentException("Damage and repair cooling bounds");
            }
        }
    }

    /** A current source/protection decision. No player or administrative override is inferred. */
    public record Admission(CommitPermit permit) {}

    @FunctionalInterface public interface DamageAccess {
        Admission authorize(TowerSnapshot deployed);
    }

    /** Create fresh system effects on the owner. The effect port must recheck physical access. */
    @FunctionalInterface public interface DefeatEffects {
        StructureLifecycle.WorldEffects prepare(TowerSnapshot zeroHp);
    }

    /** Damage has settled even when its subsequent physical compaction is rejected or quarantined. */
    public record DamageResult(TowerSnapshot settled, StructureLifecycle.Result defeat) {}

    private final SqliteRepository repository;
    private final CatalogService catalog;
    private final WorldScheduler scheduler;
    private final StructureLifecycle structures;
    private final LongSupplier clock;

    public TowerHealth(SqliteRepository repository, CatalogService catalog, WorldScheduler scheduler,
                       StructureLifecycle structures, LongSupplier clock) {
        this.repository = Objects.requireNonNull(repository);
        this.catalog = Objects.requireNonNull(catalog);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.structures = Objects.requireNonNull(structures);
        this.clock = Objects.requireNonNull(clock);
    }

    public CompletableFuture<DamageResult> damage(CatalogService.Snapshot settings, TowerSnapshot observed,
                                                   Damage damage, DamageAccess access, UUID defeatOperation,
                                                   DefeatEffects effects) {
        Objects.requireNonNull(damage);
        Objects.requireNonNull(access);
        Objects.requireNonNull(defeatOperation);
        Objects.requireNonNull(effects);
        Tower tower = observed.state().tower();
        var entry = settings.entries().get(tower.variantId());
        if (!deployed(observed) || tower.hp() <= 0 || entry == null || entry.status() != VariantStatus.ACTIVE) {
            return denied();
        }
        return repository.tower(tower.id()).thenCompose(current -> {
            if (current.isEmpty() || !observed.equals(current.get())) return denied();
            return scheduler.supply(anchor(tower), () -> {
                if (!catalog.isPublished(settings)) return null;
                Admission admission = access.authorize(observed);
                if (admission == null || !catalog.isPublished(settings)) return null;
                return new AuthorizedDamage(admission, Math.addExact(now(), damage.repairCoolingMillis()));
            }).thenCompose(authorized -> {
                if (authorized == null) return denied();
                return repository.damageTower(settings.revision(), tower.id(), observed.rowVersion(), tower.generation(),
                        tower.deploymentRevision(), damage.amount(), authorized.repairAfter(), authorized.admission().permit());
            }).thenCompose(settled -> {
                if (settled.state().tower().hp() > 0) {
                    return CompletableFuture.completedFuture(new DamageResult(settled, null));
                }
                return compactDefeated(settings, settled, defeatOperation, effects)
                        .thenApply(result -> new DamageResult(settled, result));
            });
        });
    }

    private record AuthorizedDamage(Admission admission, long repairAfter) {}
    private record DefeatPlan(StructureLifecycle.Policy policy, StructureLifecycle.WorldEffects effects) {}

    /** Observer may retry a ready zero-HP deployment with fresh policy and a fresh operation ID. */
    public CompletableFuture<StructureLifecycle.Result> compactDefeated(CatalogService.Snapshot settings,
            TowerSnapshot observed, UUID operation, DefeatEffects effects) {
        Objects.requireNonNull(operation);
        Objects.requireNonNull(effects);
        Tower tower = observed.state().tower();
        var entry = settings.entries().get(tower.variantId());
        var rejected = new StructureLifecycle.Result(StructureLifecycle.Outcome.REJECTED, operation);
        if (!deployed(observed) || tower.hp() != 0 || entry == null) return CompletableFuture.completedFuture(rejected);
        return scheduler.supply(anchor(tower), () -> {
            if (!catalog.isPublished(settings)) return null;
            var definition = entry.definition();
            long exitTime = now();
            Math.addExact(exitTime, definition.expansionCooldownMillis());
            var policy = new StructureLifecycle.Policy(settings.revision(), definition.expansionPercent(),
                    definition.expansionCooldownMillis(), exitTime, false);
            var prepared = effects.prepare(observed);
            return prepared == null ? null : new DefeatPlan(policy, prepared);
        }).handle((plan, failure) -> failure == null ? plan : null)
                .thenCompose(plan -> plan == null ? CompletableFuture.completedFuture(rejected)
                        : structures.defeat(operation, observed, plan.policy(), plan.effects(), () -> catalog.isPublished(settings)));
    }

    static boolean deployed(TowerSnapshot row) {
        return row.mutationReady() && row.state().tower().form() == Form.EXPANDED;
    }

    static WorldScheduler.Anchor anchor(Tower tower) {
        Position p = tower.position();
        return new WorldScheduler.Anchor(p.world(), p.x() >> 4, p.z() >> 4);
    }

    private long now() {
        long current = clock.getAsLong();
        if (current < 0) throw new IllegalArgumentException("Invalid wall clock");
        return current;
    }

    private static <T> CompletableFuture<T> denied() {
        return CompletableFuture.failedFuture(new RepositoryException.Conflict("Damage target or access changed"));
    }
}
