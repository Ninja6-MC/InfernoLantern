package com.ninja6.infernolantern.prototype;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** A disposable-fixture cleanup manifest; not the production tower repository. */
public final class PrototypeLedger {
    public record Cell(UUID world, int x, int y, int z) {
        public Cell above(int dy) { return new Cell(world, x, y + dy, z); }
    }
    public interface Blocks {
        boolean loaded(Cell cell);
        String read(Cell cell);
        void write(Cell cell, String data);
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
                if (input.readInt() != 0x494C5031) throw new IOException("Unknown prototype manifest format");
                int count = input.readInt();
                if (count < 0 || count > 10000) throw new IOException("Invalid prototype manifest size");
                for (int i = 0; i < count; i++) {
                    Cell cell = new Cell(new UUID(input.readLong(), input.readLong()), input.readInt(), input.readInt(), input.readInt());
                    String original = input.readUTF(), expected = input.readUTF();
                    int states = input.readInt();
                    if (states < 1 || states > 16) throw new IOException("Invalid prototype state count");
                    Set<String> owned = new HashSet<>();
                    for (int j = 0; j < states; j++) owned.add(input.readUTF());
                    if (entries.put(cell, new Entry(original, expected, owned)) != null) throw new IOException("Duplicate prototype cell");
                }
                if (input.read() != -1) throw new IOException("Trailing prototype manifest data");
            }
        }
    }

    public Set<Cell> cells() { return Set.copyOf(entries.keySet()); }

    /** Record every possible write before touching the world. All callers run on the server thread. */
    public void apply(Map<Cell, String> changes) throws IOException {
        Map<Cell, Entry> staged = new LinkedHashMap<>(entries);
        for (var change : changes.entrySet()) {
            Cell cell = change.getKey();
            if (!blocks.loaded(cell)) throw new IllegalStateException("Prototype chunk is not loaded");
            Entry old = entries.get(cell);
            String current = blocks.read(cell);
            if (old != null && !current.equals(old.expected())) throw new IllegalStateException("Prototype blocks changed; remove the fixture first");
            Set<String> owned = old == null ? new HashSet<>() : new HashSet<>(old.owned());
            owned.add(change.getValue());
            staged.put(cell, new Entry(old == null ? current : old.original(), change.getValue(), owned));
        }
        save(staged);
        entries.clear();
        entries.putAll(staged);
        for (var change : changes.entrySet()) blocks.write(change.getKey(), change.getValue());
    }

    /** Restore only recognizable fixture states; preserve and report external edits. */
    public int cleanup(Collection<Cell> cells) throws IOException {
        int conflicts = 0;
        Set<Cell> done = new HashSet<>();
        for (Cell cell : cells) {
            Entry entry = entries.get(cell);
            if (entry == null || !blocks.loaded(cell)) continue;
            String current = blocks.read(cell);
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
            output.writeInt(0x494C5031);
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
