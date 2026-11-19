package com.ninja6.infernolantern.combat;

import com.ninja6.infernolantern.catalog.CatalogConfig.Dimension;
import com.ninja6.infernolantern.catalog.CatalogConfig.WorldRef;
import com.ninja6.infernolantern.catalog.CatalogService;
import com.ninja6.infernolantern.health.TowerHealth;
import com.ninja6.infernolantern.repository.RepositoryException;
import com.ninja6.infernolantern.repository.SqliteRepository;
import com.ninja6.infernolantern.repository.SqliteTestConnections;
import com.ninja6.infernolantern.scheduling.WorldScheduler;
import com.ninja6.infernolantern.structure.StructureLifecycle;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.ninja6.infernolantern.combat.ZombieCombat.*;
import static com.ninja6.infernolantern.repository.RepositoryRecords.*;
import static org.junit.jupiter.api.Assertions.*;

class ZombieCombatTest {
    private static final UUID WORLD = new UUID(0, 1);
    private static final UUID ACTOR = new UUID(0, 2);
    private static final UUID FIRST = new UUID(0, 3);
    private static final UUID SECOND = new UUID(0, 4);
    private static final UUID EXTERNAL = new UUID(0, 5);

    @TempDir Path directory;
    final Owner owner = new Owner();
    final WorldFake world = new WorldFake();
    SqliteRepository repository;
    CatalogService catalog;
    CatalogService.Snapshot settings;
    StructureLifecycle structures;
    ZombieCombat combat;
    TowerSnapshot tower;

    @BeforeEach void open() throws Exception {
        repository = get(SqliteRepository.open(directory.resolve("combat.db"), 32));
        catalog = new CatalogService(repository, owner, definition -> {}, ready -> CompletableFuture.completedFuture(null));
        structures = new StructureLifecycle(repository, owner, () -> 1_000);
        var health = new TowerHealth(repository, catalog, owner, structures, () -> 1_000);
        combat = new ZombieCombat(repository, catalog, owner, health, world, owner::require, () -> 1_000, 1, 8, 1);
        settings = get(catalog.reload(config(true))).snapshot();
        tower = placed(4);
    }

    @AfterEach void close() throws Exception {
        combat.close();
        structures.close();
        catalog.close();
        get(repository.closeAsync());
    }

    @Test void offlineDefenceChoosesStableNearestAndDoesNotRedirectNormalTargeting() throws Exception {
        world.controller = Controller.OFFLINE;
        world.available = List.of(new Candidate(SECOND, 4), new Candidate(FIRST, 4));
        world.targets.put(FIRST, EXTERNAL);
        start(Mode.NORMAL, 10);
        UUID proxy = world.proxy();
        assertEquals(List.of(FIRST), world.attacks);
        assertFalse(combat.permitsTarget(proxy, FIRST, TargetReason.RETALIATION, true));
        assertEquals(1_400, current().state().tower().repairAfter());
        owner.next(); // Cooling result.
        combat.tick(10);
        owner.next(); // Refreshed row.
        world.available = List.of(new Candidate(SECOND, 1), new Candidate(FIRST, 4));
        combat.tick(10);
        assertEquals(List.of(FIRST, FIRST), world.attacks);
        owner.next();
        world.controller = Controller.DENIED;
        combat.tick(11);
        if (!owner.work.isEmpty()) owner.next();
        combat.tick(20);
        assertEquals(2, world.attacks.size());
        assertEquals(EXTERNAL, world.targets.get(FIRST));
        assertTrue(world.removed.contains(proxy));
    }

    @Test void cancelledBeamCannotOpenRetaliationOrStartCooling() throws Exception {
        world.acceptAttack = false;
        start(Mode.RETALIATION, 10);
        assertEquals(List.of(FIRST), world.attacks);
        UUID proxy = world.removed.getFirst();
        assertFalse(combat.permitsTarget(proxy, FIRST, TargetReason.RETALIATION, false));
        assertEquals(0, current().state().tower().repairAfter());
        assertNotNull(combat.idle(current()));
    }

