package com.ninja6.infernolantern.combat;

import com.ninja6.infernolantern.catalog.CatalogConfig.Dimension;
import com.ninja6.infernolantern.catalog.CatalogConfig.HeadFluid;
import com.ninja6.infernolantern.catalog.CatalogConfig.WorldRef;
import com.ninja6.infernolantern.health.TowerHealth;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTargetEvent.TargetReason;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.ninja6.infernolantern.combat.ZombieCombat.*;
import static com.ninja6.infernolantern.repository.RepositoryRecords.*;
import static org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK;
import static org.junit.jupiter.api.Assertions.*;

class PaperZombieWorldTest {
    @Test void outgoingRequiresExactNativeProxyWithoutPlayerAttributionAndPositiveFinalDamage() {
        Villager proxy = entity(Villager.class, EntityType.VILLAGER, false);
        Zombie zombie = entity(Zombie.class, EntityType.ZOMBIE, false);
        Player player = entity(Player.class, EntityType.PLAYER, false);
        var attack = damage(proxy, zombie, proxy, proxy, 2.5);
        assertTrue(PaperZombieWorld.matchesBeam(attack, proxy, zombie));
        assertTrue(PaperZombieWorld.positiveUncancelled(attack));
        attack.setCancelled(true);
        assertFalse(PaperZombieWorld.positiveUncancelled(attack));
        assertFalse(PaperZombieWorld.positiveUncancelled(damage(proxy, zombie, proxy, proxy, 0)));
        assertFalse(PaperZombieWorld.matchesBeam(damage(proxy, zombie, player, proxy, 2.5), proxy, zombie));
        assertFalse(PaperZombieWorld.matchesBeam(damage(player, zombie, proxy, proxy, 2.5), proxy, zombie));
    }

    @Test void managedProxyShieldPreservesCancellationAndUnboundTargetFailsClosed() {
        var world = new PaperZombieWorld(new DormantAccess());
        Villager proxy = entity(Villager.class, EntityType.VILLAGER, false);
        Zombie zombie = entity(Zombie.class, EntityType.ZOMBIE, false);
        var melee = damage(zombie, proxy, zombie, zombie, 3.5);
        melee.setCancelled(true);
        world.shield(melee);
        world.damageObserved(melee);
        assertTrue(melee.isCancelled());
        assertEquals(0, melee.getFinalDamage());
        var target = new EntityTargetLivingEntityEvent(zombie, proxy, TargetReason.TARGET_ATTACKED_ENTITY);
        world.target(target);
        assertTrue(target.isCancelled());
        var external = new EntityTargetLivingEntityEvent(zombie, zombie, TargetReason.CUSTOM);
        world.target(external);
        assertFalse(external.isCancelled());
        assertTrue(PaperZombieWorld.ordinary(zombie));
        assertFalse(PaperZombieWorld.ordinary(entity(Zombie.class, EntityType.HUSK, false)));
        assertFalse(PaperZombieWorld.ordinary(entity(Zombie.class, EntityType.ZOMBIE, true)));
    }

    @Test void unloadedOrOverBudgetLineOfSightCorridorIsRejectedWithoutLoading() {
        World world = fake(World.class, (name, args) -> {
            assertEquals("isChunkLoaded", name);
            return (int) args[0] != 1;
        });
        Location origin = new Location(world, 0, 71, 0);
        assertTrue(PaperZombieWorld.loadedCorridor(origin, new Location(world, 12, 71, 0)));
        assertFalse(PaperZombieWorld.loadedCorridor(origin, new Location(world, 32, 71, 0)));
        assertFalse(PaperZombieWorld.loadedCorridor(origin, new Location(world, 1_000, 71, 1_000)));
    }

    static EntityDamageByEntityEvent damage(Entity damager, Entity victim, Entity causing, Entity direct, double amount) {
        DamageSource source = fake(DamageSource.class, (name, args) -> switch (name) {
            case "getCausingEntity" -> causing;
            case "getDirectEntity" -> direct;
            default -> throw new AssertionError(name);
        });
        return new EntityDamageByEntityEvent(damager, victim, ENTITY_ATTACK, source, amount);
    }

