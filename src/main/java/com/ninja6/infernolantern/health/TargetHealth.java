package com.ninja6.infernolantern.health;

import com.ninja6.infernolantern.repository.SqliteRepository;
import com.ninja6.infernolantern.scheduling.WorldScheduler;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.ninja6.infernolantern.repository.RepositoryRecords.TowerSnapshot;

/** One private health sample, bound to an exact deployment and a current viewer target. */
public final class TargetHealth {
    public record Sample(double current, double maximum) {}

    /**
     * Both methods run on the owner and must bind the same exact viewer session.
     * Null clears a previous private display while that session remains current.
     */
    public interface Audience {
        boolean targets(UUID viewer, TowerSnapshot deployed);
        void display(UUID viewer, Sample sample);
    }

    private final SqliteRepository repository;
    private final WorldScheduler scheduler;

    public TargetHealth(SqliteRepository repository, WorldScheduler scheduler) {
        this.repository = Objects.requireNonNull(repository);
        this.scheduler = Objects.requireNonNull(scheduler);
    }

    /** Clear on target loss, using the viewer's current owner anchor. */
    public CompletableFuture<Void> clear(UUID viewer, WorldScheduler.Anchor viewerAnchor, Audience audience) {
        Objects.requireNonNull(viewer);
        Objects.requireNonNull(audience);
        return scheduler.supply(viewerAnchor, () -> {
            audience.display(viewer, null);
            return null;
        });
    }

    /** The owner verifies the viewer is still targeting an exact current component. */
    public CompletableFuture<Boolean> show(UUID viewer, TowerSnapshot observed, Audience audience) {
        Objects.requireNonNull(viewer);
        Objects.requireNonNull(audience);
        var tower = observed.state().tower();
        if (tower.position() == null) return CompletableFuture.completedFuture(false);
        return repository.tower(tower.id()).thenCompose(found -> scheduler.supply(TowerHealth.anchor(tower), () -> {
            TowerSnapshot current = found.orElse(null);
            boolean visible = current != null && TowerHealth.deployed(current) && current.equals(observed)
                    && audience.targets(viewer, current);
            audience.display(viewer, visible ? new Sample(tower.hp(), tower.maximumHp()) : null);
            return visible;
        }));
    }
}