    @Test void acceptedMeleeIsSerializedAndRestartsOnlyTheQuietWindow() throws Exception {
        start(Mode.RETALIATION, 1_000);
        settledCooling();
        UUID proxy = world.proxy();
        assertFalse(combat.permitsTarget(proxy, FIRST, TargetReason.OTHER, false));
        world.denied.add(Action.TARGET);
        assertFalse(combat.permitsTarget(proxy, FIRST, TargetReason.RETALIATION, false));
        world.denied.clear();
        target(proxy, FIRST);
        combat.tick(180);
        var proof = combat.captureMelee(proxy, FIRST);
        assertNotNull(proof);
        assertTrue(combat.melee(proof, 3.5));
        assertFalse(combat.melee(proof, 2.0));
        assertTrue(combat.melee(combat.captureMelee(proxy, FIRST), 2.0));
        combat.tick(180);
        owner.next(); // Damage owner authorization.
        owner.next(); // Damage committed.
        assertEquals(71.5, current().state().tower().hp());
        combat.tick(181);
        owner.next();
        combat.tick(181);
        owner.next();
        owner.next();
        assertEquals(69.5, current().state().tower().hp());
        combat.tick(379);
        assertEquals(proxy, world.targets.get(FIRST));
        combat.tick(380);
        assertFalse(world.targets.containsKey(FIRST));
        assertTrue(world.removed.contains(proxy));
        assertFalse(combat.melee(proof, 10));
    }

    @Test void priorityUsesCurrentProviderAndLeavesExternalReplacementUntouched() throws Exception {
        world.denied.add(Action.TARGET);
        start(Mode.PRIORITY, 5);
        assertEquals(0, world.priorities);
        settledCooling();
        UUID old = world.proxy();
        assertFalse(combat.permitsTarget(old, FIRST, TargetReason.PRIORITY, false));
        world.denied.clear();
        combat.zombieGone(FIRST);
        combat.tick(5);
        UUID proxy = world.proxy();
        assertEquals(1, world.priorities);
        assertEquals(proxy, world.targets.get(FIRST));
        combat.targetChanged(FIRST, null);
        world.targets.put(FIRST, EXTERNAL);
        combat.untrack(tower);
        assertEquals(EXTERNAL, world.targets.get(FIRST));
        assertTrue(world.removed.contains(proxy));
    }

    @Test void ownerDeliveryReleasesWorkCapacityBeforeSchedulingFuturePublishes() throws Exception {
        combat.track(tower, settings, new Settings(Mode.NORMAL, 6, 2, 1_000));
        combat.tick(0);
        owner.deferCompletion = true;
        owner.next();
        owner.deferCompletion = false;
        try {
            combat.tick(0);
            assertEquals(List.of(FIRST), world.attacks, "Owner work must release its slot before the next tick");
        } finally {
            owner.publishDeferred();
        }
        owner.next();
    }

    @Test void targetPermissionLossRejectsCapturedAndQueuedMeleeAndReleasesOwnedTarget() throws Exception {
        start(Mode.RETALIATION, 1_000);
        settledCooling();
        UUID proxy = world.proxy();
        target(proxy, FIRST);
        var queued = combat.captureMelee(proxy, FIRST);
        var captured = combat.captureMelee(proxy, FIRST);
        assertTrue(combat.melee(queued, 5));
        combat.tick(2);
        owner.waitForWork();
        world.denied.add(Action.TARGET);
        assertFalse(combat.melee(captured, 2));
        assertNull(combat.captureMelee(proxy, FIRST));
        combat.tick(3); // Revalidate even while the health request is awaiting owner authorization.
        assertFalse(world.targets.containsKey(FIRST));
        assertTrue(world.removed.contains(proxy));
        owner.next();
        owner.next();
        assertEquals(75, current().state().tower().hp());
    }

