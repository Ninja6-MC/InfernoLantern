package com.ninja6.infernolantern.prototype;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.DataOutputStream;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PrototypeLedgerTest {
    @TempDir Path directory;
    final PrototypeLedger.Cell base = new PrototypeLedger.Cell(new UUID(0, 1), 4, 70, 4);
    final FakeBlocks world = new FakeBlocks();
    static class FakeBlocks implements PrototypeLedger.Blocks {
        final Map<PrototypeLedger.Cell, String> data = new HashMap<>();
        boolean loaded = true;
        int writes, failAt = -1;
        public boolean loaded(PrototypeLedger.Cell cell) { return loaded; }
        public String read(PrototypeLedger.Cell cell) { return data.getOrDefault(cell, "air"); }
        public void write(PrototypeLedger.Cell cell, String value) {
            if (++writes == failAt) throw new IllegalStateException("Interrupted world write");
            data.put(cell, value);
        }
    }
    /** Model the parser/default expansion observed at the Paper block-data boundary. */
    static class CanonicalBlocks extends FakeBlocks {
        @Override public String canonical(String value) {
            return switch (value) {
                case "air" -> "minecraft:air";
                case "minecraft:barrier" -> "minecraft:barrier[waterlogged=false]";
                case "minecraft:light[waterlogged=false,level=15]" -> "minecraft:light[level=15,waterlogged=false]";
                default -> value;
            };
        }
        @Override public String read(PrototypeLedger.Cell cell) { return canonical(super.read(cell)); }
        @Override public void write(PrototypeLedger.Cell cell, String value) { super.write(cell, canonical(value)); }
    }
    private PrototypeLedger ledger(FakeBlocks blocks) throws Exception {
        return new PrototypeLedger(directory.resolve("cleanup.bin"), blocks);
    }
    /** Exact ILP1 layout written before canonicalization, including raw shorthand states. */
    private void legacyManifest(List<PrototypeLedger.Cell> cells, String original, String expected, String... owned) throws Exception {
        try (var out = new DataOutputStream(Files.newOutputStream(directory.resolve("cleanup.bin")))) {
            out.writeInt(0x494C5031); out.writeInt(cells.size());
            for (var cell : cells) {
                out.writeLong(cell.world().getMostSignificantBits()); out.writeLong(cell.world().getLeastSignificantBits());
                out.writeInt(cell.x()); out.writeInt(cell.y()); out.writeInt(cell.z());
                out.writeUTF(original); out.writeUTF(expected); out.writeInt(owned.length);
                for (String state : owned) out.writeUTF(state);
            }
        }
    }
    PrototypeLedger ledger() throws Exception { return new PrototypeLedger(directory.resolve("cleanup.bin"), world); }
    @Test void restartRestoresOriginalsAfterPartialWorldWrite() throws Exception {
        var ledger = ledger();
        world.failAt = 2;
        var changes = new LinkedHashMap<PrototypeLedger.Cell, String>();
        changes.put(base, "barrier"); changes.put(base.above(1), "barrier");
        assertThrows(IllegalStateException.class, () -> ledger.apply(changes));
        var restarted = ledger();
        assertEquals(0, restarted.cleanup(restarted.cells()));
        assertEquals("air", world.read(base));
        assertTrue(ledger().cells().isEmpty());
    }
    @Test void preservesExternalEditsAndReportsConflict() throws Exception {
        var ledger = ledger(); ledger.apply(Map.of(base, "barrier"));
        world.data.put(base, "diamond_block");
        assertEquals(1, ledger.cleanup(ledger.cells()));
        assertEquals("diamond_block", world.read(base));
    }
    @Test void unloadedRecoveryWaitsWithoutLoadingChunks() throws Exception {
        var ledger = ledger(); ledger.apply(Map.of(base, "barrier")); world.loaded = false;
        ledger.cleanup(ledger.cells()); assertEquals(1, ledger.cells().size());
        world.loaded = true; ledger.cleanup(ledger.cells()); assertTrue(ledger.cells().isEmpty());
    }
    @Test void repeatedLightTransitionsRetainOriginalSnapshot() throws Exception {
        var ledger = ledger(); ledger.apply(Map.of(base, "light0"));
        ledger.apply(Map.of(base, "light15")); ledger.apply(Map.of(base, "light0"));
        ledger.cleanup(ledger.cells()); assertEquals("air", world.read(base));
    }
    @Test void changedFixtureRejectsFurtherMutation() throws Exception {
        var ledger = ledger(); ledger.apply(Map.of(base, "barrier")); world.data.put(base, "stone");
        assertThrows(IllegalStateException.class, () -> ledger.apply(Map.of(base, "light15")));
        assertEquals("stone", world.read(base));
    }
    @Test void manifestFailurePreventsWorldWrite() throws Exception {
        Path parent = directory.resolve("blocked"); Files.writeString(parent, "not a directory");
        var ledger = new PrototypeLedger(parent.resolve("cleanup.bin"), world);
        assertThrows(Exception.class, () -> ledger.apply(Map.of(base, "barrier")));
        assertEquals(0, world.writes);
    }
    @Test void invalidManifestFailsClosed() throws Exception {
        Files.write(directory.resolve("cleanup.bin"), new byte[]{1, 2, 3});
        assertThrows(Exception.class, this::ledger);
    }
    @Test void canonicalReadsAllowRepeatedExpansionCompactionAndLightChanges() throws Exception {
        var blocks = new CanonicalBlocks();
        var ledger = ledger(blocks);
        ledger.apply(Map.of(base, "minecraft:barrier"));
        for (int i = 0; i < 2; i++) {
            ledger.apply(Map.of(base, "minecraft:barrier", base.above(1), "minecraft:barrier",
                    base.above(2), "minecraft:light[level=0,waterlogged=false]"));
            ledger.apply(Map.of(base.above(2), "minecraft:light[waterlogged=false,level=15]"));
            ledger.apply(Map.of(base.above(2), "minecraft:light[level=0,waterlogged=false]"));
            assertEquals(0, ledger.cleanup(List.of(base.above(1), base.above(2))));
            assertEquals("minecraft:barrier[waterlogged=false]", blocks.read(base));
        }
        assertEquals(0, ledger.cleanup(ledger.cells()));
        assertTrue(ledger.cells().isEmpty());
        assertEquals("minecraft:air", blocks.read(base));
    }
    @Test void legacyManifestRecoversPartialWritesAfterAnUnloadedChunkReturns() throws Exception {
        var cells = List.of(base, base.above(1), base.above(2));
        legacyManifest(cells, "minecraft:air", "minecraft:barrier", "minecraft:barrier");
        var blocks = new CanonicalBlocks();
        blocks.data.put(base, "minecraft:barrier[waterlogged=false]");
        blocks.data.put(base.above(1), "minecraft:barrier[waterlogged=false]");
        // The third recorded write never reached the world before interruption.
        blocks.loaded = false;
        var restarted = ledger(blocks);
        assertEquals(0, restarted.cleanup(restarted.cells()));
        assertEquals(3, restarted.cells().size());
        assertEquals(0, blocks.writes);
        blocks.loaded = true;
        assertEquals(0, restarted.cleanup(restarted.cells()));
        assertTrue(ledger(blocks).cells().isEmpty());
        for (var cell : cells) assertEquals("minecraft:air", blocks.read(cell));
    }
    @Test void legacyExpectedStateAllowsFurtherCanonicalWrites() throws Exception {
        legacyManifest(List.of(base), "minecraft:air", "minecraft:barrier", "minecraft:barrier");
        var blocks = new CanonicalBlocks();
        blocks.data.put(base, "minecraft:barrier[waterlogged=false]");
        var restarted = ledger(blocks);
        restarted.apply(Map.of(base, "minecraft:light[level=15,waterlogged=false]"));
        assertEquals(0, restarted.cleanup(restarted.cells()));
        assertEquals("minecraft:air", blocks.read(base));
    }
    @Test void legacyOwnedStatesStillRecoverEarlierWritesBeforeExpectedTransition() throws Exception {
        legacyManifest(List.of(base), "minecraft:air", "minecraft:light[level=15,waterlogged=false]",
                "minecraft:barrier", "minecraft:light[level=15,waterlogged=false]");
        var blocks = new CanonicalBlocks();
        blocks.data.put(base, "minecraft:barrier[waterlogged=false]");
        var restarted = ledger(blocks);
        assertEquals(0, restarted.cleanup(restarted.cells()));
        assertTrue(restarted.cells().isEmpty());
        assertEquals("minecraft:air", blocks.read(base));
    }
    @Test void legacyManifestDoesNotOwnWaterloggedBarrierOrDifferentBlocks() throws Exception {
        legacyManifest(List.of(base, base.above(1)), "minecraft:air", "minecraft:barrier", "minecraft:barrier");
        var blocks = new CanonicalBlocks();
        blocks.data.put(base, "minecraft:barrier[waterlogged=true]");
        blocks.data.put(base.above(1), "minecraft:diamond_block");
        var restarted = ledger(blocks);
        assertEquals(2, restarted.cleanup(restarted.cells()));
        assertEquals(0, blocks.writes);
        assertEquals(2, ledger(blocks).cells().size());
        assertEquals("minecraft:barrier[waterlogged=true]", blocks.read(base));
        assertEquals("minecraft:diamond_block", blocks.read(base.above(1)));
        assertThrows(IllegalStateException.class, () -> restarted.apply(Map.of(base, "minecraft:barrier")));
    }
    @Test void canonicalWritesKeepRecoveryAfterAnInterruptedWorldWrite() throws Exception {
        var blocks = new CanonicalBlocks();
        blocks.failAt = 2;
        var ledger = ledger(blocks);
        var changes = new LinkedHashMap<PrototypeLedger.Cell, String>();
        changes.put(base, "minecraft:barrier"); changes.put(base.above(1), "minecraft:barrier");
        assertThrows(IllegalStateException.class, () -> ledger.apply(changes));
        var restarted = ledger(blocks);
        assertEquals(0, restarted.cleanup(restarted.cells()));
        assertTrue(ledger(blocks).cells().isEmpty());
        assertEquals("minecraft:air", blocks.read(base));
    }

}
