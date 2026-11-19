package com.ninja6.infernolantern.combat;

import com.ninja6.infernolantern.catalog.CatalogConfig.WorldRef;
import com.ninja6.infernolantern.catalog.CatalogService;
import com.ninja6.infernolantern.health.TowerHealth;
import com.ninja6.infernolantern.repository.CommitPermit;
import com.ninja6.infernolantern.repository.SqliteRepository;
import com.ninja6.infernolantern.scheduling.WorldScheduler;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static com.ninja6.infernolantern.repository.RepositoryRecords.*;

/** Owner-thread combat for independently routed, loaded deployments. */
public final class ZombieCombat implements AutoCloseable {
    private static final long MELEE_TIMEOUT = 200;
    private static final int MAX_QUEUED_HITS = 32;

    public enum Mode { NORMAL, RETALIATION, PRIORITY }
    public enum Controller { ELIGIBLE, OFFLINE, DENIED, UNKNOWN }
    public enum Action { BEAM, TARGET, MELEE }
    public enum TargetReason { RETALIATION, PRIORITY, OTHER }

    public record Settings(Mode mode, double range, double damage, long intervalTicks) {
        public Settings {
            Objects.requireNonNull(mode);
            if (!Double.isFinite(range) || range <= 0 || !Float.isFinite((float) damage)
                    || (float) damage <= 0 || intervalTicks < 1) {
                throw new IllegalArgumentException("Combat requires finite positive range, damage and interval");
            }
        }
    }

    public record Conditions(WorldRef world, Controller controller, boolean fluid, boolean integrity) {
        public Conditions {
            Objects.requireNonNull(world);
            Objects.requireNonNull(controller);
        }
    }

    public record Candidate(UUID zombie, double distanceSquared) {
        public Candidate {
            Objects.requireNonNull(zombie);
            if (!Double.isFinite(distanceSquared) || distanceSquared < 0) throw new IllegalArgumentException("Distance");
        }
    }

    /** Platform handles cannot cross the storage boundary. All port calls run on the owner. */
    public interface WorldPort {
        Conditions inspect(TowerSnapshot tower);
        UUID spawnProxy(TowerSnapshot tower, UUID lease);
        void removeProxy(UUID proxy, UUID lease);
        /** Complete bounded loaded candidate set. Return empty if the query exceeds the budget. */
        List<Candidate> candidates(TowerSnapshot tower, double range, int limit);
        boolean reachable(TowerSnapshot tower, UUID proxy, UUID zombie, double range);
        boolean allows(Action action, TowerSnapshot tower, UUID proxy, UUID zombie);
        boolean hasOwnedTarget(UUID zombie, UUID proxy);
        boolean hasOtherTarget(UUID zombie, UUID proxy);
        void prioritize(UUID zombie, UUID proxy);
        void releaseTarget(UUID zombie, UUID proxy);
        boolean attack(UUID proxy, UUID zombie, double damage);
        TowerHealth.DefeatEffects defeatEffects();
    }

    private final SqliteRepository repository;
    private final CatalogService catalog;
    private final WorldScheduler scheduler;
    private final TowerHealth health;
    private final WorldPort world;
    private final Runnable requireOwner;
    private final LongSupplier clock;
    private final int perTick, candidates, concurrency;
    private final LinkedHashMap<UUID, Deployment> deployments = new LinkedHashMap<>();
    private final Map<UUID, Lease> zombies = new HashMap<>();
    private final Map<UUID, Set<Lease>> detachedMeleeByZombie = new HashMap<>();
    private final Map<UUID, Deployment> proxies = new HashMap<>();
    private final Set<UUID> unsettledCooling = new HashSet<>();
    private boolean closed;
    private final AtomicInteger inFlight = new AtomicInteger();
    private long tick;

    private static final class Deployment {
        final Tower tower;
        final CatalogService.Snapshot catalog;
        final Settings settings;
        final WorldScheduler.Anchor anchor;
        final UUID proxyToken = UUID.randomUUID();
        final ArrayDeque<AcceptedMelee> melee = new ArrayDeque<>();
        TowerSnapshot row;
        SqliteRepository.TowerObservation observation;
        CommitPermit idle = new CommitPermit();
        UUID proxy, timedOut;
        Lease lease;
        AcceptedMelee activeMelee;
        long nextAttack;
        boolean busy;
        volatile boolean unavailable;

