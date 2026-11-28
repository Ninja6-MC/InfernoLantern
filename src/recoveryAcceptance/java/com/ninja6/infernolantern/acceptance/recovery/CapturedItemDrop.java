package com.ninja6.infernolantern.acceptance.recovery;

import com.ninja6.infernolantern.item.PaperItemIdentity;
import com.ninja6.infernolantern.recovery.MatchedItemSaves;
import com.ninja6.infernolantern.recovery.PaperRecoveryPort.GroundEvidence;
import com.ninja6.infernolantern.recovery.RecoveryEnvelope;
import com.ninja6.infernolantern.repository.RepositoryRecords.Ground;
import com.ninja6.infernolantern.repository.RepositoryRecords.Operation;
import com.ninja6.infernolantern.repository.RepositoryRecords.TowerSnapshot;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.BitSet;
import java.util.HashSet;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.inventory.CraftItemStack;

/** Physical inputs for the fixture's one journal. The runner only seals closed copies. */
final class CapturedItemDrop {
    private static final int DATA_VERSION = 5023;
    private static final int SECTOR = 4096;
    private static final int NBT_LIMIT = 4 * 1024 * 1024;
    private static final int REGION_LIMIT = 16 * 1024 * 1024;
    private static final String DELIVERY = PaperItemIdentity.OPERATION.toString();
    private static final String STAGED = "infernolantern:item_staged";
    private static final String HASH = "([a-f0-9]{64})\n";
    private static final Pattern MANIFEST = Pattern.compile("capture=([a-f0-9-]{36})\n"
            + "database=" + HASH + "world=" + HASH + "player=" + HASH + "entities=" + HASH);

    record Seal(UUID captureId, String database, String world, String player, String entities) {
        MatchedItemSaves.Source source(String digest) { return new MatchedItemSaves.Source(captureId, digest); }
    }

    record SavedDrop(Ground reference, double x, double y, double z, boolean staged, boolean pickup,
                     boolean invulnerable, boolean unlimited, boolean gravity, int ticksLived, CompoundTag item) {
        GroundEvidence canonical(World world) {
            return new GroundEvidence(reference.entity(), itemBytes(item, world), reference.world(), reference.chunkX(),
                    reference.chunkZ(), x, y, z, staged, pickup, invulnerable, unlimited, gravity, ticksLived,
                    reference.deliveryOperation().toString());
        }
    }

    record PhysicalInputs(RecoveryEnvelope.Context context, Ground reference, CompoundTag slot, SavedDrop drop) {}

    private CapturedItemDrop() {}

    static Seal readSeal(Path folder, String digest) throws IOException {
        byte[] bytes = CapturedPaperFiles.sealed(folder, Path.of("capture.txt"), 512, digest);
        var match = MANIFEST.matcher(new String(bytes, StandardCharsets.US_ASCII));
        if (!match.matches()) throw new IOException("Invalid fixture capture manifest");
        return new Seal(UUID.fromString(match.group(1)), match.group(2), match.group(3), match.group(4), match.group(5));
    }

    static PhysicalInputs inspect(Path folder, Operation operation, Seal seal) throws IOException, DataFormatException {
        var context = RecoveryEnvelope.decode(operation.intent().effects()).orElseThrow();
        if (!operation.pending() || !context.kind().equals("item-drop") || !RecoveryEnvelope.supports(operation, context)
                || context.inventory().slot() > 35) throw new IOException("Unsupported fixture journal");
        Ground target = operation.intent().after().ground();
        CompoundTag world = gzip(folder, Path.of("data/paper"), Path.of("metadata.dat"), 64 * 1024, seal.world());
        if (!target.world().equals(uuid(world.getCompound("data").orElseThrow(), "uuid"))) {
            throw new IOException("Wrong captured world");
        }
        var inventory = context.inventory();
        CompoundTag player = gzip(folder, Path.of("players"), Path.of(inventory.actor() + ".dat"),
                1024 * 1024, seal.player());
        if (!inventory.actor().equals(uuid(player, "UUID")) || !target.world().equals(worldId(player))) {
            throw new IOException("Wrong saved player or world");
        }
        CompoundTag slot = findSlot(player, inventory.slot());
        Path region = Path.of("r." + Math.floorDiv(target.chunkX(), 32) + "." + Math.floorDiv(target.chunkZ(), 32) + ".mca");
        byte[] raw = CapturedPaperFiles.sealed(folder.resolve("entities"), region, REGION_LIMIT, seal.entities());
        CompoundTag chunk = pinned(CapturedPaperFiles.compound(entry(raw, target)));
        int[] anchor = chunk.getIntArray("Position").orElseThrow();
        if (anchor.length != 2 || anchor[0] != target.chunkX() || anchor[1] != target.chunkZ()) {
            throw new IOException("Wrong entity record position");
        }
        return new PhysicalInputs(context, target, slot, findDrop(chunk, target));
    }

