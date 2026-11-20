package com.ninja6.infernolantern;

import com.ninja6.infernolantern.item.PaperItemIdentity;
import org.bukkit.Material;
import org.bukkit.event.Event.Result;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import io.papermc.paper.persistence.PersistentDataContainerView;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

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