        Deployment(TowerSnapshot row, CatalogService.Snapshot catalog, Settings settings) {
            tower = row.state().tower();
            this.row = row;
            this.catalog = catalog;
            this.settings = settings;
            Position p = tower.position();
            anchor = new WorldScheduler.Anchor(p.world(), p.x() >> 4, p.z() >> 4);
        }

        boolean sameDeployment(Tower candidate) {
            return candidate.form() == Form.EXPANDED && tower.id().equals(candidate.id())
                    && tower.generation() == candidate.generation() && tower.deploymentRevision() == candidate.deploymentRevision()
                    && tower.position().equals(candidate.position());
        }
    }

    private static final class Lease {
        final Deployment deployment;
        final UUID zombie;
        final UUID proxy;
        final CommitPermit permit = new CommitPermit();
        boolean hit, targeted, detached;
        long meleeSince;

        Lease(Deployment deployment, UUID zombie, long tick) {
            this.deployment = deployment;
            this.zombie = zombie;
            proxy = deployment.proxy;
            meleeSince = tick;
        }
    }

    private record AcceptedMelee(Lease source, double amount) {}

    public ZombieCombat(SqliteRepository repository, CatalogService catalog, WorldScheduler scheduler,
                        TowerHealth health, WorldPort world, Runnable requireOwner, LongSupplier clock,
                        int perTick, int candidates, int concurrency) {
        this.repository = Objects.requireNonNull(repository);
        this.catalog = Objects.requireNonNull(catalog);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.health = Objects.requireNonNull(health);
        this.world = Objects.requireNonNull(world);
        this.requireOwner = Objects.requireNonNull(requireOwner);
        this.clock = Objects.requireNonNull(clock);
        if (perTick < 1 || candidates < 1 || concurrency < 1) throw new IllegalArgumentException("Positive work budgets required");
        this.perTick = perTick;
        this.candidates = candidates;
        this.concurrency = concurrency;
    }

    /** The runtime router registers loaded expanded deployments and unregisters them before unload. */
    public void track(TowerSnapshot row, CatalogService.Snapshot settings, Settings combat) {
        requireOwner.run();
        Tower tower = row.state().tower();
        if (closed || tower.form() != Form.EXPANDED || !catalog.isPublished(settings)) return;
        Deployment previous = deployments.get(tower.id());
        if (previous != null && (previous.tower.generation() > tower.generation()
                || previous.tower.deploymentRevision() > tower.deploymentRevision())) return;
        if (previous != null && previous.sameDeployment(tower) && previous.catalog.equals(settings)
                && previous.settings.equals(combat)) return;
        forgetTower(tower.id());
        deployments.put(tower.id(), new Deployment(row, settings, combat));
    }

    public void untrack(TowerSnapshot expected) {
        requireOwner.run();
        Tower tower = expected.state().tower();
        Deployment current = deployments.get(tower.id());
        if (current != null && current.sameDeployment(tower)) forgetTower(tower.id());
    }

    private void forgetTower(UUID tower) {
        Deployment removed = deployments.remove(tower);
        if (removed == null) return;
        removed.idle.revoke();
        suspend(removed);
        closeObservation(removed);
    }

    /** Supply to the revision-bound repair access. A null permit denies repair. */
    public CommitPermit idle(TowerSnapshot row) {
        requireOwner.run();
        Tower tower = row.state().tower();
        Deployment current = deployments.get(tower.id());
        if (current == null || !current.sameDeployment(tower) || !row.mutationReady()
                || !catalog.isPublished(current.catalog) || current.unavailable || current.lease != null
                || current.busy || pendingMelee(current) || unsettledCooling.contains(tower.id())) return null;
        return current.idle;
    }

    /** Monotonic Paper ticks; bounded round-robin work even with many deployments. */
    public void tick(long now) {
        requireOwner.run();
        if (now < tick) throw new IllegalArgumentException("Combat ticks must be monotonic");
        tick = now;
        int visits = Math.min(deployments.size(), perTick);
        for (int index = 0; index < visits && !deployments.isEmpty(); index++) {
            UUID id = deployments.keySet().iterator().next();
            Deployment deployment = deployments.remove(id);
            deployments.put(id, deployment);
            tick(deployment);
        }
    }