    static MatchedItemSaves.Capture pair(Seal seal, Operation operation, TowerSnapshot tower,
            PhysicalInputs inputs, World world) {
        if (!Bukkit.isPrimaryThread() || !inputs.reference().world().equals(world.getUID())) {
            throw new IllegalStateException("Fixture owner scope unavailable");
        }
        var inventory = inputs.context().inventory();
        var database = new MatchedItemSaves.Database(seal.source(seal.database()), operation, tower);
        var player = new MatchedItemSaves.PlayerSlot(seal.source(seal.player()), inventory.actor(), inventory.slot(),
                inputs.slot() == null ? "empty" : itemBytes(inputs.slot(), world));
        var entity = new MatchedItemSaves.GroundEntity(seal.source(seal.entities()), inputs.reference(),
                inputs.drop() == null ? null : inputs.drop().canonical(world));
        // The runner controls all movement in this synthetic fixture. File digests do not prove custody.
        var scope = new MatchedItemSaves.WorldScope(seal.source(seal.world()), inputs.context().anchor(),
                MatchedItemSaves.Custody.EXACT);
        return new MatchedItemSaves.Capture(database, scope, player, entity);
    }

    private static CompoundTag gzip(Path root, Path directory, Path file, int limit, String digest)
            throws IOException, DataFormatException {
        return pinned(CapturedPaperFiles.compound(CapturedPaperFiles.inflateGzip(
                CapturedPaperFiles.sealed(root.resolve(directory), file, limit, digest))));
    }

    private static CompoundTag pinned(CompoundTag value) throws IOException {
        if (!(value.get("DataVersion") instanceof IntTag version) || version.intValue() != DATA_VERSION
                || SharedConstants.getCurrentVersion().dataVersion().version() != DATA_VERSION) {
            throw new IOException("Unsupported fixture data version");
        }
        return value;
    }

    private static CompoundTag findSlot(CompoundTag player, int selected) throws IOException {
        if (!(player.get("Inventory") instanceof ListTag inventory) || inventory.size() > 41) {
            throw new IOException("Invalid captured inventory");
        }
        var seen = new BitSet(256);
        CompoundTag result = null;
        for (var value : inventory) {
            if (!(value instanceof CompoundTag entry) || !(entry.get("Slot") instanceof ByteTag index)) {
                throw new IOException("Invalid captured inventory slot");
            }
            int slot = Byte.toUnsignedInt(index.byteValue());
            if (seen.get(slot)) throw new IOException("Duplicate captured inventory slot");
            seen.set(slot);
            if (slot == selected) result = entry;
        }
        return result;
    }

    private static SavedDrop findDrop(CompoundTag chunk, Ground target) throws IOException {
        if (!(chunk.get("Entities") instanceof ListTag entities) || entities.size() > 1024) {
            throw new IOException("Invalid captured entity list");
        }
        var seen = new HashSet<UUID>();
        CompoundTag result = null;
        for (var value : entities) {
            if (!(value instanceof CompoundTag entry) || !(entry.get("id") instanceof StringTag kind)) {
                throw new IOException("Invalid captured entity");
            }
            UUID id = uuid(entry, "UUID");
            if (!seen.add(id)) throw new IOException("Duplicate captured entity");
            boolean delivery = entry.getCompound("BukkitValues").flatMap(pdc -> pdc.getString(DELIVERY))
                    .filter(target.deliveryOperation().toString()::equals).isPresent();
            if (target.entity().equals(id)) {
                if (!delivery || !kind.value().equals("minecraft:item")) throw new IOException("Wrong captured target");
                result = entry;
            } else if (delivery) {
                throw new IOException("Ambiguous captured delivery marker");
            }
        }
        return result == null ? null : savedDrop(result, target);
    }