    @Test void alreadyAcceptedDamageSettlesOnceAfterUnrelatedTargetReplacement() throws Exception {
        start(Mode.RETALIATION, 1_000);
        settledCooling();
        UUID proxy = world.proxy();
        target(proxy, FIRST);
        var accepted = combat.captureMelee(proxy, FIRST);
        var notYetAccepted = combat.captureMelee(proxy, FIRST);
        assertTrue(combat.melee(accepted, 3.5));
        assertFalse(combat.melee(accepted, 3.5));
        combat.targetChanged(FIRST, null);
        world.targets.put(FIRST, EXTERNAL);
        assertFalse(combat.melee(notYetAccepted, 20));
        assertNull(combat.captureMelee(proxy, FIRST));
        assertNull(combat.idle(current()));
        combat.tick(2);
        owner.next();
        owner.next();
        assertEquals(71.5, current().state().tower().hp());
        assertEquals(EXTERNAL, world.targets.get(FIRST));
        assertTrue(world.removed.contains(proxy));
    }

    @Test void detachedDamageCannotCrossProviderDenial() throws Exception {
        start(Mode.RETALIATION, 1_000);
        settledCooling();
        UUID proxy = world.proxy();
        target(proxy, FIRST);
        assertTrue(combat.melee(combat.captureMelee(proxy, FIRST), 3.5));
        combat.targetChanged(FIRST, null);
        world.targets.put(FIRST, EXTERNAL);
        combat.tick(2);
        owner.waitForWork();
        world.denied.add(Action.MELEE);
        combat.tick(3);
        owner.next();
        owner.next();
        assertEquals(75, current().state().tower().hp());
        assertEquals(EXTERNAL, world.targets.get(FIRST));
        assertTrue(world.removed.contains(proxy));
    }

    @Test void exactZombieRemovalRevokesDetachedMeleeAfterOwnerAuthorization() throws Exception {
        start(Mode.RETALIATION, 1_000);
        settledCooling();
        UUID proxy = world.proxy();
        target(proxy, FIRST);
        assertTrue(combat.melee(combat.captureMelee(proxy, FIRST), 3.5));
        assertTrue(combat.melee(combat.captureMelee(proxy, FIRST), 2));
        combat.targetChanged(FIRST, null);
        world.targets.put(FIRST, EXTERNAL);
        var adapter = new PaperZombieWorld(new PaperZombieWorldTest.DormantAccess());
        adapter.bind(combat);

        combat.tick(2);
        owner.waitForWork(); // The authoritative row read finished before owner damage authorization.
        try (var writer = SqliteTestConnections.open(directory.resolve("combat.db"))) {
            writer.setAutoCommit(false);
            owner.next(); // Authorize detached melee and queue the SQL write behind this writer.
            adapter.removed(zombieRemoval(SECOND));
            assertFalse(world.removed.contains(proxy));
            adapter.removed(zombieRemoval(FIRST));
            assertTrue(world.removed.contains(proxy));
            writer.rollback();
        }
        owner.next();
        assertEquals(75, current().state().tower().hp());
        assertEquals(EXTERNAL, world.targets.get(FIRST));
        assertNotNull(combat.idle(current()));
    }

    private static EntityRemoveEvent zombieRemoval(UUID id) {
        var zombie = PaperZombieWorldTest.fake(Zombie.class, (name, args) -> {
            assertEquals("getUniqueId", name);
            return id;
        });
        return new EntityRemoveEvent(zombie, EntityRemoveEvent.Cause.UNLOAD);
    }

    @Test void acquiredLeaseRevokesIdlePermitAndQueuedMeleeCannotCrossUnload() throws Exception {
        combat.track(tower, settings, new Settings(Mode.RETALIATION, 6, 2, 1_000));
        var idle = combat.idle(tower);
        assertNotNull(idle);
        acquire();
        settledCooling();
        assertThrows(RepositoryException.CommitRevoked.class, idle::requireValid);
        UUID proxy = world.proxy();
        target(proxy, FIRST);
        assertTrue(combat.melee(combat.captureMelee(proxy, FIRST), 20));
        combat.tick(2);
        owner.waitForWork();
        combat.proxyGone(proxy);
        owner.next();
        owner.next();
        assertEquals(75, current().state().tower().hp());
        assertFalse(world.targets.containsKey(FIRST));
        assertTrue(world.removed.contains(proxy));
    }

