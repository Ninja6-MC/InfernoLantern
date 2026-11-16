package com.ninja6.infernolantern.prototype;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
}
