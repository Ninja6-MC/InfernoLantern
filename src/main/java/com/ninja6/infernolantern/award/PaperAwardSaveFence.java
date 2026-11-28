package com.ninja6.infernolantern.award;

import com.ninja6.infernolantern.access.OverrideSessions;
import com.ninja6.infernolantern.item.PaperItemIdentity;
import com.ninja6.infernolantern.repository.CampaignDelivery;
import com.ninja6.infernolantern.repository.SqliteRepository;

import static com.ninja6.infernolantern.repository.RepositoryRecords.MAX_PAGE;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Owner-side movement protection for unresolved campaign deliveries. It supplies no save witness. */
public final class PaperAwardSaveFence implements PaperAwardInventory.SaveFence, Listener, AutoCloseable {
    private static final int CAPTURE_LIMIT = MAX_PAGE;

    private record Slot(UUID recipient, int index) {
        Slot(CampaignDelivery delivery) { this(delivery.recipient(), delivery.slot()); }
    }

    private final Server server;
    private final OverrideSessions sessions;
    private final SqliteRepository repository;
    private final Consumer<Runnable> onOwner;
    private final PaperItemIdentity identity = new PaperItemIdentity();
    private final Map<UUID, CampaignDelivery> deliveries = new HashMap<>();
    private boolean ready;
    private boolean closed;
    private long revision;

    public PaperAwardSaveFence(Server server, OverrideSessions sessions, SqliteRepository repository,
                               Consumer<Runnable> onOwner) {
        this.server = Objects.requireNonNull(server);
        this.sessions = Objects.requireNonNull(sessions);
        this.repository = Objects.requireNonNull(repository);
        this.onOwner = Objects.requireNonNull(onOwner);
    }

    /**
     * Register this listener before calling refresh. Until a complete projection is installed,
     * recognized marked items stay unavailable. A refresh after a durable disposition can release its pin.
     */
    public CompletableFuture<Boolean> refresh() {
        owner();
        if (closed) return CompletableFuture.completedFuture(false);
        ready = false;
        long expected = ++revision;
        var installed = new CompletableFuture<Boolean>();
        repository.outstandingCampaignDeliveries(CAPTURE_LIMIT).whenComplete((items, failure) -> {
            try {
                onOwner.accept(() -> {
                    try {
                        installed.complete(failure == null && install(expected, items));
                    } catch (RuntimeException unavailable) {
                        installed.completeExceptionally(unavailable);
                    }
                });
            } catch (RuntimeException unavailable) {
                installed.completeExceptionally(unavailable);
            }
        });
        return installed;
    }

    private boolean install(long expected, List<CampaignDelivery> items) {
        owner();
        if (closed || revision != expected) return false;
        var slots = new HashSet<Slot>();
        var next = new HashMap<UUID, CampaignDelivery>();
        for (CampaignDelivery delivery : items) {
            if (!slots.add(new Slot(delivery)) || next.putIfAbsent(delivery.tower(), delivery) != null) return false;
        }
        deliveries.clear();
        deliveries.putAll(next);
        ready = true;
        return true;
    }

    @Override
    public boolean protect(Player player, CampaignDelivery delivery) {
        owner();
        if (!ready || closed || !player.isConnected() || !delivery.recipient().equals(player.getUniqueId())
                || !delivery.session().equals(sessions.currentSession(player))) return false;
        ItemStack item = player.getInventory().getItem(delivery.slot());
        var selected = new Slot(delivery);
        CampaignDelivery previous = deliveries.get(delivery.tower());
        if (previous != null) {
            return previous.equals(delivery) && exactStage(item, delivery);
        }
        if (deliveries.size() >= CAPTURE_LIMIT || !empty(item)
                || deliveries.values().stream().anyMatch(armed -> new Slot(armed).equals(selected))) return false;
        deliveries.put(delivery.tower(), delivery);
        return true;
    }

    private static boolean exactStage(ItemStack item, CampaignDelivery delivery) {
        return !empty(item) && Arrays.equals(item.serializeAsBytes(), delivery.staged().copy());
    }