    @Test void currentCatalogAndExclusiveLeaseFenceCompetingTowers() throws Exception {
        start(Mode.RETALIATION, 1_000);
        settledCooling();
        var secondTower = placed(8);
        combat.track(secondTower, settings, new Settings(Mode.RETALIATION, 6, 2, 1_000));
        combat.tick(2);
        combat.tick(3);
        owner.next();
        combat.tick(4);
        combat.tick(5);
        assertEquals(List.of(FIRST), world.attacks);
        UUID original = world.proxies.getFirst();
        target(original, FIRST);
        var proof = combat.captureMelee(original, FIRST);
        assertNotNull(proof);
        settings = get(catalog.reload(config(false))).snapshot();
        combat.tick(6);
        combat.tick(7);
        assertFalse(combat.melee(proof, 2));
        assertTrue(world.removed.contains(original));
    }

    @Test void realPaperEventsCancelBeforeSettlementAndDebitPreShieldDamageOnlyOnce() throws Exception {
        UUID proxyId = UUID.randomUUID();
        var nativeProxy = PaperZombieWorldTest.fake(Villager.class, (name, args) -> switch (name) {
            case "getUniqueId" -> proxyId;
            case "getHealth" -> 20.0;
            case "getScoreboardTags" -> Set.of(PaperZombieWorld.TAG);
            case "addScoreboardTag" -> true;
            default -> {
                assertTrue(name.startsWith("set"), name);
                yield null;
            }
        });
        var nativeZombie = PaperZombieWorldTest.fake(Zombie.class, (name, args) -> switch (name) {
            case "getUniqueId" -> FIRST;
            case "getType" -> EntityType.ZOMBIE;
            case "isBaby" -> false;
            case "getScoreboardTags" -> Set.of();
            default -> throw new AssertionError(name);
        });
        World nativeWorld = PaperZombieWorldTest.fake(World.class, (name, args) -> switch (name) {
            case "isChunkLoaded" -> true;
            case "spawn" -> {
                @SuppressWarnings("unchecked")
                var initial = (Consumer<Villager>) args[2];
                initial.accept(nativeProxy);
                yield nativeProxy;
            }
            default -> throw new AssertionError(name);
        });
        var adapter = new PaperZombieWorld(new PaperZombieWorld.Access() {
            @Override public Conditions inspect(TowerSnapshot row) { throw new AssertionError(); }
            @Override public boolean allows(Action action, TowerSnapshot row, Villager proxy, Zombie target) { throw new AssertionError(); }
            @Override public boolean proxyFits(TowerSnapshot row, Location from) { return true; }
            @Override public List<UUID> candidates(TowerSnapshot row, Location from, double range, int limit) { throw new AssertionError(); }
            @Override public TowerHealth.DefeatEffects defeatEffects() { throw new AssertionError(); }
        });
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        var original = serverField.get(null);
        var server = PaperZombieWorldTest.fake(Server.class, (name, args) -> switch (name) {
            case "getWorld" -> nativeWorld;
            case "isPrimaryThread" -> true;
            default -> throw new AssertionError(name);
        });
        serverField.set(null, server);
        try {
            adapter.bind(combat);
            assertEquals(proxyId, adapter.spawnProxy(tower, UUID.randomUUID()));
            world.nextProxy = proxyId;
            start(Mode.RETALIATION, 1_000);
            settledCooling();
            var target = new EntityTargetLivingEntityEvent(nativeZombie, nativeProxy, EntityTargetEvent.TargetReason.TARGET_ATTACKED_ENTITY);
            adapter.target(target);
            assertFalse(target.isCancelled());
            adapter.targetObserved(target);
            world.targets.put(FIRST, proxyId);
            var cancelled = PaperZombieWorldTest.damage(nativeZombie, nativeProxy, nativeZombie, nativeZombie, 3.5);
            adapter.shield(cancelled);
            cancelled.setCancelled(true); // Provider cancelled after the shield.
            adapter.damageObserved(cancelled);
            var allowed = PaperZombieWorldTest.damage(nativeZombie, nativeProxy, nativeZombie, nativeZombie, 2.75);
            adapter.shield(allowed);
            adapter.damageObserved(allowed);
            adapter.shield(allowed);
            adapter.damageObserved(allowed);
            assertEquals(0, allowed.getFinalDamage());
            combat.tick(2);
            owner.next();
            owner.next();
            assertEquals(72.25, current().state().tower().hp());
            combat.tick(3);
            owner.next();
            combat.tick(3);
            assertTrue(owner.work.isEmpty(), "Duplicate event cannot enqueue a second HP debit");
        } finally {
            serverField.set(null, original);
        }
    }