    @Test void observesOnlyTheCurrentBoundAttackHeadAndNeverReadsAnUnavailableCell() throws Exception {
        var worldId = UUID.randomUUID();
        var towerId = UUID.randomUUID();
        var tower = new Tower(towerId, "test", 75, 100, Form.EXPANDED, 2, 9, UUID.randomUUID(),
                new Position(worldId, 2, 70, 2, "north"), true, true, 0, 0);
        var base = new Component("base", ComponentKind.BLOCK, worldId, 2, 70, 2,
                "minecraft:water[level=0]", "minecraft:barrier[waterlogged=true]", null, null);
        var head = new Component("head", ComponentKind.BLOCK, worldId, 2, 72, 2,
                "minecraft:air", "minecraft:air", null, null);
        var row = new TowerSnapshot(new State(tower, null, List.of(base, head)), 6, null);
        var proof = new AtomicReference<>(new PaperZombieWorld.AttackHead(towerId, 2, 9, head));
        var loaded = new AtomicBoolean(true);
        var data = new AtomicReference<BlockData>(fake(BlockData.class, (n, a) -> Material.AIR));
        var world = fake(World.class, (name, args) -> switch (name) {
            case "isChunkLoaded" -> loaded.get();
            case "getMinHeight" -> -64;
            case "getMaxHeight" -> 320;
            case "getBlockAt" -> {
                assertArrayEquals(new Object[]{2,72,2}, args, "Only the exact head is observed");
                yield fake(Block.class, (n, a) -> data.get());
            }
            default -> throw new AssertionError(name);
        });
        var adapter = new PaperZombieWorld(new DormantAccess(){
            @Override public PaperZombieWorld.Inspection inspect(TowerSnapshot current) {
                assertSame(row, current);
                return new PaperZombieWorld.Inspection(new WorldRef(worldId, "generic", Dimension.OVERWORLD, false),
                        Controller.ELIGIBLE, true, proof.get());
            }
        });
        var field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        var previous = field.get(null);
        field.set(null, fake(Server.class, (name, args) -> switch (name) {
            case "getWorld" -> world;
            case "isPrimaryThread" -> true;
            default -> throw new AssertionError(name);
        }));
        try {
            assertEquals(HeadFluid.DRY, adapter.inspect(row).fluid());
            data.set(fake(Levelled.class, (name, args) -> switch (name) {
                case "getMaterial" -> Material.WATER;
                case "getLevel" -> 6;
                default -> throw new AssertionError(name);
            }));
            assertEquals(HeadFluid.WATER, adapter.inspect(row).fluid());
            data.set(fake(Waterlogged.class, (name, args) -> switch (name) {
                case "getMaterial" -> Material.BARRIER;
                case "isWaterlogged" -> true;
                default -> throw new AssertionError(name);
            }));
            assertEquals(HeadFluid.WATER, adapter.inspect(row).fluid());
            data.set(fake(Levelled.class, (name, args) -> Material.LAVA));
            assertEquals(HeadFluid.LAVA, adapter.inspect(row).fluid());
            data.set(fake(BlockData.class, (name, args) -> Material.BUBBLE_COLUMN));
            assertEquals(HeadFluid.UNKNOWN, adapter.inspect(row).fluid());
            data.set(fake(BlockData.class, (name, args) -> fail("Stale proofs cannot read head terrain")));
            proof.set(new PaperZombieWorld.AttackHead(towerId, 2, 8, head));
            assertEquals(HeadFluid.UNKNOWN, adapter.inspect(row).fluid());
            loaded.set(false);
            assertNull(adapter.inspect(row));
        } finally {
            field.set(null, previous);
        }
    }

    private static <T extends Entity> T entity(Class<T> entity, EntityType type, boolean baby) {
        UUID id = UUID.randomUUID();
        return fake(entity, (name, args) -> switch (name) {
            case "getUniqueId" -> id;
            case "getType" -> type;
            case "isBaby" -> baby;
            case "getScoreboardTags" -> type == EntityType.VILLAGER ? Set.of(PaperZombieWorld.TAG) : Set.of();
            default -> throw new AssertionError(name);
        });
    }

    static <T> T fake(Class<T> type, BiFunction<String, Object[], Object> handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (object, method, args) -> handler.apply(method.getName(), args)));
    }

    static class DormantAccess implements PaperZombieWorld.Access {
        @Override public PaperZombieWorld.Inspection inspect(TowerSnapshot tower) { throw new AssertionError(); }
        @Override public boolean allows(Action action, TowerSnapshot tower, Villager proxy, Zombie target) { throw new AssertionError(); }
        @Override public boolean proxyFits(TowerSnapshot tower, Location origin) { throw new AssertionError(); }
        @Override public List<UUID> candidates(TowerSnapshot tower, Location origin, double range, int limit) { throw new AssertionError(); }
        @Override public TowerHealth.DefeatEffects defeatEffects() { throw new AssertionError(); }
    }
}
