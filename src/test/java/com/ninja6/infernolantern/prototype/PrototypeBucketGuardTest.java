package com.ninja6.infernolantern.prototype;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;

class PrototypeBucketGuardTest {
    private static <T> T stub(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, arguments) -> {
            if (method.getName().equals("equals")) return proxy == arguments[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            return null;
        }));
    }

    private PlayerBucketEmptyEvent event(Block destination, Block clicked, BlockFace face, EquipmentSlot hand, Material bucket) {
        return new PlayerBucketEmptyEvent(stub(Player.class), destination, clicked, face, bucket, null, hand);
    }

    @Test void adjacentFaceCannotWaterlogRecordedLightFromEitherHand() {
        Block light = stub(Block.class), adjacentSupport = stub(Block.class);
        var guard = new PrototypeBucketGuard(block -> block == light);
        for (EquipmentSlot hand : new EquipmentSlot[]{EquipmentSlot.HAND, EquipmentSlot.OFF_HAND}) {
            var event = event(light, adjacentSupport, BlockFace.EAST, hand, Material.WATER_BUCKET);
            guard.empty(event);
            assertTrue(event.isCancelled(), "The destination is owned even though the clicked adjacent block is not");
        }
    }

    @Test void usesActualDestinationForDirectWaterloggingInsteadOfOffsettingTheFace() {
        Block light = stub(Block.class);
        var event = event(light, light, BlockFace.UP, EquipmentSlot.HAND, Material.WATER_BUCKET);
        new PrototypeBucketGuard(block -> block == light).empty(event);
        assertTrue(event.isCancelled());
    }

    @Test void lavaPlacementIntoRecordedCellIsAlsoCancelled() {
        Block target = stub(Block.class);
        var event = event(target, stub(Block.class), BlockFace.NORTH, EquipmentSlot.OFF_HAND, Material.LAVA_BUCKET);
        new PrototypeBucketGuard(block -> block == target).empty(event);
        assertTrue(event.isCancelled());
    }

    @Test void unrelatedBucketUseIsNotCancelled() {
        Block fixture = stub(Block.class);
        var event = event(stub(Block.class), stub(Block.class), BlockFace.UP, EquipmentSlot.HAND, Material.WATER_BUCKET);
        new PrototypeBucketGuard(block -> block == fixture).empty(event);
        assertFalse(event.isCancelled());
    }

    @Test void preservesCancellationFromAnotherListener() {
        var event = event(stub(Block.class), stub(Block.class), BlockFace.UP, EquipmentSlot.HAND, Material.WATER_BUCKET);
        event.setCancelled(true);
        new PrototypeBucketGuard(block -> false).empty(event);
        assertTrue(event.isCancelled());
    }
}