    private void tick(Deployment current) {
        if (current.unavailable || !catalog.isPublished(current.catalog)) {
            forgetTower(current.tower.id());
            return;
        }
        if (current.activeMelee != null && !current.busy) return; // Dispatch can call reentrant protection hooks.
        if (current.lease != null && !leaseCurrent(current.lease)) {
            release(current, true);
            suspend(current);
        }
        Lease lease = current.lease;
        if (!effective(current) || lease == null && !detachedMeleeAllowed(current)
                || lease != null && lease.targeted && !ownedTargetAllowed(lease)) {
            suspend(current);
            lease = null;
        }
        if (current.busy || unsettledCooling.contains(current.tower.id()) || inFlight.get() >= concurrency) return;
        if (current.lease == null && !pendingMelee(current) && tick < current.nextAttack) return;
        if (!current(current)) {
            refresh(current);
            return;
        }
        if (!effective(current)) {
            suspend(current);
            closeObservation(current);
            return;
        }
        if (lease != null && !reachable(lease)) {
            suspend(current);
            lease = null;
        }
        if (!current.melee.isEmpty()) {
            damage(current.melee.removeFirst());
            return;
        }
        if (tick < current.nextAttack) return;
        current.nextAttack = tick + Math.min(Long.MAX_VALUE - tick, current.settings.intervalTicks());
        if (lease == null) lease = acquire(current);
        if (lease != null) attack(lease);
        if (current.lease == null) {
            suspend(current);
            closeObservation(current);
        }
    }

    private static void closeObservation(Deployment deployment) {
        if (deployment.observation == null) return;
        deployment.observation.close();
        deployment.observation = null;
    }

    private void refresh(Deployment deployment) {
        submit(deployment, repository.tower(deployment.tower.id()).thenCompose(found -> {
            TowerSnapshot next = found.orElse(null);
            if (next == null || !deployment.sameDeployment(next.state().tower())) return CompletableFuture.completedFuture(new Observation(next, null));
            return repository.observeTower(next).thenApply(observation -> new Observation(next, observation.orElse(null)));
        }), (observation, failure) -> {
            if (failure != null || !registered(deployment)) {
                if (observation != null && observation.permit() != null) observation.permit().close();
                return;
            }
            if (observation.row() == null || !deployment.sameDeployment(observation.row().state().tower())) {
                forgetTower(deployment.tower.id());
                return;
            }
            closeObservation(deployment);
            deployment.row = observation.row();
            deployment.observation = observation.permit();
            if (!effective(deployment)) suspend(deployment);
        });
    }

    private record Observation(TowerSnapshot row, SqliteRepository.TowerObservation permit) {}

    private Lease acquire(Deployment current) {
        var settings = current.settings;
        List<Candidate> available = world.candidates(current.row, settings.range(), candidates);
        if (available.isEmpty() || available.size() > candidates || !current(current)) return null;
        if (current.proxy == null) {
            current.proxy = world.spawnProxy(current.row, current.proxyToken);
            if (current.proxy == null) return null;
            proxies.put(current.proxy, current);
        }
        if (current.timedOut != null && !world.reachable(current.row, current.proxy, current.timedOut, settings.range())) {
            current.timedOut = null;
        }
        UUID zombie = available.stream().filter(candidate -> !candidate.zombie().equals(current.timedOut))
                .filter(candidate -> !zombies.containsKey(candidate.zombie()))
                .sorted(Comparator.comparingDouble(Candidate::distanceSquared).thenComparing(Candidate::zombie))
                .map(Candidate::zombie)
                .filter(id -> world.reachable(current.row, current.proxy, id, settings.range()))
                .filter(id -> settings.mode() == Mode.NORMAL || !world.hasOtherTarget(id, current.proxy))
                .filter(id -> world.allows(Action.BEAM, current.row, current.proxy, id))
                .findFirst().orElse(null);
        if (zombie == null || !current(current)) return null;
        current.idle.revoke();
        Lease lease = new Lease(current, zombie, tick);
        current.lease = lease;
        zombies.put(zombie, lease);
        if (settings.mode() == Mode.PRIORITY && world.allows(Action.TARGET, current.row, current.proxy, zombie)) {
            world.prioritize(zombie, current.proxy);
        }
        return lease;
    }

