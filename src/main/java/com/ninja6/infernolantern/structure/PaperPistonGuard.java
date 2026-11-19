package com.ninja6.infernolantern.structure;

import com.ninja6.infernolantern.repository.RepositoryRecords.Cell;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.PistonMoveReaction;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/** Owner-side guard for exact committed and active-operation reservation cells. */
public final class PaperPistonGuard implements Listener {
    private final Predicate<Cell> reserved;

    /**
     * The caller publishes its current loaded footprint, including pending before/after reservations,
     * before enabling native structure access. Lookup stays on the owner and never waits on SQLite.
     */
    public PaperPistonGuard(Predicate<Cell> reserved) {
        this.reserved = Objects.requireNonNull(reserved);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void extend(BlockPistonExtendEvent event) {
        protect(event, event.getBlocks(), false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void retract(BlockPistonRetractEvent event) {
        protect(event, event.getBlocks(), true);
    }

    private void protect(BlockPistonEvent event, List<Block> blocks, boolean retracting) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Piston guard requires the Paper owner");
        if (event.isCancelled()) return;
        BlockFace direction = event.getDirection();
        // Paper reports movement for nonempty events and piston facing for empty retractions.
        BlockFace head = retracting && !blocks.isEmpty() ? direction.getOppositeFace() : direction;
        Cell piston = cell(event.getBlock());
        if (reserved.test(piston) || reserved.test(offset(piston, head))) {
            event.setCancelled(true);
            return;
        }
        for (Block block : blocks) {
            Cell source = cell(block);
            if (reserved.test(source) || block.getPistonMoveReaction() != PistonMoveReaction.BREAK
                    && reserved.test(offset(source, direction))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private static Cell cell(Block block) {
        return new Cell(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }

    private static Cell offset(Cell cell, BlockFace direction) {
        return new Cell(cell.world(), cell.x() + direction.getModX(),
                cell.y() + direction.getModY(), cell.z() + direction.getModZ());
    }
}
