package com.ninja6.infernolantern.structure;

import com.ninja6.infernolantern.repository.RepositoryRecords.Cell;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.PistonMoveReaction;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static com.ninja6.infernolantern.structure.PaperStructureEffectsTest.proxy;
import static org.junit.jupiter.api.Assertions.*;

class PaperPistonGuardTest {
    private final UUID world = UUID.randomUUID();

    @Test void respectsExactSourceDestinationHeadAndWorldReservations() throws Exception {
        var field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        Object previous = field.get(null);
        field.set(null, proxy(Server.class, (method, args) -> {
            assertEquals("isPrimaryThread", method.getName());
            return true;
        }));
        try {
            var reservations = new HashSet<Cell>();
            var guard = new PaperPistonGuard(reservations::contains);
            Block piston = block(world, 2, PistonMoveReaction.MOVE);
            Block source = block(world, 4, PistonMoveReaction.MOVE);

            reservations.add(new Cell(world,5,70,4));
            var push = new BlockPistonExtendEvent(piston,List.of(source),BlockFace.EAST);
            guard.extend(push);
            assertTrue(push.isCancelled());

            var breakOnly = new BlockPistonExtendEvent(piston,List.of(block(world,4,PistonMoveReaction.BREAK)),BlockFace.EAST);
            guard.extend(breakOnly);
            assertFalse(breakOnly.isCancelled());
            reservations.clear();

            reservations.add(new Cell(world,4,70,4));
            var sourceMove = new BlockPistonExtendEvent(piston,List.of(source),BlockFace.EAST);
            guard.extend(sourceMove);
            assertTrue(sourceMove.isCancelled());
            reservations.clear();

            reservations.add(new Cell(world,5,70,4)); // Also models a pending reservation.
            var pull = new BlockPistonRetractEvent(piston,List.of(block(world,6,PistonMoveReaction.MOVE)),BlockFace.WEST);
            guard.retract(pull);
            assertTrue(pull.isCancelled());
            reservations.clear();

            reservations.add(new Cell(world,3,70,4));
            var emptyPull = new BlockPistonRetractEvent(piston,List.of(),BlockFace.EAST);
            guard.retract(emptyPull);
            assertTrue(emptyPull.isCancelled());
            var emptyPush = new BlockPistonExtendEvent(piston,List.of(),BlockFace.EAST);
            guard.extend(emptyPush);
            assertTrue(emptyPush.isCancelled());

            var other = new BlockPistonExtendEvent(block(UUID.randomUUID(),2,PistonMoveReaction.MOVE),List.of(),BlockFace.EAST);
            guard.extend(other);
            assertFalse(other.isCancelled());
            other.setCancelled(true);
            guard.extend(other);
            assertTrue(other.isCancelled());
        } finally {
            field.set(null, previous);
        }
    }

    private static Block block(UUID id, int x, PistonMoveReaction reaction) {
        World world = proxy(World.class, (method, args) -> {
            assertEquals("getUID", method.getName());
            return id;
        });
        return proxy(Block.class, (method, args) -> switch (method.getName()) {
            case "getWorld" -> world;
            case "getX" -> x;
            case "getY" -> 70;
            case "getZ" -> 4;
            case "getPistonMoveReaction" -> reaction;
            default -> throw new AssertionError(method);
        });
    }
}