    private static SavedDrop savedDrop(CompoundTag entity, Ground target) throws IOException {
        if (!worldId(entity).equals(target.world()) || !(entity.get("Pos") instanceof ListTag pos) || pos.size() != 3
                || !(pos.get(0) instanceof DoubleTag x) || !(pos.get(1) instanceof DoubleTag y)
                || !(pos.get(2) instanceof DoubleTag z) || !Double.isFinite(x.doubleValue())
                || !Double.isFinite(y.doubleValue()) || !Double.isFinite(z.doubleValue())
                || Math.floor(x.doubleValue() / 16) != target.chunkX() || Math.floor(z.doubleValue() / 16) != target.chunkZ()
                || !(entity.get("Age") instanceof ShortTag age) || !(entity.get("PickupDelay") instanceof ShortTag delay)
                || !(entity.get("Spigot.ticksLived") instanceof IntTag elapsed) || elapsed.intValue() < 0
                || !(entity.get("Item") instanceof CompoundTag item)) {
            throw new IOException("Invalid captured ground representation");
        }
        var pdc = entity.getCompound("BukkitValues").orElseThrow();
        boolean staged = pdc.contains(STAGED);
        if (staged && (!(pdc.get(STAGED) instanceof ByteTag tag) || tag.byteValue() != 1)) {
            throw new IOException("Invalid captured staged marker");
        }
        return new SavedDrop(target, x.doubleValue(), y.doubleValue(), z.doubleValue(), staged,
                delay.shortValue() != Short.MAX_VALUE, flag(entity, "Invulnerable"),
                age.shortValue() == Short.MIN_VALUE, !flag(entity, "NoGravity"), elapsed.intValue(), item);
    }

    private static boolean flag(CompoundTag tag, String field) throws IOException {
        if (!tag.contains(field)) return false;
        if (!(tag.get(field) instanceof ByteTag value)) throw new IOException("Invalid captured flag");
        return value.byteValue() != 0;
    }

    private static UUID uuid(CompoundTag tag, String field) throws IOException {
        int[] words = tag.getIntArray(field).orElseThrow();
        if (words.length != 4) throw new IOException("Invalid captured UUID");
        return new UUID((long) words[0] << 32 | Integer.toUnsignedLong(words[1]),
                (long) words[2] << 32 | Integer.toUnsignedLong(words[3]));
    }

    private static UUID worldId(CompoundTag tag) throws IOException {
        if (!(tag.get("WorldUUIDMost") instanceof LongTag most) || !(tag.get("WorldUUIDLeast") instanceof LongTag least)) {
            throw new IOException("Missing captured world UUID");
        }
        return new UUID(most.longValue(), least.longValue());
    }

    private static byte[] entry(byte[] bytes, Ground target) throws IOException, DataFormatException {
        if (bytes.length < 2 * SECTOR) throw new IOException("Truncated region header");
        int sectors = (bytes.length + SECTOR - 1) / SECTOR;
        var region = ByteBuffer.wrap(bytes);
        var used = new BitSet(sectors);
        for (int index = 0; index < 1024; index++) {
            int record = region.getInt(index * Integer.BYTES);
            if (record == 0) continue;
            int start = record >>> 8;
            int count = record & 255;
            int next = used.nextSetBit(start);
            if (start < 2 || count == 0 || start + count > sectors || (long) start * SECTOR + 5 > bytes.length
                    || next >= 0 && next < start + count) throw new IOException("Invalid region allocation");
            used.set(start, start + count);
        }
        int index = Math.floorMod(target.chunkZ(), 32) * 32 + Math.floorMod(target.chunkX(), 32);
        int record = region.getInt(index * Integer.BYTES);
        if (record == 0) throw new IOException("Selected entity chunk was not saved");
        int start = (record >>> 8) * SECTOR;
        int length = region.getInt(start);
        // Paper can leave the crash file's last sector partially populated.
        if (length < 2 || length > (record & 255) * SECTOR - Integer.BYTES
                || length > bytes.length - start - Integer.BYTES || region.get(start + 4) != 2) {
            throw new IOException("Invalid or unsupported entity record");
        }
        var inflate = new Inflater();
        try (var contents = new ByteArrayOutputStream()) {
            inflate.setInput(bytes, start + 5, length - 1);
            byte[] block = new byte[8192];
            while (!inflate.finished()) {
                int count = inflate.inflate(block);
                if (count == 0 && !inflate.finished() || contents.size() + count > NBT_LIMIT) {
                    throw new IOException("Truncated or oversized entity record");
                }
                contents.write(block, 0, count);
            }
            if (inflate.getBytesRead() != length - 1) throw new IOException("Unexpected trailing region data");
            return contents.toByteArray();
        } finally {
            inflate.end();
        }
    }

    private static String itemBytes(CompoundTag stack, World world) {
        var registry = ((CraftWorld) world).getHandle().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        var nativeItem = net.minecraft.world.item.ItemStack.CODEC.parse(registry, stack).getOrThrow();
        return Base64.getEncoder().encodeToString(CraftItemStack.asBukkitMirror(nativeItem).serializeAsBytes());
    }
}
