package com.ninja6.infernolantern.prototype;

import com.ninja6.infernolantern.item.*;
import com.ninja6.infernolantern.scheduling.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;
import java.util.*;
import java.util.function.BooleanSupplier;
import static com.ninja6.infernolantern.repository.RepositoryRecords.*;

/** Default-off creation harness; existing token protection survives creation being disabled. */
public final class ItemPrototype implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final BooleanSupplier enabled;
    private final GroundPolicy policy;
    private final ItemPrototypeStore store = new ItemPrototypeStore();
    private final PaperWorldScheduler scheduler;
    private final PaperItemFreeze freeze = new PaperItemFreeze();
    private final PaperItemIdentity identity = new PaperItemIdentity();
    private final Map<UUID,UUID> sessions = new HashMap<>();
    private final Set<UUID> issuing = new HashSet<>();
    private ItemLifecycle lifecycle;
    private FixtureDelivery delivery;
    private PaperGroundItems adapter;
    private volatile boolean closed;
    private boolean ready;
    private String status = "opening; mutations unavailable";
    public ItemPrototype(JavaPlugin plugin, BooleanSupplier enabled, GroundPolicy policy) {
        this.plugin = plugin; this.enabled = enabled; this.policy = policy;
        scheduler = new PaperWorldScheduler(plugin);
        Bukkit.getPluginManager().registerEvents(freeze,plugin);
        Bukkit.getPluginManager().registerEvents(this,plugin);
        Bukkit.getOnlinePlayers().forEach(player->sessions.put(player.getUniqueId(),UUID.randomUUID()));
        store.open(plugin.getDataFolder().toPath().resolve("item-prototype.db"), enabled.getAsBoolean(), policy)
                .whenComplete((opened,failure)->{
                    if(closed){store.closeAsync();return;}
                    try{Bukkit.getScheduler().runTask(plugin,()->{
                        if(closed)return;
                        if(failure!=null){status="failed; preserve database and evidence; mutations unavailable";plugin.getLogger().severe("Item prototype store failed to open. Preserve database/journal files; no recovery was attempted.");store.closeAsync();return;}
                        if(opened.repository()==null){status="disabled; no item store";return;}
                        if(!opened.mutationsReady()){status="quarantined; pending journal; mutations unavailable";return;}
                        if(Bukkit.getOnlinePlayers().stream().anyMatch(player->hasUnactivated(player.getInventory().getContents()))){status="quarantined; unactivated inventory evidence";return;}
                        lifecycle=new ItemLifecycle(opened.repository(),scheduler);
                        delivery=new FixtureDelivery(opened.repository(),scheduler);
                        adapter=new PaperGroundItems(plugin,opened.repository(),lifecycle,scheduler,variant->policy);
                        freeze.close();ready=true;status="ready; native acceptance pending";
                    });}catch(RuntimeException unavailable){store.closeAsync();}
                });
    }
    public String status(){return status;}
    private boolean hasUnactivated(ItemStack[] items){return Arrays.stream(items).filter(Objects::nonNull).anyMatch(item->item.getPersistentDataContainer().has(PaperItemIdentity.DELIVERY));}
    @EventHandler public void join(PlayerJoinEvent e){sessions.put(e.getPlayer().getUniqueId(),UUID.randomUUID());if(ready&&hasUnactivated(e.getPlayer().getInventory().getContents()))pause();}
    @EventHandler public void inventoryOpen(InventoryOpenEvent e){if(ready&&hasUnactivated(e.getInventory().getContents()))pause();}
    @EventHandler public void quit(PlayerQuitEvent e){sessions.remove(e.getPlayer().getUniqueId());}
    public void command(Player player){
        if(!player.isOp()){player.sendMessage("Prototype items require an operator.");return;}
        if(!enabled.getAsBoolean()){player.sendMessage("prototype.enabled: true and prototype.item-lifecycle.enabled: true required.");return;}
        if(!ready||closed){player.sendMessage("Item prototype: "+status+". Please contact an admin.");return;}
        int slot=player.getInventory().getHeldItemSlot();
        if(player.getInventory().getItem(slot)!=null){player.sendMessage("Select an empty hotbar slot before creating a disposable test item.");return;}
        if(!issuing.add(player.getUniqueId())){player.sendMessage("A test-item delivery is already pending for this session.");return;}
        UUID session=sessions.computeIfAbsent(player.getUniqueId(),ignored->UUID.randomUUID());
        UUID tower=UUID.randomUUID(),operation=UUID.randomUUID();
        ItemStack appearance=new ItemStack(Material.PAPER);
        var meta=appearance.getItemMeta();meta.displayName(Component.text("Disposable lantern lifecycle fixture"));appearance.setItemMeta(meta);
        ItemStack staged=identity.create(appearance,new ItemToken(1,FixtureDelivery.VARIANT,tower,1),policy);
        var stagedMeta=staged.getItemMeta();stagedMeta.getPersistentDataContainer().set(PaperItemIdentity.DELIVERY,PersistentDataType.STRING,operation.toString());staged.setItemMeta(stagedMeta);
        byte[] bytes=staged.serializeAsBytes();
        var location=player.getLocation();
        var anchor=new WorldScheduler.Anchor(player.getWorld().getUID(),location.getBlockX()>>4,location.getBlockZ()>>4);
        var request=new FixtureDelivery.Request(tower,operation,anchor,new Document(1,"fixture recipient="+player.getUniqueId()+";session="+session+";slot="+slot+";staged="+Base64.getEncoder().encodeToString(bytes)));
        player.sendMessage("Preparing one disposable item; keep the selected slot empty until completion.");
        delivery.issue(request,new FixtureDelivery.Effects(){
            private boolean sameSession(){return !closed&&ready&&player.isOnline()&&session.equals(sessions.get(player.getUniqueId()))&&player.getWorld().getUID().equals(anchor.world());}
            private boolean exact(){var item=player.getInventory().getItem(slot);return item!=null&&Arrays.equals(item.serializeAsBytes(),bytes);}
            public boolean stage(){if(!sameSession()||!enabled.getAsBoolean()||!player.isOp()||player.getInventory().getHeldItemSlot()!=slot||player.getInventory().getItem(slot)!=null)return false;player.getInventory().setItem(slot,staged.clone());return exact();}
            public boolean verify(){return sameSession()&&enabled.getAsBoolean()&&player.isOp()&&player.getInventory().getHeldItemSlot()==slot&&exact();}
            public boolean activate(){
                if(!verify())return false;
                var item=player.getInventory().getItem(slot);var itemMeta=item.getItemMeta();
                if(!operation.toString().equals(itemMeta.getPersistentDataContainer().get(PaperItemIdentity.DELIVERY,PersistentDataType.STRING)))return false;
                itemMeta.getPersistentDataContainer().remove(PaperItemIdentity.DELIVERY);item.setItemMeta(itemMeta);
                player.getInventory().setItem(slot,item);
                return identity.read(player.getInventory().getItem(slot)).filter(request.token()::equals).isPresent();
            }
        }).thenAccept(result->{
            if(closed)return;
            try{Bukkit.getScheduler().runTask(plugin,()->{
            if(closed)return;
            issuing.remove(player.getUniqueId());
            if(result.outcome()==ItemLifecycle.Outcome.QUARANTINED){pause();}
            if(player.isOnline()&&session.equals(sessions.get(player.getUniqueId())))player.sendMessage(result.outcome()==ItemLifecycle.Outcome.COMMITTED
                    ?"One disposable lifecycle test item is ready. World placement is not implemented."
                    :"Test-item delivery did not complete. Preserve the item and journal; please contact an admin. Do not retry an ambiguous delivery.");
            });}catch(RuntimeException unavailable){/* Durable evidence remains; do not retry delivery. */}
        });
    }
    private void pause(){ready=false;status="quarantined; delivery evidence requires inspection";if(lifecycle!=null)lifecycle.close();if(delivery!=null)delivery.close();if(adapter!=null)adapter.close();Bukkit.getPluginManager().registerEvents(freeze,plugin);}
    @Override public void close(){
        closed=true;ready=false;status="closed";
        if(delivery!=null)delivery.close();if(lifecycle!=null)lifecycle.close();if(adapter!=null)adapter.close();
        scheduler.close();freeze.close();HandlerList.unregisterAll(this);sessions.clear();issuing.clear();
        store.closeAsync().exceptionally(failure->{plugin.getLogger().severe("Item prototype store did not close cleanly. Preserve database/journal evidence.");return null;});
    }
}
