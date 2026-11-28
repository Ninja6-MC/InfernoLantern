package com.ninja6.infernolantern.runtime;

import com.ninja6.infernolantern.repository.RepositoryRecords.Cell;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;

import java.util.Set;

/** Protects published coordinates from direct player edits. Used only on the Paper owner. */
public final class PaperHeldReservationGuard implements Listener, AutoCloseable {
    private Set<Cell> cells = Set.of();
    private boolean closed;

    public boolean publish(Set<Cell> verified) {
        if (closed) return false;
        cells = Set.copyOf(verified);
        return true;
    }

    private boolean reserved(Block block) {
        return !cells.isEmpty() && reserved(block.getWorld(), block.getX(), block.getY(), block.getZ());
    }

    private boolean reserved(BlockState replaced) {
        return reserved(replaced.getWorld(), replaced.getX(), replaced.getY(), replaced.getZ());
    }

    private boolean reserved(World world, int x, int y, int z) {
        return cells.contains(new Cell(world.getUID(), x, y, z));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent event) {
        if (reserved(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void place(BlockPlaceEvent event) {
        if (!cells.isEmpty() && (reserved(event.getBlockPlaced())
                || event instanceof BlockMultiPlaceEvent multiple
                    && multiple.getReplacedBlockStates().stream().anyMatch(this::reserved))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void empty(PlayerBucketEmptyEvent event) {
        if (reserved(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void fill(PlayerBucketFillEvent event) {
        if (reserved(event.getBlock())) event.setCancelled(true);
    }

    @Override public void close() {
        closed = true;
        cells = Set.of();
        HandlerList.unregisterAll(this);
    }
}
