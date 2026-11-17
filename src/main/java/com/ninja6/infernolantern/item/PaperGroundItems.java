package com.ninja6.infernolantern.item;

import com.ninja6.infernolantern.repository.*;
import com.ninja6.infernolantern.scheduling.WorldScheduler;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import static com.ninja6.infernolantern.repository.RepositoryRecords.*;

/** Opt-in Paper adapter. No acquisition permission check, ownership registry, or startup replay. */
public final class PaperGroundItems implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final ItemLifecycle lifecycle;
    private final SqliteRepository repository;
    private static final NamespacedKey STAGED = new NamespacedKey("infernolantern", "item_staged");
    private final WorldScheduler scheduler;
    private final PaperItemIdentity identity = new PaperItemIdentity();
    private final Function<String, GroundPolicy> policies;
    // Owner-thread only. Session IDs invalidate callbacks even if a player reconnects with the same UUID.
    private final Map<UUID, UUID> sessions = new HashMap<>();
    private final Set<UUID> busy = new HashSet<>(), internalRemovals = new HashSet<>();
    private final ArrayDeque<UUID> physics = new ArrayDeque<>();
    private final Set<UUID> known = new HashSet<>();
    private final BukkitTask pulse;
    private boolean closed;
    public PaperGroundItems(JavaPlugin plugin, SqliteRepository repository, ItemLifecycle lifecycle, WorldScheduler scheduler,
                            Function<String, GroundPolicy> policies) {
        this.plugin = Objects.requireNonNull(plugin); this.lifecycle = Objects.requireNonNull(lifecycle);
        this.repository = Objects.requireNonNull(repository);
        this.scheduler = Objects.requireNonNull(scheduler); this.policies = Objects.requireNonNull(policies);
        Bukkit.getOnlinePlayers().forEach(p -> sessions.put(p.getUniqueId(), UUID.randomUUID()));
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        // Chunk/entity load notifications handle later availability; never scan/load all saved chunks.
        for (World world : Bukkit.getWorlds()) for (Chunk chunk : world.getLoadedChunks())
            for (Entity entity : chunk.getEntities()) if (entity instanceof Item item) inspectLoaded(item);
        pulse = Bukkit.getScheduler().runTaskTimer(plugin, this::physics, 1, 1);
    }
    private static ItemLifecycle.Effects checked(Function<ItemLifecycle.Claim, Boolean> apply, Function<ItemLifecycle.Claim, Boolean> verify) {
        return new ItemLifecycle.Effects() {
            @Override public boolean apply(ItemLifecycle.Claim claim) { return apply.apply(claim); }
            @Override public boolean verify(ItemLifecycle.Claim claim) { return verify.apply(claim); }
        };
    }
    public PaperItemIdentity identity() { return identity; }
    private GroundPolicy policy(ItemToken token) { return Objects.requireNonNull(policies.apply(token.variant())); }
    private static WorldScheduler.Anchor anchor(Entity entity) {
        var location = entity.getLocation();
        return new WorldScheduler.Anchor(entity.getWorld().getUID(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }
    private UUID session(Player player) { return sessions.computeIfAbsent(player.getUniqueId(), ignored -> UUID.randomUUID()); }
    private boolean sameSession(Player player, UUID session) { return !closed && player.isOnline() && session.equals(sessions.get(player.getUniqueId())); }
    private void privateRejection(Player player) { player.sendMessage(ItemLifecycle.REJECTION); }
    private static boolean exact(ItemStack item, byte[] bytes) { return item != null && Arrays.equals(item.serializeAsBytes(), bytes); }
    private Document evidence(String action, UUID actor, UUID session, int slot, byte[] stack) {
        return new Document(1, "action=" + action + ";actor=" + actor + ";session=" + session + ";slot=" + slot
                + ";source=" + Base64.getEncoder().encodeToString(stack));
    }
    private void removeOwned(Item item) {
        internalRemovals.add(item.getUniqueId());
        try { item.remove(); } finally { internalRemovals.remove(item.getUniqueId()); known.remove(item.getUniqueId()); }
    }
    private void inspectLoaded(Item item) {
        track(item);
        if (!identity.marked(item.getItemStack())) return;
        item.setCanPlayerPickup(false);
        if (item.getPersistentDataContainer().has(STAGED)) {
            item.setInvulnerable(true);
            String value = item.getPersistentDataContainer().get(PaperItemIdentity.OPERATION, PersistentDataType.STRING);
            try {
                UUID operation = UUID.fromString(value);
                WorldScheduler.Anchor location = anchor(item);
                repository.operation(operation).thenAccept(found -> scheduler.supply(location, () -> {
                    if (!closed && !busy.contains(item.getUniqueId()) && item.isValid() && found.isEmpty()
                            && item.getPersistentDataContainer().has(STAGED) && !item.canPlayerPickup()
                            && operation.toString().equals(item.getPersistentDataContainer().get(PaperItemIdentity.OPERATION, PersistentDataType.STRING))) removeOwned(item);
                    return true;
                }));
            } catch (RuntimeException malformed) { /* Preserve unknown staging for administrative inspection. */ }
            return;
        }
        var parsed = identity.read(item.getItemStack());
        if (parsed.isEmpty()) return;
        ItemToken token = parsed.get(); UUID entityId = item.getUniqueId();
        WorldScheduler.Anchor location = anchor(item);
        repository.tower(token.tower()).thenAccept(found -> scheduler.supply(location, () -> {
            if (!closed && item.isValid() && identity.read(item.getItemStack()).filter(token::equals).isPresent()
                    && found.filter(row -> row.mutationReady() && ItemLifecycle.token(row.state().tower()).equals(token)
                        && row.state().ground() != null && row.state().ground().entity().equals(entityId)).isPresent()) item.setCanPlayerPickup(true);
            return true;
        }));
    }
    private void track(Item item) {
        if (!identity.marked(item.getItemStack())) return;
        item.setCanMobPickup(false);
        if (item.getPersistentDataContainer().has(STAGED)) item.setUnlimitedLifetime(true);
        else identity.read(item.getItemStack()).ifPresent(token -> item.setUnlimitedLifetime(!policy(token).despawns()));
        if (known.add(item.getUniqueId())) physics.add(item.getUniqueId());
    }
    /** Bounded technical work per tick; no gameplay item-count limit and no unrelated entities. */
    private void physics() {
        int count = Math.min(64, physics.size());
        for (int i = 0; i < count; i++) {
            UUID id = physics.removeFirst();
            Entity entity = Bukkit.getEntity(id);
            if (!(entity instanceof Item item) || !item.isValid()) { known.remove(id); continue; }
            physics.addLast(id);
            identity.read(item.getItemStack()).ifPresent(token -> {
                if (policy(token).survivesFireAndLava() && item.getLocation().getBlock().getType() == Material.LAVA) {
                    var velocity = item.getVelocity();
                    if (velocity.getY() < 0.06) item.setVelocity(velocity.setY(0.06));
                    item.setFireTicks(0);
                }
            });
        }
    }
    @EventHandler public void join(PlayerJoinEvent event) { sessions.put(event.getPlayer().getUniqueId(), UUID.randomUUID()); }
    @EventHandler public void quit(PlayerQuitEvent event) { sessions.remove(event.getPlayer().getUniqueId()); }
    @EventHandler public void loaded(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) if (entity instanceof Item item) inspectLoaded(item);
    }
    @EventHandler public void unloaded(EntitiesUnloadEvent event) {
        var unloaded = new HashSet<UUID>();
        for (Entity entity : event.getEntities()) { known.remove(entity.getUniqueId()); unloaded.add(entity.getUniqueId()); }
        physics.removeIf(unloaded::contains);
        // No LOST transition. Missing/unloaded entities are not intentional destruction.
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void merge(ItemMergeEvent event) {
        if (identity.marked(event.getEntity().getItemStack()) || identity.marked(event.getTarget().getItemStack())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void hopper(InventoryPickupItemEvent event) {
        if (identity.marked(event.getItem().getItemStack())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void drop(PlayerDropItemEvent event) {
        ItemStack source = event.getItemDrop().getItemStack();
        if (!identity.marked(source)) return;
        event.setCancelled(true); // Paper restores the original; capture it on the following owner tick.
        Player player = event.getPlayer(); UUID session = session(player); int slot = player.getInventory().getHeldItemSlot();
        byte[] bytes = source.serializeAsBytes();
        if (identity.read(source).isEmpty()) { privateRejection(player); return; }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!sameSession(player, session) || !exact(player.getInventory().getItem(slot), bytes)) { if (player.isOnline()) privateRejection(player); return; }
            dropSlot(player, slot);
        });
    }
    /** Explicit owner-thread drop API; callers preserve source until the journal owns the transition. */
    public CompletableFuture<ItemLifecycle.Result> dropSlot(Player player, int slot) {
        return dropSlot(player, slot, player.getLocation());
    }
    private CompletableFuture<ItemLifecycle.Result> dropSlot(Player player, int slot, Location destination) {
        ItemStack source = player.getInventory().getItem(slot);
        var parsed = identity.read(source);
        UUID operation = UUID.randomUUID();
        if (closed || parsed.isEmpty()) {
            privateRejection(player);
            return CompletableFuture.completedFuture(new ItemLifecycle.Result(ItemLifecycle.Outcome.REJECTED, operation));
        }
        ItemToken token = parsed.get(); UUID session = session(player); byte[] bytes = source.serializeAsBytes();
        GroundPolicy policy = policy(token);
        Document details = evidence("drop", player.getUniqueId(), session, slot, bytes);
        // Staging is unavailable and carries an invalid future generation. Source remains intact.
        Item staged = destination.getWorld().dropItem(destination, identity.create(source, token.next(), policy), item -> {
            item.setCanPlayerPickup(false); item.setCanMobPickup(false); item.setInvulnerable(true);
            item.setUnlimitedLifetime(true);
            item.getPersistentDataContainer().set(PaperItemIdentity.OPERATION, PersistentDataType.STRING, operation.toString());
            item.getPersistentDataContainer().set(STAGED, PersistentDataType.BYTE, (byte) 1);
        });
        track(staged); staged.setUnlimitedLifetime(true);
        UUID entityId = staged.getUniqueId(); busy.add(entityId);
        var request = new ItemLifecycle.Request(operation, ItemLifecycle.Action.DROP, token, 1, entityId, anchor(staged), null,
                details);
        var future = lifecycle.execute(request, checked(claim -> {
            if (!sameSession(player, session) || !exact(player.getInventory().getItem(slot), bytes)
                    || !staged.isValid() || !entityId.equals(staged.getUniqueId())
                    || !operation.toString().equals(staged.getPersistentDataContainer().get(PaperItemIdentity.OPERATION, PersistentDataType.STRING))
                    || !identity.read(staged.getItemStack()).filter(claim.resultingToken()::equals).isPresent()) return false;
            player.getInventory().setItem(slot, null);
            return player.getInventory().getItem(slot) == null && staged.isValid();
        }, claim -> sameSession(player, session) && player.getInventory().getItem(slot) == null && staged.isValid()
                && identity.read(staged.getItemStack()).filter(claim.resultingToken()::equals).isPresent()));
        future.thenAccept(result -> {
            if (result.outcome() == ItemLifecycle.Outcome.COMMITTED) {
                repository.tower(token.tower()).thenAccept(found -> scheduler.supply(request.anchor(), () -> {
                    busy.remove(entityId);
                    if (sameSession(player, session) && staged.isValid() && staged.getWorld().getUID().equals(request.anchor().world())
                            && identity.read(staged.getItemStack()).filter(token.next()::equals).isPresent()
                            && operation.toString().equals(staged.getPersistentDataContainer().get(PaperItemIdentity.OPERATION, PersistentDataType.STRING))
                            && found.filter(row -> row.mutationReady() && ItemLifecycle.token(row.state().tower()).equals(token.next())
                                && row.state().ground() != null && row.state().ground().entity().equals(entityId)).isPresent()) {
                        staged.getPersistentDataContainer().remove(STAGED);
                        staged.setInvulnerable(false); staged.setUnlimitedLifetime(!policy.despawns());
                        staged.setCanPlayerPickup(true); staged.setPickupDelay(20);
                    }
                    return true;
                }));
            } else scheduler.supply(request.anchor(), () -> {
                busy.remove(entityId);
                if (result.outcome() == ItemLifecycle.Outcome.REJECTED) {
                    // No intent/effects were admitted. Dispose only our unavailable staging entity.
                    if (staged.isValid()) removeOwned(staged);
                } else if (staged.isValid()) { staged.setCanPlayerPickup(false); staged.setInvulnerable(true); }
                if (sameSession(player, session)) privateRejection(player);
                return true;
            });
        });
        return future;
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void pickup(EntityPickupItemEvent event) {
        if (event == validationPickup) return;
        Item item = event.getItem();
        if (!identity.marked(item.getItemStack())) return;
        event.setCancelled(true);
        if (!(event.getEntity() instanceof Player player) || busy.contains(item.getUniqueId())) return;
        var parsed = identity.read(item.getItemStack());
        if (parsed.isEmpty()) { privateRejection(player); return; }
        if (!providerAllowsPickup(player, item)) return;
        int slot = player.getInventory().firstEmpty();
        if (slot < 0) return;
        UUID operation = UUID.randomUUID(), session = session(player), entityId = item.getUniqueId();
        byte[] bytes = item.getItemStack().serializeAsBytes(); busy.add(entityId);
        ItemToken token = parsed.get();
        var request = new ItemLifecycle.Request(operation, ItemLifecycle.Action.PICKUP, token, 1, entityId, anchor(item), null,
                evidence("pickup", player.getUniqueId(), session, slot, bytes));
        lifecycle.execute(request, checked(claim -> {
            // Recheck external providers at the effect boundary without allowing vanilla mutation.
            if (!sameSession(player, session) || !item.isValid() || !exact(item.getItemStack(), bytes)
                    || !player.getWorld().equals(item.getWorld()) || player.getLocation().distanceSquared(item.getLocation()) > 9
                    || player.getInventory().getItem(slot) != null) return false;
            if (!providerAllowsPickup(player, item) || !item.isValid() || !exact(item.getItemStack(), bytes)
                    || player.getInventory().getItem(slot) != null || !sameSession(player, session)) return false;
            ItemStack portable = identity.create(item.getItemStack(), claim.resultingToken(), policy(token));
            player.getInventory().setItem(slot, portable);
            removeOwned(item);
            return exact(player.getInventory().getItem(slot), portable.serializeAsBytes()) && !item.isValid();
        }, claim -> sameSession(player, session) && !item.isValid()
                && identity.read(player.getInventory().getItem(slot)).filter(claim.resultingToken()::equals).isPresent())).thenAccept(result -> scheduler.supply(request.anchor(), () -> {
            busy.remove(entityId);
            if (result.outcome() != ItemLifecycle.Outcome.COMMITTED && sameSession(player, session)) privateRejection(player);
            return true;
        }));
    }
    private EntityPickupItemEvent validationPickup;
    private boolean providerAllowsPickup(Player player, Item item) {
        EntityPickupItemEvent check = new EntityPickupItemEvent(player, item, 0);
        EntityPickupItemEvent previous = validationPickup; validationPickup = check;
        try { Bukkit.getPluginManager().callEvent(check); } finally { validationPickup = previous; }
        return !check.isCancelled();
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void despawn(ItemDespawnEvent event) {
        Item item = event.getEntity();
        if (!identity.marked(item.getItemStack())) return;
        event.setCancelled(true);
        if (item.getPersistentDataContainer().has(STAGED)) return;
        var token = identity.read(item.getItemStack());
        if (token.isPresent() && policy(token.get()).despawns()) lose(item, token.get(), "despawn");
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void damage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Item item) || !identity.marked(item.getItemStack())) return;
        event.setCancelled(true);
        if (item.getPersistentDataContainer().has(STAGED)) return;
        var token = identity.read(item.getItemStack()); if (token.isEmpty()) return;
        boolean fire = switch (event.getCause()) { case FIRE, FIRE_TICK, LAVA, HOT_FLOOR -> true; default -> false; };
        if (fire && policy(token.get()).survivesFireAndLava()) return;
        if (event.getFinalDamage() >= item.getHealth()) lose(item, token.get(), "damage-" + event.getCause().name());
        else if (event.getFinalDamage() > 0) item.setHealth(Math.max(1, item.getHealth() - (int) Math.ceil(event.getFinalDamage())));
    }
    private void lose(Item item, ItemToken token, String cause) {
        UUID entityId = item.getUniqueId(); if (!busy.add(entityId)) return;
        byte[] bytes = item.getItemStack().serializeAsBytes();
        var request = new ItemLifecycle.Request(UUID.randomUUID(), ItemLifecycle.Action.LOSS, token, 1, entityId, anchor(item), null, new Document(1, cause));
        lifecycle.execute(request, checked(claim -> {
            if (!item.isValid() || !exact(item.getItemStack(), bytes)) return false;
            removeOwned(item); return !item.isValid();
        }, claim -> !item.isValid())).thenAccept(result -> scheduler.supply(request.anchor(), () -> { busy.remove(entityId); return true; }));
    }
    @EventHandler public void removed(EntityRemoveEvent event) {
        if (!(event.getEntity() instanceof Item item) || internalRemovals.contains(item.getUniqueId()) || event.getCause() == EntityRemoveEvent.Cause.UNLOAD) return;
        known.remove(item.getUniqueId());
        identity.read(item.getItemStack()).ifPresent(token -> lifecycle.unexpectedRemoval(new ItemLifecycle.Request(UUID.randomUUID(),
                ItemLifecycle.Action.LOSS, token, 1, item.getUniqueId(), anchor(item), null, new Document(1, "external-removal-" + event.getCause().name()))));
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void death(PlayerDeathEvent event) {
        if (event.getKeepInventory()) return;
        Player player = event.getEntity(); UUID session = session(player); Location location = player.getLocation().clone();
        var retained = new ArrayList<byte[]>();
        var drops = event.getDrops().iterator();
        while (drops.hasNext()) {
            ItemStack item = drops.next();
            if (!identity.marked(item)) continue;
            drops.remove();
            // Respect another provider's individual keep decision; never keep plus drop one source.
            if (event.getItemsToKeep().contains(item)) continue;
            event.getItemsToKeep().add(item.clone());
            retained.add(item.serializeAsBytes());
        }
        if (retained.isEmpty()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!sameSession(player, session) || event.getKeepInventory()) return;
            if (!location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
                privateRejection(player); return;
            }
            var used = new HashSet<Integer>();
            for (byte[] bytes : retained) {
                int found = -1;
                for (int slot = 0; slot < player.getInventory().getSize(); slot++)
                    if (!used.contains(slot) && exact(player.getInventory().getItem(slot), bytes)) { found = slot; break; }
                if (found < 0) { privateRejection(player); continue; }
                used.add(found); dropSlot(player, found, location);
            }
        });
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void spawned(ItemSpawnEvent event) {
        Item item = event.getEntity();
        if (identity.marked(item.getItemStack())) {
            track(item);
            if (!item.getPersistentDataContainer().has(STAGED)) inspectLoaded(item);
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void vanillaUse(PlayerInteractEvent event) {
        if (identity.marked(event.getItem())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void vanillaPlace(org.bukkit.event.block.BlockPlaceEvent event) {
        if (identity.marked(event.getItemInHand())) { event.setCancelled(true); privateRejection(event.getPlayer()); }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void consume(PlayerItemConsumeEvent event) {
        if (identity.marked(event.getItem())) event.setCancelled(true);
    }
    private static boolean storage(InventoryType type) {
        return switch (type) { case CHEST, BARREL, SHULKER_BOX, ENDER_CHEST, PLAYER, CRAFTING -> true; default -> false; };
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void transform(InventoryClickEvent event) {
        boolean token = identity.marked(event.getCurrentItem()) || identity.marked(event.getCursor());
        if (event.getHotbarButton() >= 0) token |= identity.marked(event.getWhoClicked().getInventory().getItem(event.getHotbarButton()));
        if (!token) return;
        boolean top = event.getRawSlot() >= 0 && event.getRawSlot() < event.getView().getTopInventory().getSize();
        if ((event.getSlotType() == InventoryType.SlotType.CRAFTING || event.getSlotType() == InventoryType.SlotType.RESULT)
                || (!storage(event.getView().getTopInventory().getType()) && (top || event.isShiftClick()))) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void drag(InventoryDragEvent event) {
        if (!identity.marked(event.getOldCursor())) return;
        int top = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot < top)
                && (!storage(event.getView().getTopInventory().getType()) || event.getView().getTopInventory().getType() == InventoryType.CRAFTING)) event.setCancelled(true);
    }
    @EventHandler public void craft(PrepareItemCraftEvent event) {
        if (Arrays.stream(event.getInventory().getMatrix()).anyMatch(identity::marked)) event.getInventory().setResult(null);
    }
    @Override public void close() {
        closed = true; pulse.cancel(); HandlerList.unregisterAll(this); sessions.clear(); known.clear(); physics.clear();
        // Pending intent remains durable; shutdown must not refund, respawn or infer loss.
    }
}
