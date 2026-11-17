package com.ninja6.infernolantern.item;

import org.bukkit.event.*;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.entity.Item;
import java.util.Arrays;

/** Fail-closed bootstrap guard. Storage remains possible; no unavailable token may mutate. */
public final class PaperItemFreeze implements Listener {
    private final PaperItemIdentity identity = new PaperItemIdentity();
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void drop(PlayerDropItemEvent e) { if(identity.marked(e.getItemDrop().getItemStack())) { e.setCancelled(true); e.getPlayer().sendMessage("Item prototype is unavailable. Please contact an admin."); } }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void pickup(EntityPickupItemEvent e) { if(identity.marked(e.getItem().getItemStack()))e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void hopper(InventoryPickupItemEvent e) { if(identity.marked(e.getItem().getItemStack()))e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void merge(ItemMergeEvent e) { if(identity.marked(e.getEntity().getItemStack())||identity.marked(e.getTarget().getItemStack()))e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void damage(EntityDamageEvent e) { if(e.getEntity() instanceof Item item && identity.marked(item.getItemStack()))e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void despawn(ItemDespawnEvent e) { if(identity.marked(e.getEntity().getItemStack()))e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void use(PlayerInteractEvent e) { if(identity.marked(e.getItem()))e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void place(BlockPlaceEvent e) { if(identity.marked(e.getItemInHand()))e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void consume(PlayerItemConsumeEvent e) { if(identity.marked(e.getItem()))e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void death(PlayerDeathEvent e) {
        if(e.getKeepInventory())return;
        var drops=e.getDrops().iterator();
        while(drops.hasNext()){var item=drops.next();if(identity.marked(item)){drops.remove();if(!e.getItemsToKeep().contains(item))e.getItemsToKeep().add(item.clone());}}
    }
    @EventHandler public void craft(PrepareItemCraftEvent e){if(Arrays.stream(e.getInventory().getMatrix()).anyMatch(identity::marked))e.getInventory().setResult(null);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void inventory(InventoryClickEvent e) {
        boolean marked=identity.marked(e.getCurrentItem())||identity.marked(e.getCursor());
        if(e.getHotbarButton()>=0)marked|=identity.marked(e.getWhoClicked().getInventory().getItem(e.getHotbarButton()));
        if(marked&&(e.getSlotType()==InventoryType.SlotType.CRAFTING||e.getSlotType()==InventoryType.SlotType.RESULT
                ||(!storage(e.getView().getTopInventory().getType())&&(e.isShiftClick()||e.getRawSlot()<e.getView().getTopInventory().getSize()))))e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void drag(InventoryDragEvent e){
        if(identity.marked(e.getOldCursor())&&e.getRawSlots().stream().anyMatch(slot->slot<e.getView().getTopInventory().getSize())
                &&(!storage(e.getView().getTopInventory().getType())||e.getView().getTopInventory().getType()==InventoryType.CRAFTING))e.setCancelled(true);
    }
    private static boolean storage(InventoryType type){return switch(type){case CHEST,BARREL,SHULKER_BOX,ENDER_CHEST,PLAYER,CRAFTING->true;default->false;};}
    public void close(){HandlerList.unregisterAll(this);}
}
