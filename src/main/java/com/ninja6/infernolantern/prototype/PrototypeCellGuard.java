package com.ninja6.infernolantern.prototype;

import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.util.function.Predicate;

import static com.ninja6.infernolantern.prototype.PrototypeLedger.Cell;

/** Protects recorded fixture coordinates without recovering or loading their chunks. */
public final class PrototypeCellGuard implements Listener {
    private final Predicate<Cell> recorded;

    public PrototypeCellGuard(Predicate<Cell> recorded) { this.recorded = recorded; }

    public boolean recorded(Block block) {
        return recorded.test(new Cell(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ()));
    }

    @EventHandler public void breakBlock(BlockBreakEvent event) {
        if (recorded(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler public void placeBlock(BlockPlaceEvent event) {
        if (recorded(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler public void flow(BlockFromToEvent event) {
        if (recorded(event.getToBlock())) event.setCancelled(true);
    }

    @EventHandler public void entityBlock(EntityChangeBlockEvent event) {
        if (recorded(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler public void explode(EntityExplodeEvent event) { event.blockList().removeIf(this::recorded); }

    @EventHandler public void explode(BlockExplodeEvent event) { event.blockList().removeIf(this::recorded); }

    @EventHandler public void piston(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> recorded(block) || recorded(block.getRelative(event.getDirection())))) {
            event.setCancelled(true);
        }
    }

    @EventHandler public void piston(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> recorded(block) || recorded(block.getRelative(event.getDirection())))) {
            event.setCancelled(true);
        }
    }
}
