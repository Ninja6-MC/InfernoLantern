package com.ninja6.infernolantern.prototype;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;

import java.io.IOException;
import java.util.*;
import static com.ninja6.infernolantern.prototype.PrototypeLedger.Cell;

/** Deliberately opt-in, operator-only feasibility fixtures, never production lanterns. */
public final class RenderingPrototype implements Listener {
    private static final String TAG = "infernolantern-prototype";
    private static final String BARRIER = "minecraft:barrier";
    private static final String LIGHT_OFF = "minecraft:light[level=0,waterlogged=false]";
    private static final String LIGHT_ON = "minecraft:light[level=15,waterlogged=false]";
    private final JavaPlugin plugin;
    private final PrototypeLedger ledger;
    private final Map<Cell, Fixture> fixtures = new HashMap<>();
    private static final class Fixture {
        final Cell base;
        ArmorStand visual;
        boolean expanded, light, defence;
        Fixture(Cell base) { this.base = base; }
    }
    private static final class Menu implements InventoryHolder {
        final Cell base;
        final Inventory inventory;
        Menu(Cell base) {
            this.base = base;
            inventory = Bukkit.createInventory(this, 9, "Rendering prototype");
        }
        public Inventory getInventory() { return inventory; }
    }

    public RenderingPrototype(JavaPlugin plugin) throws IOException {
        this.plugin = plugin;
        ledger = new PrototypeLedger(plugin.getDataFolder().toPath().resolve("prototype-cleanup.bin"), new PrototypeLedger.Blocks() {
            public boolean loaded(Cell cell) {
                World world = Bukkit.getWorld(cell.world());
                return world != null && world.isChunkLoaded(cell.x() >> 4, cell.z() >> 4);
            }
            public String read(Cell cell) { return block(cell).getBlockData().getAsString(); }
            public String canonical(String state) { return Bukkit.createBlockData(state).getAsString(); }
            public void write(Cell cell, String state) { block(cell).setBlockData(Bukkit.createBlockData(state), false); }
        });
        recover(ledger.cells());
        for (World world : Bukkit.getWorlds()) for (Entity entity : world.getEntities()) removeStale(entity);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getPluginManager().registerEvents(new PrototypeBucketGuard(block -> ledger.cells().contains(cell(block))), plugin);
    }

    public int count() { return fixtures.size(); }
    public int pendingCleanupCells() { return ledger.cells().size(); }
    private Block block(Cell cell) { return Objects.requireNonNull(Bukkit.getWorld(cell.world())).getBlockAt(cell.x(), cell.y(), cell.z()); }
    private Cell cell(Block block) { return new Cell(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ()); }
    private Location center(Cell cell) { return new Location(Bukkit.getWorld(cell.world()), cell.x() + .5, cell.y(), cell.z() + .5); }
    private List<Cell> volume(Cell base, int height) {
        List<Cell> result = new ArrayList<>();
        for (int y = 0; y < height; y++) result.add(base.above(y));
        return result;
    }
    private boolean permitted(Player player) { return player.isOp() && plugin.getConfig().getBoolean("prototype.enabled", false); }

    public void command(Player player, String[] arguments) {
        if (!permitted(player)) { player.sendMessage("Prototype fixtures require an operator and prototype.enabled: true."); return; }
        try {
            String action = arguments.length == 0 ? "menu" : arguments[0].toLowerCase(Locale.ROOT);
            if (arguments.length > 1) throw new IllegalStateException("Use prototype [place|remove|cleanup].");
            if (action.equals("cleanup")) {
                for (Fixture fixture : new ArrayList<>(fixtures.values())) remove(fixture);
                recover(ledger.cells());
                player.sendMessage("Prototype cleanup complete for loaded chunks; pending cells: " + pendingCleanupCells());
                return;
            }
            RayTraceResult target = player.rayTraceBlocks(5.0);
            if (target == null || target.getHitBlock() == null) throw new IllegalStateException("Look at a block within five blocks.");
            if (action.equals("place")) {
                Block support = target.getHitBlock();
                if (target.getHitBlockFace() != org.bukkit.block.BlockFace.UP || !support.getType().isSolid()) throw new IllegalStateException("Place on top of solid support.");
                Cell base = cell(support.getRelative(org.bukkit.block.BlockFace.UP));
                checkClear(base, 1, null);
                Fixture fixture = new Fixture(base);
                fixtures.put(base, fixture);
                try { ledger.apply(Map.of(base, BARRIER)); render(fixture); }
                catch (Exception failure) { remove(fixture); throw failure; }
                open(player, fixture);
            } else {
                Fixture fixture = find(cell(target.getHitBlock()));
                if (fixture == null) throw new IllegalStateException("Look at a prototype fixture.");
                if (action.equals("remove")) remove(fixture);
                else if (action.equals("menu")) open(player, fixture);
                else throw new IllegalStateException("Use prototype [place|remove|cleanup].");
            }
        } catch (IOException failure) {
            plugin.getLogger().severe("Prototype cleanup manifest could not be updated; operations stopped. Preserve the manifest for recovery.");
            player.sendMessage("Prototype storage failed. Stop testing and inspect the console.");
        } catch (RuntimeException failure) { player.sendMessage("Prototype: " + failure.getMessage()); }
    }