    private void attack(Lease lease) {
        Deployment current = lease.deployment;
        if (!allows(lease, Action.BEAM) || !world.attack(current.proxy, lease.zombie, current.settings.damage())) {
            if (current.lease == lease) release(current, false);
            return;
        }
        if (!lease.hit) lease.meleeSince = tick;
        lease.hit = true;
        var definition = current.catalog.entries().get(current.tower.variantId()).definition();
        if (definition.repair() != null) {
            long attackedAt = clock.getAsLong();
            UUID tower = current.tower.id();
            unsettledCooling.add(tower);
            submit(current, persistCooling(current, attackedAt), (row, failure) -> {
                if (failure == null) unsettledCooling.remove(tower);
                else if (registered(current)) {
                    suspend(current);
                    closeObservation(current);
                }
            });
        }
    }

    private CompletableFuture<TowerSnapshot> persistCooling(Deployment current, long attackedAt) {
        return repository.recordRepairCooling(current.catalog.revision(), current.row, attackedAt, null);
    }

    private void damage(AcceptedMelee hit) {
        Lease lease = hit.source();
        Deployment current = lease.deployment;
        // Keep the dequeued source visible to revocation while protection callbacks run.
        current.activeMelee = hit;
        boolean handedOff = false;
        try {
            if (!currentDamageAllowed(hit)) return;
            var configured = current.catalog.entries().get(current.tower.variantId()).definition().repair();
            long cooling = configured == null ? 0 : configured.coolingMillis();
            var defeatEffects = world.defeatEffects();
            if (current.activeMelee != hit) return;
            submit(current, health.damage(current.catalog, current.row, new TowerHealth.Damage(hit.amount(), cooling),
                    row -> currentDamageAllowed(hit) ? new TowerHealth.Admission(lease.permit) : null,
                    UUID.randomUUID(), defeatEffects), (result, failure) ->
                    finishMelee(hit, failure != null || result.settled().state().tower().hp() == 0));
            handedOff = true;
        } finally {
            if (!handedOff) finishMelee(hit, false);
        }
    }

    private boolean currentDamageAllowed(AcceptedMelee hit) {
        Deployment deployment = hit.source().deployment;
        return deployment.activeMelee == hit && acceptedDamageAllowed(hit.source()) && deployment.activeMelee == hit;
    }

    private void finishMelee(AcceptedMelee hit, boolean stop) {
        Lease source = hit.source();
        Deployment deployment = source.deployment;
        if (deployment.activeMelee != hit) return;
        deployment.activeMelee = null;
        if (source.detached && deployment.melee.isEmpty()) revokeMelee(source);
        if (registered(deployment) && source.proxy.equals(deployment.proxy)
                && (stop || deployment.lease == null && deployment.melee.isEmpty())) {
            suspend(deployment);
            closeObservation(deployment);
        }
    }

    private boolean acceptedDamageAllowed(Lease source) {
        Deployment deployment = source.deployment;
        if (!source.detached && incomingAllowed(source)) return true;
        // A protection callback can also publish a newer unrelated target during authorization.
        return source.detached && current(deployment) && effective(deployment) && detachedProtectionAllows(source)
                && current(deployment);
    }

    private boolean detachedMeleeAllowed(Deployment deployment) {
        AcceptedMelee hit = deployment.activeMelee;
        if (hit == null) hit = deployment.melee.peekFirst();
        return hit != null && detachedProtectionAllows(hit.source());
    }

    private boolean detachedProtectionAllows(Lease source) {
        Deployment deployment = source.deployment;
        return source.detached && source.proxy.equals(deployment.proxy)
                && world.allows(Action.TARGET, deployment.row, source.proxy, source.zombie)
                && world.allows(Action.MELEE, deployment.row, source.proxy, source.zombie)
                && source.proxy.equals(deployment.proxy);
    }

    private static boolean pendingMelee(Deployment deployment) {
        return deployment.activeMelee != null || !deployment.melee.isEmpty();
    }

    /** Exact lease captured before damage is zeroed. Only the native adapter can request one. */
    static final class MeleeProof {
        private final Lease lease;
        private boolean delivered;

        private MeleeProof(Lease lease) {
            this.lease = lease;
        }
    }