    private static boolean empty(ItemStack item) {
        return item == null || item.getType() == Material.AIR || item.getType() == Material.CAVE_AIR
                || item.getType() == Material.VOID_AIR;
    }

    private boolean held(ItemStack item) {
        if (!identity.marked(item)) return false;
        if (!ready || item.getPersistentDataContainer().has(PaperItemIdentity.DELIVERY)) return true;
        return identity.read(item).map(token -> deliveries.containsKey(token.tower())).orElse(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void click(InventoryClickEvent event) {
        if (held(event.getCurrentItem()) || held(event.getCursor())) {
            event.setCancelled(true);
            return;
        }
        var inventory = event.getWhoClicked().getInventory();
        int hotbar = event.getHotbarButton();
        if (hotbar >= 0 && held(inventory.getItem(hotbar))
                || event.getClick() == ClickType.SWAP_OFFHAND && held(inventory.getItemInOffHand())
                || (event.getAction() == InventoryAction.UNKNOWN || event.getAction() == InventoryAction.COLLECT_TO_CURSOR)
                && (containsHeld(inventory.getContents()) || containsHeld(event.getView().getTopInventory().getContents()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void drag(InventoryDragEvent event) {
        if (held(event.getOldCursor()) || event.getNewItems().values().stream().anyMatch(this::held)
                || event.getRawSlots().stream().anyMatch(slot -> held(event.getView().getItem(slot)))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void swap(PlayerSwapHandItemsEvent event) {
        var inventory = event.getPlayer().getInventory();
        if (held(inventory.getItemInMainHand()) || held(inventory.getItemInOffHand())
                || held(event.getMainHandItem()) || held(event.getOffHandItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void drop(PlayerDropItemEvent event) {
        if (held(event.getItemDrop().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void pickup(EntityPickupItemEvent event) {
        if (held(event.getItem().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void pickup(InventoryPickupItemEvent event) {
        if (held(event.getItem().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void transfer(InventoryMoveItemEvent event) {
        if (held(event.getItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void interact(PlayerInteractEvent event) {
        if (held(event.getItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void interact(PlayerInteractEntityEvent event) {
        var inventory = event.getPlayer().getInventory();
        ItemStack item = event.getHand() == EquipmentSlot.HAND ? inventory.getItemInMainHand() : inventory.getItemInOffHand();
        if (held(item)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void place(BlockPlaceEvent event) {
        if (held(event.getItemInHand())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void consume(PlayerItemConsumeEvent event) {
        if (held(event.getItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void craft(PrepareItemCraftEvent event) {
        if (containsHeld(event.getInventory().getMatrix())) event.getInventory().setResult(null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void death(PlayerDeathEvent event) {
        if (event.getKeepInventory()) return;
        var drops = event.getDrops().iterator();
        while (drops.hasNext()) {
            ItemStack item = drops.next();
            if (held(item) && uniqueDeathItem(event.getEntity(), item)) {
                drops.remove();
                if (!event.getItemsToKeep().contains(item)) event.getItemsToKeep().add(item.clone());
            }
        }
    }

    private static boolean uniqueDeathItem(Player player, ItemStack item) {
        // Paper matches its keep list against inventory stacks by equality. A foreign or
        // repeated drop cannot identify a single stack to retain in its existing slot.
        return Arrays.stream(player.getInventory().getContents()).filter(item::equals).limit(2).count() == 1;
    }

    private boolean containsHeld(ItemStack[] items) {
        return Arrays.stream(items).anyMatch(this::held);
    }

    private void owner() {
        if (!server.isPrimaryThread()) throw new IllegalStateException("Paper owner thread required");
    }

    /** Close when the runtime has restored its bootstrap guard or the server is stopping. */
    @Override
    public void close() {
        owner();
        closed = true;
        ready = false;
        deliveries.clear();
        HandlerList.unregisterAll(this);
    }
}