    @Test void failedCoolingPersistenceFencesRepairEvenAfterRetracking() throws Exception {
        world.beforeAttack = () -> {
            Tower original = tower.state().tower();
            var changed = new Tower(original.id(), original.variantId(), 74, original.maximumHp(), original.form(),
                    original.generation(), original.deploymentRevision(), original.controller(), original.position(),
                    original.light(), original.defence(), 0, 0);
            repository.compareAndSetTower(tower.rowVersion(), changed).join();
        };
        start(Mode.NORMAL, 1_000);
        owner.next();
        assertNotNull(world.removed.getFirst());
        combat.untrack(tower);
        TowerSnapshot changed = current();
        combat.track(changed, settings, new Settings(Mode.NORMAL, 6, 2, 1_000));
        assertNull(combat.idle(changed));
        combat.tick(2);
        assertEquals(1, world.attacks.size());
    }

    private void start(Mode mode, long interval) throws Exception {
        combat.track(tower, settings, new Settings(mode, 6, 2, interval));
        acquire();
    }

    private void acquire() throws Exception {
        combat.tick(0);
        owner.next();
        combat.tick(0);
    }

    private void settledCooling() throws Exception {
        owner.next();
        combat.tick(1);
        owner.next();
    }

    private void target(UUID proxy, UUID zombie) {
        assertTrue(combat.permitsTarget(proxy, zombie, TargetReason.RETALIATION, false));
        world.targets.put(zombie, proxy);
        combat.targetChanged(zombie, proxy);
    }

    private TowerSnapshot current() throws Exception {
        return get(repository.tower(tower.state().tower().id())).orElseThrow();
    }

    private TowerSnapshot placed(int x) throws Exception {
        UUID id = UUID.randomUUID();
        UUID operation = UUID.randomUUID();
        get(repository.createTower(new Tower(id, "test", 75, 100, Form.PORTABLE, 1, 0,
                null, null, true, true, 0, 0)));
        Tower deployed = new Tower(id, "test", 75, 100, Form.EXPANDED, 2, 1, ACTOR,
                new Position(WORLD, x, 70, 4, "north"), true, true, 0, 0);
        var component = new Component("base", ComponentKind.BLOCK, WORLD, x, 70, 4,
                "minecraft:air", "minecraft:barrier[waterlogged=false]", null, null);
        var intent = new Intent(operation, id, 0, 1, 0, "synthetic-placement",
                new State(deployed, null, List.of(component)), new Document(1, "Synthetic placement proof"));
        var admitted = get(repository.beginCatalogIntent(intent, settings.revision()));
        var applied = get(repository.advanceOperation(operation, admitted.rowVersion(), Phase.INTENT, Phase.EFFECTS_APPLIED, "Synthetic proof"));
        get(repository.completeOperation(operation, applied.rowVersion(), "Synthetic result"));
        return get(repository.tower(id)).orElseThrow();
    }

    private static Map<String, ?> config(boolean defence) {
        return Map.of("schema", 1, "variants", Map.of("test", Map.of(
                "player-switches", Map.of("light", true, "defence", true),
                "capabilities", Map.of("defence", defence), "light-level", 15, "maximum-hp", 100,
                "expansion-cooldown-ms", 60_000L,
                "geometry", Map.of("compact-model", "test:compact", "expanded-model", "test:expanded",
                        "width", 1, "height", 3, "depth", 1, "light-x", 0, "light-y", 2, "light-z", 0),
                "repair", Map.of("resources", Map.of("minecraft:iron_ingot", 5), "cooling-ms", 400L))));
    }