    MeleeProof captureMelee(UUID proxy, UUID zombie) {
        requireOwner.run();
        Lease lease = bound(proxy, zombie);
        return lease != null && incomingAllowed(lease) ? new MeleeProof(lease) : null;
    }

    boolean melee(MeleeProof proof, double amount) {
        requireOwner.run();
        if (proof == null || proof.delivered) return false;
        proof.delivered = true;
        if (!Double.isFinite(amount) || amount <= 0) return false;
        Lease lease = proof.lease;
        if (!incomingAllowed(lease) || lease.deployment.melee.size() >= MAX_QUEUED_HITS) return false;
        lease.deployment.melee.addLast(new AcceptedMelee(lease, amount));
        lease.meleeSince = tick;
        return true;
    }

    boolean permitsBeam(UUID proxy, UUID zombie) {
        requireOwner.run();
        Lease lease = bound(proxy, zombie);
        return lease != null && allows(lease, Action.BEAM);
    }

    public boolean permitsTarget(UUID proxy, UUID zombie, TargetReason reason, boolean acceptedBeam) {
        requireOwner.run();
        Lease lease = bound(proxy, zombie);
        if (lease == null || !allows(lease, Action.TARGET) || world.hasOtherTarget(zombie, proxy)) return false;
        return switch (lease.deployment.settings.mode()) {
            case NORMAL -> false;
            case RETALIATION -> (lease.hit || acceptedBeam) && (lease.targeted || reason == TargetReason.RETALIATION);
            case PRIORITY -> lease.targeted || reason == TargetReason.PRIORITY || reason == TargetReason.RETALIATION && acceptedBeam;
        };
    }

    public void targetChanged(UUID zombie, UUID proxy) {
        requireOwner.run();
        Lease lease = zombies.get(zombie);
        if (lease == null) return;
        if (Objects.equals(lease.proxy, proxy)) {
            lease.targeted = lease.deployment.settings.mode() != Mode.NORMAL;
        } else if (lease.targeted) {
            // The event has not published its newer target yet; leave it untouched.
            lease.detached = true;
            forget(lease.deployment, false);
        }
    }

    public void zombieGone(UUID zombie) {
        requireOwner.run();
        Lease lease = zombies.get(zombie);
        if (lease != null && lease.deployment.lease == lease) suspend(lease.deployment);
        Set<Lease> detached = detachedMeleeByZombie.remove(zombie);
        if (detached != null) detached.forEach(this::detachedZombieGone);
    }

    private void detachedZombieGone(Lease source) {
        Deployment deployment = source.deployment;
        boolean active = deployment.activeMelee != null && deployment.activeMelee.source() == source;
        boolean queued = deployment.melee.removeIf(hit -> hit.source() == source);
        revokeMelee(source);
        if ((active || queued) && deployment.lease == null && source.proxy.equals(deployment.proxy)) {
            suspend(deployment);
        }
    }

    public void proxyGone(UUID proxy) {
        requireOwner.run();
        Deployment deployment = proxies.get(proxy);
        if (deployment != null) forgetTower(deployment.tower.id());
    }

    private Lease bound(UUID proxy, UUID zombie) {
        Lease lease = zombies.get(zombie);
        return lease != null && lease.deployment == proxies.get(proxy) ? lease : null;
    }

    private boolean incomingAllowed(Lease lease) {
        return lease.targeted && allows(lease, Action.MELEE) && ownedTargetAllowed(lease)
                && current(lease.deployment);
    }

    private boolean ownedTargetAllowed(Lease lease) {
        Deployment deployment = lease.deployment;
        return leaseCurrent(lease) && world.hasOwnedTarget(lease.zombie, deployment.proxy)
                && world.allows(Action.TARGET, deployment.row, deployment.proxy, lease.zombie)
                && leaseCurrent(lease) && world.hasOwnedTarget(lease.zombie, deployment.proxy);
    }

    private boolean allows(Lease lease, Action action) {
        Deployment deployment = lease.deployment;
        return leaseCurrent(lease) && current(deployment) && effective(deployment) && reachable(lease)
                && world.allows(action, deployment.row, deployment.proxy, lease.zombie)
                && leaseCurrent(lease) && current(deployment);
    }

