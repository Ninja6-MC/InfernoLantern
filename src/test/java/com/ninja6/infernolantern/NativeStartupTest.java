package com.ninja6.infernolantern;

import com.ninja6.infernolantern.item.PaperItemFreeze;
import com.ninja6.infernolantern.item.PaperItemIdentity;
import com.ninja6.infernolantern.prototype.PrototypeBucketGuard;
import com.ninja6.infernolantern.prototype.PrototypeCellGuard;
import com.ninja6.infernolantern.prototype.PrototypeLedger;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.Event.Result;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import io.papermc.paper.persistence.PersistentDataContainerView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NativeStartupTest {
    @Test void nativeStartupKeepsMarkedTokensFrozenAndNeverStartsPrototypes() {
        assertTrue(InfernoLanternPlugin.startRuntime(true, freeze -> {
            var marked = interact(true);
            freeze.use(marked);
            assertEquals(Result.DENY, marked.useItemInHand());

            var unrelated = interact(false);
            freeze.use(unrelated);
            assertEquals(Result.DEFAULT, unrelated.useItemInHand());
        }, () -> fail("Native mode may not start prototypes, even when native startup stays unavailable")));
    }

    @Test void disabledNativeModeStartsOnlyExistingPrototypePath() {
        assertTrue(InfernoLanternPlugin.startRuntime(false, freeze -> fail("Native mode was disabled"), () -> true));
        assertFalse(InfernoLanternPlugin.startRuntime(false, freeze -> fail("Native mode was disabled"), () -> false));
    }

    @Test void nativeStartupProtectsCommittedCellsAcrossChunkLoadWithoutRecovery(@TempDir Path data) throws Exception {
        UUID world = UUID.randomUUID();
        var cell = new PrototypeLedger.Cell(world, 17, 64, -3);
        Path manifest = data.resolve("prototype-cleanup.bin");
        var ledger = new PrototypeLedger(manifest, new PrototypeLedger.Blocks() {
            @Override public boolean loaded(PrototypeLedger.Cell at) { return true; }
            @Override public String read(PrototypeLedger.Cell at) { return "minecraft:air"; }
            @Override public void write(PrototypeLedger.Cell at, String state) {}
        });
        ledger.apply(Map.of(cell, "minecraft:barrier"));
        byte[] before = Files.readAllBytes(manifest);

        var listeners = new ArrayList<Listener>();
        assertTrue(InfernoLanternPlugin.startRuntime(true, freeze -> {
            var cleanup = InfernoLanternPlugin.installNativePreservation(data, freeze, listeners::add);
            assertFalse(cleanup.complete());
            assertTrue(cleanup.cellsKnown());
            assertEquals(Set.of(cell), cleanup.cells());
        }, () -> fail("Native startup cannot trigger prototype recovery")));
        assertEquals(3, listeners.size());
        var guard = assertInstanceOf(PrototypeCellGuard.class, listeners.get(1));
        var bucket = assertInstanceOf(PrototypeBucketGuard.class, listeners.get(2));
        // The listener has no chunk recovery handler; the exact guard stays installed for
        // block instances recreated when the same chunk is loaded again.
        assertTrue(listeners.stream().flatMap(listener -> java.util.Arrays.stream(listener.getClass().getMethods()))
                .noneMatch(method -> java.util.Arrays.asList(method.getParameterTypes()).contains(ChunkLoadEvent.class)));
        for (int load = 0; load < 2; load++) {
            Block recorded = block(world, 17);
            var breaking = new BlockBreakEvent(recorded, null);
            guard.breakBlock(breaking);
            assertTrue(breaking.isCancelled());

            var water = new PlayerBucketEmptyEvent(null, recorded, block(world, 16), BlockFace.EAST,
                    Material.WATER_BUCKET, null, EquipmentSlot.HAND);
            bucket.empty(water);
            assertTrue(water.isCancelled());
        }
        var adjacent = new BlockBreakEvent(block(world, 16), null);
        guard.breakBlock(adjacent);
        assertFalse(adjacent.isCancelled());
        var differentWorld = new BlockBreakEvent(block(UUID.randomUUID(), 17), null);
        guard.breakBlock(differentWorld);
        assertFalse(differentWorld.isCancelled());
        assertArrayEquals(before, Files.readAllBytes(manifest));
    }

    @Test void malformedManifestLeavesOnlyItemGuardAndReportsUnknownCells(@TempDir Path data) throws Exception {
        Path manifest = Files.write(data.resolve("prototype-cleanup.bin"), new byte[]{1, 2, 3});
        var listeners = new ArrayList<Listener>();
        var cleanup = InfernoLanternPlugin.installNativePreservation(data, new PaperItemFreeze(), listeners::add);
        assertFalse(cleanup.complete());
        assertFalse(cleanup.cellsKnown());
        assertTrue(cleanup.cells().isEmpty());
        assertEquals(1, listeners.size());
        assertInstanceOf(PaperItemFreeze.class, listeners.getFirst());
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(manifest));
    }

    @Test void onlyAValidHttpsDownloadUrlCanConfigureJavaDelivery() {
        URI permitted = URI.create("https://assets.example.invalid/infernolantern-generic-java.zip");
        assertEquals(permitted, InfernoLanternPlugin.parseJavaPackUrl(permitted.toString()));
        for (Object invalid : new Object[]{null, 42, "", "http://example.invalid/pack.zip",
                "https:pack.zip", "https://username@example.invalid/pack.zip",
                "https://example.invalid/pack.zip#extra", "https://example.invalid:99999/pack.zip"}) {
            assertThrows(IllegalArgumentException.class, () -> InfernoLanternPlugin.parseJavaPackUrl(invalid));
        }
    }

    @Test void bundledJavaDeliveryConfigDefaultsAndRejectsIncompleteOrInvalidChoices() {
        String base = "https://assets.example.invalid/base.zip";
        String overlay = "https://assets.example.invalid/override.zip";
        var defaults = InfernoLanternPlugin.parseJavaPackConfig(base, null, null, null, null, null);
        assertEquals(URI.create(base), defaults.url());
        assertNull(defaults.overrideUrl());
        assertFalse(defaults.required());
        assertFalse(defaults.localOverride());
        assertTrue(InfernoLanternPlugin.parseJavaPackConfig(base, "default", "", true, null, null).required());

        var stacked = InfernoLanternPlugin.parseJavaPackConfig(base, "stacked", overlay, true, null, null);
        assertEquals(URI.create(overlay), stacked.overrideUrl());
        assertTrue(stacked.required());

        for (Object mode : new Object[]{"combined", "stakced", "", false}) {
            assertThrows(IllegalArgumentException.class, () -> InfernoLanternPlugin.parseJavaPackConfig(base, mode, null, null, null, null));
        }
        for (Object override : new Object[]{null, "", "http://assets.example.invalid/override.zip", base, true}) {
            assertThrows(IllegalArgumentException.class, () -> InfernoLanternPlugin.parseJavaPackConfig(base, "stacked", override, true, null, null));
        }
        assertThrows(IllegalArgumentException.class, () -> InfernoLanternPlugin.parseJavaPackConfig(base, "default", overlay, false, null, null));
        assertThrows(IllegalArgumentException.class, () -> InfernoLanternPlugin.parseJavaPackConfig(base, "stacked", overlay, "true", null, null));
        assertTrue(InfernoLanternPlugin.parseJavaPackConfig(base, "stacked", overlay, false, true, null).localOverride());
        assertThrows(IllegalArgumentException.class, () -> InfernoLanternPlugin.parseJavaPackConfig(base, "default", null, false, true, null));
        assertThrows(IllegalArgumentException.class, () -> InfernoLanternPlugin.parseJavaPackConfig(base, "stacked", overlay, false, "true", null));
    }

    @Test void combinedModeRequiresOnlyTheExactProviderName() {
        var selected = InfernoLanternPlugin.parseJavaPackConfig("", "combined", "", false, false, "PackOwner-1");
        assertEquals("PackOwner-1", selected.combinedProvider());
        assertNull(selected.url());
        assertNull(selected.overrideUrl());
        assertFalse(selected.required());
        assertFalse(selected.localOverride());

        for (Object provider : new Object[]{null, "", false, " Owner", "a/b"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> InfernoLanternPlugin.parseJavaPackConfig("", "combined", "", false, false, provider));
        }
        String url = "https://assets.example.invalid/pack.zip";
        assertThrows(IllegalArgumentException.class,
                () -> InfernoLanternPlugin.parseJavaPackConfig(url, "combined", "", false, false, "PackOwner"));
        assertThrows(IllegalArgumentException.class,
                () -> InfernoLanternPlugin.parseJavaPackConfig("", "combined", url, false, false, "PackOwner"));
        assertThrows(IllegalArgumentException.class,
                () -> InfernoLanternPlugin.parseJavaPackConfig("", "combined", "", true, false, "PackOwner"));
        assertThrows(IllegalArgumentException.class,
                () -> InfernoLanternPlugin.parseJavaPackConfig("", "combined", "", false, true, "PackOwner"));
        assertThrows(IllegalArgumentException.class,
                () -> InfernoLanternPlugin.parseJavaPackConfig(url, "default", "", false, false, "PackOwner"));
    }

    private static Block block(UUID world, int x) {
        World owner = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getUID")) return world;
                    throw new AssertionError("Unexpected world access: " + method.getName());
                });
        return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[]{Block.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getWorld" -> owner;
                    case "getX" -> x;
                    case "getY" -> 64;
                    case "getZ" -> -3;
                    default -> throw new AssertionError("Unexpected block access: " + method.getName());
                });
    }

    private static PlayerInteractEvent interact(boolean marked) {
        ItemStack item = new ItemStack() {
            @Override public Material getType() { return Material.PAPER; }
            @Override public PersistentDataContainerView getPersistentDataContainer() {
                return (PersistentDataContainerView) Proxy.newProxyInstance(
                        PersistentDataContainerView.class.getClassLoader(),
                        new Class<?>[]{PersistentDataContainerView.class},
                        (proxy, method, args) -> marked && PaperItemIdentity.DELIVERY.equals(args[0]));
            }
        };
        return new PlayerInteractEvent(null, Action.RIGHT_CLICK_AIR, item, null, null, EquipmentSlot.HAND);
    }
}
