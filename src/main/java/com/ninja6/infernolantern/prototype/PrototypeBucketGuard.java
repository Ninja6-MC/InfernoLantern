package com.ninja6.infernolantern.prototype;

import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBucketEmptyEvent;

import java.util.function.Predicate;

/** Bucket placement is a separate mutation path from block placement and fluid flow. */
public final class PrototypeBucketGuard implements Listener {
    private final Predicate<Block> recorded;

    public PrototypeBucketGuard(Predicate<Block> recorded) {
        this.recorded = recorded;
    }

    @EventHandler
    public void empty(PlayerBucketEmptyEvent event) {
        // Paper supplies the actual destination, including waterlogging and retries
        // into an adjacent cell. The clicked block/face alone is not sufficient.
        if (recorded.test(event.getBlock())) event.setCancelled(true);
    }
}
