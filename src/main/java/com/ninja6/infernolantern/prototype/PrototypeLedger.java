package com.ninja6.infernolantern.prototype;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.function.UnaryOperator;

/** A disposable-fixture cleanup manifest; not the production tower repository. */
public final class PrototypeLedger {
    static final String FILENAME = "prototype-cleanup.bin";
    private static final int MAGIC = 0x494C5031;

    private static final int MAX_INSPECTION_BYTES = 8 * 1024 * 1024;

    public record CleanupInspection(boolean complete, boolean cellsKnown, Set<Cell> cells) {
        public CleanupInspection { cells = Set.copyOf(cells); }
    }

    /** Read the committed fixture manifest without touching worlds or using staged records. */
    public static CleanupInspection inspectCleanup(Path directory) {
        Path manifest = directory.resolve(FILENAME);
        boolean staged = !Files.notExists(manifest.resolveSibling(FILENAME + ".tmp"), LinkOption.NOFOLLOW_LINKS);
        if (Files.notExists(manifest, LinkOption.NOFOLLOW_LINKS)) {
            return new CleanupInspection(!staged, !staged, Set.of());
        }
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            return new CleanupInspection(false, false, Set.of());
        }
        try (var stream = Files.newInputStream(manifest, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = stream.readNBytes(MAX_INSPECTION_BYTES + 1);
            if (bytes.length > MAX_INSPECTION_BYTES) return new CleanupInspection(false, false, Set.of());
            try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
                Set<Cell> cells = read(input, UnaryOperator.identity()).keySet();
                return new CleanupInspection(!staged && cells.isEmpty(), true, cells);
            }
        } catch (IOException | SecurityException failure) {
            return new CleanupInspection(false, false, Set.of());
        }
    }

    public record Cell(UUID world, int x, int y, int z) {
        public Cell above(int dy) { return new Cell(world, x, y + dy, z); }
    }
    public interface Blocks {
        boolean loaded(Cell cell);
        String read(Cell cell);
        void write(Cell cell, String data);
        /** Resolve omitted defaults and property order without discarding block properties. */
        default String canonical(String data) { return data; }
    }
    private record Entry(String original, String expected, Set<String> owned) {}
    private final Path file;
    private final Blocks blocks;
    private final Map<Cell, Entry> entries = new LinkedHashMap<>();

    public PrototypeLedger(Path file, Blocks blocks) throws IOException {
        this.file = file;
        this.blocks = blocks;
        if (Files.exists(file)) {
            try (var input = new DataInputStream(Files.newInputStream(file))) {
                entries.putAll(read(input, blocks::canonical));
            }
        }
    }

    private static Map<Cell, Entry> read(DataInputStream input, UnaryOperator<String> canonical) throws IOException {
        if (input.readInt() != MAGIC) throw new IOException("Unknown prototype manifest format");
        int count = input.readInt();
        if (count < 0 || count > 10000) throw new IOException("Invalid prototype manifest size");
        Map<Cell, Entry> records = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            Cell cell = new Cell(new UUID(input.readLong(), input.readLong()), input.readInt(), input.readInt(), input.readInt());
            String original = canonical.apply(input.readUTF()), expected = canonical.apply(input.readUTF());
            int states = input.readInt();
            if (states < 1 || states > 16) throw new IOException("Invalid prototype state count");
            Set<String> owned = new HashSet<>();
            for (int j = 0; j < states; j++) owned.add(canonical.apply(input.readUTF()));
            if (records.put(cell, new Entry(original, expected, owned)) != null) throw new IOException("Duplicate prototype cell");
        }
        if (input.read() != -1) throw new IOException("Trailing prototype manifest data");
        return records;
    }

    public Set<Cell> cells() { return Set.copyOf(entries.keySet()); }
    public boolean contains(Cell cell) { return entries.containsKey(cell); }

    /** Record every possible write before touching the world. All callers run on the server thread. */
    public void apply(Map<Cell, String> changes) throws IOException {
        Map<Cell, Entry> staged = new LinkedHashMap<>(entries);
        Map<Cell, String> writes = new LinkedHashMap<>();
        for (var change : changes.entrySet()) {
            Cell cell = change.getKey();
            if (!blocks.loaded(cell)) throw new IllegalStateException("Prototype chunk is not loaded");
            Entry old = entries.get(cell);
            String current = blocks.canonical(blocks.read(cell));
            if (old != null && !current.equals(old.expected())) throw new IllegalStateException("Prototype blocks changed; remove the fixture first");
            Set<String> owned = old == null ? new HashSet<>() : new HashSet<>(old.owned());
            String state = blocks.canonical(change.getValue());
            owned.add(state);
            staged.put(cell, new Entry(old == null ? current : old.original(), state, owned));
            writes.put(cell, state);
        }
        save(staged);
        entries.clear();
        entries.putAll(staged);
        for (var change : writes.entrySet()) blocks.write(change.getKey(), change.getValue());
    }

    /** Restore only recognizable fixture states; preserve and report external edits. */
    public int cleanup(Collection<Cell> cells) throws IOException {
        int conflicts = 0;
        Set<Cell> done = new HashSet<>();
        for (Cell cell : cells) {
            Entry entry = entries.get(cell);
            if (entry == null || !blocks.loaded(cell)) continue;
            String current = blocks.canonical(blocks.read(cell));
            if (entry.owned().contains(current)) blocks.write(cell, entry.original());
            else if (!current.equals(entry.original())) { conflicts++; continue; }
            done.add(cell);
        }
        Map<Cell, Entry> remaining = new LinkedHashMap<>(entries);
        done.forEach(remaining::remove);
        save(remaining);
        entries.clear();
        entries.putAll(remaining);
        return conflicts;
    }

    private void save(Map<Cell, Entry> data) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (var output = new DataOutputStream(Files.newOutputStream(temporary))) {
            output.writeInt(MAGIC);
            output.writeInt(data.size());
            for (var pair : data.entrySet()) {
                Cell cell = pair.getKey();
                Entry entry = pair.getValue();
                output.writeLong(cell.world().getMostSignificantBits());
                output.writeLong(cell.world().getLeastSignificantBits());
                output.writeInt(cell.x()); output.writeInt(cell.y()); output.writeInt(cell.z());
                output.writeUTF(entry.original()); output.writeUTF(entry.expected());
                output.writeInt(entry.owned().size());
                for (String state : new TreeSet<>(entry.owned())) output.writeUTF(state);
            }
        }
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
