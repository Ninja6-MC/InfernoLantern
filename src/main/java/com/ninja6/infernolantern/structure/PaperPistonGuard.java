package com.ninja6.infernolantern.structure;

import com.ninja6.infernolantern.repository.RepositoryRecords.Cell;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.PistonMoveReaction;
import org.bukkit.block.data.type.Piston;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/** Owner-side guard for exact committed and active-operation reservation cells. */
public final class PaperPistonGuard implements Listener {
    private static final List<BlockFace> NEIGHBORS = List.of(BlockFace.NORTH, BlockFace.SOUTH,
            BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN);

    private final Predicate<Cell> reserved;

    /**
     * The caller publishes its current loaded footprint, including pending before/after reservations,
     * before enabling native structure access. Lookup stays on the owner and never waits on SQLite.
     */
    public PaperPistonGuard(Predicate<Cell> reserved) {
        this.reserved = Objects.requireNonNull(reserved);
    }

    /** Exclude pre-existing native piston geometry before occupying a new footprint. */
    static boolean canReserve(World world, Cell cell) {
        requireOwner();
        if (!world.isChunkLoaded(cell.x() >> 4, cell.z() >> 4)
                || pistonPart(world.getBlockAt(cell.x(), cell.y(), cell.z()).getBlockData().getMaterial())) {
            return false;
        }
        for (BlockFace face : NEIGHBORS) {
            Cell neighbor = offset(cell, face);
            if (neighbor.y() < world.getMinHeight() || neighbor.y() >= world.getMaxHeight()) continue;
            if (!world.isChunkLoaded(neighbor.x() >> 4, neighbor.z() >> 4)) return false;
            var adjacent = world.getBlockAt(neighbor.x(), neighbor.y(), neighbor.z()).getBlockData();
            Material material=adjacent.getMaterial();
            if (material == Material.MOVING_PISTON) return false;
            if (material == Material.PISTON || material == Material.STICKY_PISTON) {
                if (!(adjacent instanceof Piston piston)
                        || piston.isExtended() && piston.getFacing() == face.getOppositeFace()) return false;
            }
        }
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void extend(BlockPistonExtendEvent event) {
        requireOwner();
        if (event.isCancelled()) return;
        Cell piston = cell(event.getBlock());
        if (reserved.test(piston) || reserved.test(offset(piston, event.getDirection()))) {
            event.setCancelled(true);
            return;
        }
        protectMove(event, event.getBlocks());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void retract(BlockPistonRetractEvent event) {
        requireOwner();
        // Paper can already have written the base or skipped an event for head removal.
        // Admission and extension protect those positions; this callback guards the listed move.
        protectMove(event, event.getBlocks());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void place(BlockPlaceEvent event) {
        requireOwner();
        if (event.isCancelled()) return;
        Block block = event.getBlockPlaced();
        if (pistonPart(block.getType()) && reserved.test(cell(block))) event.setCancelled(true);
    }

    private void protectMove(BlockPistonEvent event, List<Block> blocks) {
        if (event.isCancelled()) return;
        BlockFace direction = event.getDirection();
        for (Block block : blocks) {
            Cell source = cell(block);
            if (reserved.test(source) || block.getPistonMoveReaction() != PistonMoveReaction.BREAK
                    && reserved.test(offset(source, direction))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private static void requireOwner() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Piston guard requires the Paper owner");
    }

    static boolean pistonPart(Material material) {
        return switch (material) {
            case PISTON, STICKY_PISTON, PISTON_HEAD, MOVING_PISTON -> true;
            default -> false;
        };
    }

    private static Cell cell(Block block) {
        return new Cell(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }

    private static Cell offset(Cell cell, BlockFace direction) {
        return new Cell(cell.world(), cell.x() + direction.getModX(),
                cell.y() + direction.getModY(), cell.z() + direction.getModZ());
    }
}
