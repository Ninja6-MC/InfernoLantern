package com.ninja6.infernolantern.runtime;

import com.ninja6.infernolantern.repository.RepositoryRecords.Cell;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class PaperHeldReservationGuardTest {
    private final UUID world = new UUID(0, 42);
    private final Cell reserved = new Cell(world, 4, 70, 4);
    private final Cell neighbor = new Cell(world, 5, 70, 4);
    private final Player player = proxy(Player.class, name -> { throw new AssertionError(name); });

    @Test void coverageStartsOnlyOnPublicationAndUsesWorldIdentity() {
        var guard = new PaperHeldReservationGuard();
        try {
            var before = new BlockBreakEvent(block(reserved), player);
            guard.breakBlock(before);
            assertFalse(before.isCancelled());

            assertTrue(guard.publish(Set.of(reserved)));
            var protectedBlock = new BlockBreakEvent(block(reserved), player);
            guard.breakBlock(protectedBlock);
            assertTrue(protectedBlock.isCancelled());
            for (Cell other : List.of(neighbor, new Cell(new UUID(0, 43), 4, 70, 4))) {
                var allowed = new BlockBreakEvent(block(other), player);
                guard.breakBlock(allowed);
                assertFalse(allowed.isCancelled());
            }
        } finally {
            guard.close();
        }
        assertFalse(guard.publish(Set.of(reserved)));
        var after = new BlockBreakEvent(block(reserved), player);
        guard.breakBlock(after);
        assertFalse(after.isCancelled());
    }

    @Test void placementChecksEveryChangedCoordinate() {
        var guard = new PaperHeldReservationGuard();
        try {
            guard.publish(Set.of(reserved));
            var single = new BlockPlaceEvent(block(reserved), state(reserved), block(neighbor),
                    null, player, true, EquipmentSlot.HAND);
            guard.place(single);
            assertTrue(single.isCancelled());
            var multiple = new BlockMultiPlaceEvent(List.of(state(neighbor), state(reserved)),
                    block(neighbor), null, player, true, EquipmentSlot.HAND);
            guard.place(multiple);
            assertTrue(multiple.isCancelled());
            var unrelated = new BlockMultiPlaceEvent(List.of(state(neighbor)), block(neighbor), null, player,
                    true, EquipmentSlot.HAND);
            guard.place(unrelated);
            assertFalse(unrelated.isCancelled());
        } finally {
            guard.close();
        }
    }

    @Test void bucketChecksActualTargetInsteadOfClickedBlock() {
        var guard = new PaperHeldReservationGuard();
        try {
            guard.publish(Set.of(reserved));
            var empty = new PlayerBucketEmptyEvent(player, block(reserved), block(neighbor),
                    BlockFace.WEST, Material.WATER_BUCKET, null, EquipmentSlot.HAND);
            guard.empty(empty);
            assertTrue(empty.isCancelled());
            var fill = new PlayerBucketFillEvent(player, block(reserved), block(neighbor),
                    BlockFace.WEST, Material.BUCKET, null, EquipmentSlot.HAND);
            guard.fill(fill);
            assertTrue(fill.isCancelled());

            var other = new PlayerBucketEmptyEvent(player, block(neighbor), block(reserved),
                    BlockFace.EAST, Material.WATER_BUCKET, null, EquipmentSlot.HAND);
            guard.empty(other);
            assertFalse(other.isCancelled());
        } finally {
            guard.close();
        }
    }

    private static Block block(Cell cell) {
        return proxy(Block.class, name -> coordinates(cell, name));
    }

    private static BlockState state(Cell cell) {
        return proxy(BlockState.class, name -> name.equals("getBlock") ? block(cell) : coordinates(cell, name));
    }

    private static Object coordinates(Cell cell, String name) {
        return switch (name) {
            case "getWorld" -> proxy(World.class, method -> {
                if (method.equals("getUID")) return cell.world();
                throw new AssertionError(method);
            });
            case "getX" -> cell.x();
            case "getY" -> cell.y();
            case "getZ" -> cell.z();
            default -> throw new AssertionError(name);
        };
    }

    private static <T> T proxy(Class<T> type, Function<String, Object> method) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (object, call, args) -> method.apply(call.getName())));
    }
}
