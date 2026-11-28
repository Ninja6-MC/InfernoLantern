package com.ninja6.infernolantern.acceptance.recovery;

import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionFileType;
import ca.spottedleaf.moonrise.patches.chunk_system.level.entity.ChunkEntitySlices;
import com.ninja6.infernolantern.scheduling.WorldScheduler.Anchor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.craftbukkit.CraftChunk;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

public final class ItemDropAcceptancePlugin extends JavaPlugin {
    private static final int X = -8;
    private static final int Y = -55;
    private static final int Z = 8;
    private static final int SLOT = 2;
    private static final Path FIXTURE = Path.of("fixture");

    @Override
    public void onEnable() {
        if (!"127.0.0.1".equals(getServer().getIp()) || getServer().getOnlineMode()
                || !Files.isRegularFile(FIXTURE.resolve("disposable-test"), LinkOption.NOFOLLOW_LINKS)) {
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) return false;
        try {
            World world = Bukkit.getWorlds().getFirst();
            world.setAutoSave(false);
            world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
            Player player = Objects.requireNonNull(Bukkit.getPlayerExact("FixturePlayer"));
            Chunk chunk = world.getChunkAt(X >> 4, Z >> 4);
            chunk.addPluginChunkTicket(this);
            ServerLevel level = ((CraftWorld) world).getHandle();
            switch (args[0]) {
                case "prepare" -> {
                    world.getBlockAt(X, Y, Z).setType(Material.COPPER_BLOCK, false);
                    player.getInventory().setItem(SLOT, FixtureRecovery.source(UUID.fromString(args[1])));
                    // A non-token control guarantees the stored entity chunk explicitly exists.
                    world.dropItem(new Location(world, X + 2.5, Y + 2.0, Z + 2.5),
                        new ItemStack(Material.STONE), item -> {
                            item.setVelocity(new Vector());
                            item.setGravity(false);
                            item.setCanPlayerPickup(false);
                            item.setInvulnerable(true);
                            item.setUnlimitedLifetime(true);
                        });
                }
                case "remove" -> player.getInventory().setItem(SLOT, null);
                case "stage" -> {
                    UUID tower = UUID.fromString(args[1]);
                    UUID operation = UUID.fromString(args[2]);
                    Item item = FixtureRecovery.stage(world, new Location(world, X + 0.5, Y + 1.0, Z + 0.5), tower, operation);
                    sender.sendMessage("RECOVERY-STAGE " + item.getUniqueId());
                }
                case "seed" -> {
                    UUID tower = UUID.fromString(args[1]);
                    UUID operation = UUID.fromString(args[2]);
                    UUID entity = UUID.fromString(args[3]);
                    UUID session = UUID.fromString(args[4]);
                    Anchor anchor = new Anchor(world.getUID(), chunk.getX(), chunk.getZ());
                    FixtureRecovery.seed(FIXTURE.resolve("recovery.db"), player, SLOT, tower, operation, entity, session, anchor)
                        .whenComplete((unused, error) -> callback(sender, "seed", null, error));
                    return true;
                }

                case "reconcile" -> {
                    FixtureRecovery.reconcile(this, player, FIXTURE, UUID.fromString(args[1]), args[2])
                        .whenComplete((result, error) -> callback(sender, "reconcile", result, error));
                    return true;
                }
                case "world" -> {
                    CompoundTag snapshot = SerializableChunkData.copyOf(level,
                        ((CraftChunk) chunk).getHandle(ChunkStatus.FULL)).write();
                    persist(level, chunk, snapshot, RegionFileType.CHUNK_DATA);
                }
                case "entity" -> {
                    var entities = Arrays.stream(chunk.getEntities()).filter(Item.class::isInstance)
                        .map(entity -> ((CraftEntity) entity).getHandle()).toList();
                    CompoundTag snapshot = ChunkEntitySlices.saveEntityChunk(entities,
                        new ChunkPos(chunk.getX(), chunk.getZ()), level);
                    persist(level, chunk, snapshot, RegionFileType.ENTITY_DATA);
                }
                case "player" -> player.saveData();
                default -> throw new IllegalArgumentException(args[0]);
            }
            sender.sendMessage("CRASH-ACK " + args[0]);
        } catch (Throwable error) {
            callback(sender, args[0], null, error);
        }
        return true;
    }

    private static void callback(CommandSender sender, String action, String result, Throwable error) {
        if (error != null) {
            error.printStackTrace();
            sender.sendMessage("CRASH-ERROR");
            return;
        }
        if (result != null) sender.sendMessage(result);
        sender.sendMessage("CRASH-ACK " + action);
    }

    private static void persist(ServerLevel level, Chunk chunk, CompoundTag snapshot,
                                RegionFileType type) throws java.io.IOException {
        MoonriseRegionFileIO.scheduleSave(level, chunk.getX(), chunk.getZ(), snapshot, type);
        MoonriseRegionFileIO.flush(level, type);
        MoonriseRegionFileIO.flushRegionStorages(level, type);
    }
}