    private void checkClear(Cell base, int height, Fixture self) {
        World world = Objects.requireNonNull(Bukkit.getWorld(base.world()));
        PrototypeSpace.validate(base.y(), world.getMinHeight(), world.getMaxHeight(), height,
                world.isChunkLoaded(base.x() >> 4, base.z() >> 4), offset -> {
                    Cell at = base.above(offset);
                    if (self != null && offset == 0) return block(at).getType() == Material.BARRIER;
                    return block(at).getType().isAir() && !ledger.cells().contains(at);
                });
        BoundingBox box = new BoundingBox(base.x(), base.y(), base.z(), base.x() + 1, base.y() + height, base.z() + 1);
        if (!world.getNearbyEntities(box, entity -> self == null || entity != self.visual).isEmpty()) throw new IllegalStateException("An entity occupies the required volume.");
    }

    private void expand(Fixture fixture) throws IOException {
        checkClear(fixture.base, 3, fixture);
        ledger.apply(Map.of(fixture.base.above(1), BARRIER, fixture.base.above(2), LIGHT_OFF));
        fixture.expanded = true;
        fixture.light = false;
        fixture.defence = false;
        render(fixture);
    }
    private void compact(Fixture fixture) throws IOException {
        recover(volume(fixture.base.above(1), 2));
        fixture.expanded = fixture.light = fixture.defence = false;
        render(fixture);
    }
    private void render(Fixture fixture) {
        if (fixture.visual != null) fixture.visual.remove();
        Location location = center(fixture.base).add(0, plugin.getConfig().getDouble("prototype.visual-y-offset", 0.0), 0);
        fixture.visual = location.getWorld().spawn(location, ArmorStand.class, stand -> {
            stand.addScoreboardTag(TAG);
            stand.setVisible(false); stand.setGravity(false); stand.setInvulnerable(true);
            stand.setPersistent(false); stand.setSilent(true); stand.setCollidable(false);
            stand.setBasePlate(false); stand.setArms(false); stand.setCanMove(false); stand.setCanTick(false);
            ItemStack helmet = new ItemStack(Material.CARVED_PUMPKIN);
            ItemMeta meta = helmet.getItemMeta();
            meta.setItemModel(new NamespacedKey("infernolantern", fixture.expanded ? "prototype_expanded" : "prototype_compact"));
            helmet.setItemMeta(meta);
            stand.getEquipment().setHelmet(helmet);
            for (EquipmentSlot slot : EquipmentSlot.values()) for (ArmorStand.LockType lock : ArmorStand.LockType.values()) stand.addEquipmentLock(slot, lock);
        });
    }
    private void remove(Fixture fixture) throws IOException {
        if (fixture.visual != null) fixture.visual.remove();
        try { recover(volume(fixture.base, 3)); }
        finally { fixtures.remove(fixture.base); }
    }
    private void recover(Collection<Cell> cells) throws IOException {
        int conflicts = ledger.cleanup(cells);
        if (conflicts > 0) plugin.getLogger().warning("Prototype cleanup preserved " + conflicts + " externally changed cells; retained in cleanup manifest for inspection.");
    }
    public void close() {
        for (Fixture fixture : fixtures.values()) if (fixture.visual != null) fixture.visual.remove();
        try { recover(ledger.cells()); } catch (IOException failure) { plugin.getLogger().severe("Prototype cleanup failed; keep the cleanup manifest for recovery on next startup."); }
        fixtures.clear();
    }
    private Fixture find(Cell at) {
        for (Fixture fixture : fixtures.values()) if (fixture.base.world().equals(at.world()) && fixture.base.x() == at.x() && fixture.base.z() == at.z() && at.y() >= fixture.base.y() && at.y() < fixture.base.y() + (fixture.expanded ? 3 : 1)) return fixture;
        return null;
    }
    private void open(Player player, Fixture fixture) {
        Menu menu = new Menu(fixture.base);
        menu.inventory.setItem(0, item(Material.PISTON, fixture.expanded ? "Compact" : "Expand"));
        menu.inventory.setItem(3, item(Material.LANTERN, "Light: " + (fixture.light ? "ON" : "OFF")));
        menu.inventory.setItem(5, item(Material.IRON_SWORD, "Defence flag: " + (fixture.defence ? "ON" : "OFF") + " (no combat)"));
        menu.inventory.setItem(8, item(Material.BARRIER, "Remove prototype"));
        player.openInventory(menu.inventory);
    }
    private ItemStack item(Material material, String title) {
        ItemStack item = new ItemStack(material); ItemMeta meta = item.getItemMeta(); meta.setDisplayName(title); item.setItemMeta(meta); return item;
    }
    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !permitted(player)) { event.getWhoClicked().closeInventory(); return; }
        Fixture fixture = fixtures.get(menu.base);
        if (fixture == null || !player.getWorld().getUID().equals(menu.base.world()) || player.getLocation().distanceSquared(center(menu.base)) > 36) { player.closeInventory(); return; }
        if (event.getRawSlot() < 0 || event.getRawSlot() >= 9) return;
        try {
            switch (event.getRawSlot()) {
                case 0 -> { if (fixture.expanded) compact(fixture); else expand(fixture); }
                case 3 -> {
                    if (!fixture.expanded) throw new IllegalStateException("Expand before toggling light.");
                    ledger.apply(Map.of(fixture.base.above(2), fixture.light ? LIGHT_OFF : LIGHT_ON));
                    fixture.light = !fixture.light;
                }
                case 5 -> {
                    if (!fixture.expanded) throw new IllegalStateException("Expand before toggling defence.");
                    fixture.defence = !fixture.defence;
                }
                case 8 -> remove(fixture);
                default -> { return; }
            }
        } catch (Exception failure) { player.sendMessage("Prototype action stopped: " + failure.getMessage()); }
        Bukkit.getScheduler().runTask(plugin, () -> { if (fixtures.containsKey(menu.base) && permitted(player)) open(player, fixture); else player.closeInventory(); });
    }
    @EventHandler public void drag(InventoryDragEvent event) { if (event.getView().getTopInventory().getHolder() instanceof Menu) event.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void interact(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null || event.getHand() != EquipmentSlot.HAND) return;
        Fixture fixture = find(cell(event.getClickedBlock()));
        if (fixture == null) return;
        event.setCancelled(true);
        if (permitted(event.getPlayer())) open(event.getPlayer(), fixture);
    }
    @EventHandler public void interactEntity(PlayerInteractEntityEvent event) {
        if (!event.getRightClicked().getScoreboardTags().contains(TAG)) return;
        event.setCancelled(true);
        for (Fixture fixture : fixtures.values()) if (fixture.visual == event.getRightClicked() && permitted(event.getPlayer())) { open(event.getPlayer(), fixture); break; }
    }
    @EventHandler public void damage(EntityDamageEvent event) { if (event.getEntity().getScoreboardTags().contains(TAG)) event.setCancelled(true); }
    @EventHandler public void manipulate(PlayerArmorStandManipulateEvent event) { if (event.getRightClicked().getScoreboardTags().contains(TAG)) event.setCancelled(true); }
    @EventHandler public void breakBlock(BlockBreakEvent event) { if (ledger.cells().contains(cell(event.getBlock()))) event.setCancelled(true); }
    @EventHandler public void placeBlock(BlockPlaceEvent event) { if (ledger.cells().contains(cell(event.getBlock()))) event.setCancelled(true); }
    @EventHandler public void flow(BlockFromToEvent event) { if (ledger.cells().contains(cell(event.getToBlock()))) event.setCancelled(true); }
    @EventHandler public void entityBlock(EntityChangeBlockEvent event) { if (ledger.cells().contains(cell(event.getBlock()))) event.setCancelled(true); }
    @EventHandler public void explode(EntityExplodeEvent event) { event.blockList().removeIf(block -> ledger.cells().contains(cell(block))); }
    @EventHandler public void explode(BlockExplodeEvent event) { event.blockList().removeIf(block -> ledger.cells().contains(cell(block))); }
    @EventHandler public void piston(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> ledger.cells().contains(cell(block)) || ledger.cells().contains(cell(block.getRelative(event.getDirection()))))) event.setCancelled(true);
    }
    @EventHandler public void piston(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> ledger.cells().contains(cell(block)) || ledger.cells().contains(cell(block.getRelative(event.getDirection()))))) event.setCancelled(true);
    }
    @EventHandler public void unload(ChunkUnloadEvent event) {
        for (Fixture fixture : new ArrayList<>(fixtures.values())) if (inChunk(fixture.base, event.getChunk())) {
            try { remove(fixture); } catch (IOException failure) { plugin.getLogger().severe("Prototype chunk cleanup failed; retained manifest will recover on chunk load."); }
        }
    }
    @EventHandler public void load(ChunkLoadEvent event) {
        try { recover(ledger.cells().stream().filter(cell -> inChunk(cell, event.getChunk())).toList()); }
        catch (IOException failure) { plugin.getLogger().severe("Prototype chunk recovery failed; keep the cleanup manifest."); }
    }
    @EventHandler public void entitiesLoaded(EntitiesLoadEvent event) { event.getEntities().forEach(this::removeStale); }
    private boolean inChunk(Cell cell, Chunk chunk) { return cell.world().equals(chunk.getWorld().getUID()) && (cell.x() >> 4) == chunk.getX() && (cell.z() >> 4) == chunk.getZ(); }
    private void removeStale(Entity entity) { if (entity.getScoreboardTags().contains(TAG)) entity.remove(); }
}