    private static <T> T get(CompletableFuture<T> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }

    private static final class Owner implements WorldScheduler {
        final Thread thread = Thread.currentThread();
        final BlockingQueue<Runnable> work = new LinkedBlockingQueue<>();
        boolean deferCompletion;
        private Runnable deferred;
        @Override public <T> CompletableFuture<T> supply(Anchor anchor, Supplier<T> action) {
            var result = new CompletableFuture<T>();
            work.add(() -> {
                try {
                    T value = action.get();
                    if (deferCompletion) deferred = () -> result.complete(value);
                    else result.complete(value);
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
            return result;
        }
        void publishDeferred() {
            assertNotNull(deferred);
            deferred.run();
            deferred = null;
        }
        void require() { assertSame(thread, Thread.currentThread()); }
        void next() throws Exception {
            var action = work.poll(10, TimeUnit.SECONDS);
            assertNotNull(action, "Expected queued owner work");
            action.run();
        }
        void waitForWork() throws Exception {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (work.isEmpty()) {
                assertTrue(System.nanoTime() < until, "Expected queued owner work");
                Thread.sleep(1);
            }
        }
    }

    private final class WorldFake implements WorldPort {
        final List<UUID> attacks = new ArrayList<>(), proxies = new ArrayList<>(), removed = new ArrayList<>();
        final Map<UUID, UUID> targets = new HashMap<>();
        final Set<Action> denied = EnumSet.noneOf(Action.class);
        List<Candidate> available = List.of(new Candidate(FIRST, 4));
        Controller controller = Controller.ELIGIBLE;
        boolean acceptAttack = true;
        UUID nextProxy;
        Runnable beforeAttack = () -> {};
        int priorities;

        UUID proxy() { return proxies.getLast(); }
        @Override public Conditions inspect(TowerSnapshot tower) {
            owner.require();
            return new Conditions(new WorldRef(WORLD, "generic", Dimension.OVERWORLD, false), controller, true, true);
        }
        @Override public UUID spawnProxy(TowerSnapshot tower, UUID token) {
            owner.require();
            UUID proxy = nextProxy == null ? UUID.randomUUID() : nextProxy;
            nextProxy = null;
            proxies.add(proxy);
            return proxy;
        }
        @Override public void removeProxy(UUID proxy, UUID token) { owner.require(); removed.add(proxy); }
        @Override public List<Candidate> candidates(TowerSnapshot tower, double range, int limit) {
            owner.require();
            return available;
        }
        @Override public boolean reachable(TowerSnapshot tower, UUID proxy, UUID zombie, double range) {
            owner.require();
            return !removed.contains(proxy);
        }
        @Override public boolean allows(Action action, TowerSnapshot tower, UUID proxy, UUID zombie) {
            owner.require();
            return !denied.contains(action);
        }
        @Override public boolean hasOwnedTarget(UUID zombie, UUID proxy) { return proxy.equals(targets.get(zombie)); }
        @Override public boolean hasOtherTarget(UUID zombie, UUID proxy) {
            return targets.containsKey(zombie) && !hasOwnedTarget(zombie, proxy);
        }
        @Override public void prioritize(UUID zombie, UUID proxy) {
            priorities++;
            if (combat.permitsTarget(proxy, zombie, TargetReason.PRIORITY, false)) {
                targets.put(zombie, proxy);
                combat.targetChanged(zombie, proxy);
            }
        }
        @Override public void releaseTarget(UUID zombie, UUID proxy) { targets.remove(zombie, proxy); }
        @Override public boolean attack(UUID proxy, UUID zombie, double damage) {
            attacks.add(zombie);
            beforeAttack.run();
            return acceptAttack;
        }
        @Override public TowerHealth.DefeatEffects defeatEffects() { return row -> fail("No lethal damage expected"); }
    }
}