    private boolean reachable(Lease lease) {
        Deployment deployment = lease.deployment;
        return world.reachable(deployment.row, deployment.proxy, lease.zombie, deployment.settings.range())
                && (deployment.settings.mode() == Mode.NORMAL || !world.hasOtherTarget(lease.zombie, deployment.proxy));
    }

    private boolean leaseCurrent(Lease lease) {
        Deployment deployment = lease.deployment;
        return registered(deployment) && deployment.lease == lease && zombies.get(lease.zombie) == lease
                && (deployment.settings.mode() == Mode.NORMAL || tick - lease.meleeSince < MELEE_TIMEOUT);
    }

    private boolean effective(Deployment deployment) {
        Conditions conditions = world.inspect(deployment.row);
        if (conditions == null) return false;
        boolean eligible = conditions.controller() == Controller.ELIGIBLE || conditions.controller() == Controller.OFFLINE;
        return CatalogService.effective(deployment.catalog, deployment.row, conditions.world(),
                new CatalogService.Conditions(eligible, conditions.fluid(), false, true, conditions.integrity())).defence();
    }

    private boolean registered(Deployment deployment) {
        return !closed && deployments.get(deployment.tower.id()) == deployment;
    }

    private boolean current(Deployment deployment) {
        return registered(deployment) && !deployment.unavailable && catalog.isPublished(deployment.catalog)
                && deployment.observation != null && deployment.observation.current();
    }

    private void release(Deployment deployment, boolean timeout) {
        Lease old = forget(deployment, timeout);
        if (old != null) world.releaseTarget(old.zombie, deployment.proxy);
    }

    private Lease forget(Deployment deployment, boolean timeout) {
        Lease old = deployment.lease;
        if (old == null) return null;
        deployment.lease = null;
        zombies.remove(old.zombie, old);
        if (old.detached && pendingMelee(deployment)) {
            detachedMeleeByZombie.computeIfAbsent(old.zombie, key -> new HashSet<>()).add(old);
        } else {
            revokeMelee(old);
        }
        if (timeout) deployment.timedOut = old.zombie;
        deployment.idle = new CommitPermit();
        return old;
    }

    private void revokeMelee(Lease source) {
        source.permit.revoke();
        Set<Lease> detached = detachedMeleeByZombie.get(source.zombie);
        if (detached != null && detached.remove(source) && detached.isEmpty()) {
            detachedMeleeByZombie.remove(source.zombie);
        }
    }

    private void suspend(Deployment deployment) {
        AcceptedMelee active = deployment.activeMelee;
        deployment.activeMelee = null;
        if (active != null) revokeMelee(active.source());
        deployment.melee.forEach(hit -> revokeMelee(hit.source()));
        deployment.melee.clear();
        try {
            release(deployment, false);
        } finally {
            UUID proxy = deployment.proxy;
            if (proxy != null) {
                deployment.proxy = null;
                proxies.remove(proxy, deployment);
                world.removeProxy(proxy, deployment.proxyToken);
            }
        }
    }

    @FunctionalInterface
    private interface Completed<T> { void accept(T result, Throwable failure); }

    private <T> void submit(Deployment deployment, CompletableFuture<T> operation, Completed<T> completed) {
        deployment.busy = true;
        inFlight.incrementAndGet();
        operation.whenComplete((result, failure) -> {
            var released = new AtomicBoolean();
            Runnable releaseSlot = () -> {
                if (released.compareAndSet(false, true)) inFlight.decrementAndGet();
            };
            try {
                scheduler.supply(deployment.anchor, () -> {
                    // A later owner tick can run before the scheduling future publishes completion.
                    releaseSlot.run();
                    deployment.busy = false;
                    completed.accept(result, failure);
                    return null;
                }).whenComplete((ignored, unavailable) -> {
                    if (unavailable != null) {
                        unavailable(deployment, result);
                        releaseSlot.run();
                    }
                });
            } catch (RuntimeException unavailable) {
                unavailable(deployment, result);
                releaseSlot.run();
            }
        });
    }

    private static void unavailable(Deployment deployment, Object result) {
        deployment.unavailable = true;
        if (result instanceof Observation observation && observation.permit() != null) observation.permit().close();
    }

    @Override public void close() {
        requireOwner.run();
        closed = true;
        List.copyOf(deployments.keySet()).forEach(this::forgetTower);
    }
}
