package com.ninja6.infernolantern.repository;

import com.ninja6.infernolantern.prototype.PrototypeLedger.CleanupInspection;
import com.ninja6.infernolantern.repository.RepositoryException.NativeStartupHold;
import com.ninja6.infernolantern.runtime.NativeCatalogRuntime;
import com.ninja6.infernolantern.scheduling.WorldScheduler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static com.ninja6.infernolantern.repository.RepositoryRecords.*;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.junit.jupiter.api.Assertions.*;

class NativeHeldReservationsTest {
    @TempDir Path directory;
    private static final UUID WORLD = new UUID(0, 2);
    private static final UUID TOWER = new UUID(0, 3);
    private static final UUID ACTOR = new UUID(0, 4);
    private static final Set<Cell> FULL = Set.of(new Cell(WORLD, 4, 70, 4),
            new Cell(WORLD, 4, 71, 4), new Cell(WORLD, 4, 72, 4));

    private static final WorldScheduler NO_WORLD = new WorldScheduler() {
        @Override public <T> CompletableFuture<T> supply(Anchor anchor, Supplier<T> action) {
            throw new AssertionError("Held startup must not schedule chunk work");
        }
    };

    @Test void pendingUnionRetainsExclusiveOwnerAndPreservesFiles() throws Exception {
        var file = seed(false, false);
        byte[] database = Files.readAllBytes(file);
        byte[] marker = Files.readAllBytes(owner(file));
        var projection = new CompletableFuture<SqliteRepository.HeldReservations>();
        assertHold(SqliteRepository.openCatalogOnly(file, 4, projection::complete),
                NativeStartupHold.Reason.PENDING_OPERATIONS);
        var hold = get(projection);
        try {
            assertEquals(FULL, hold.cells());
            assertThrows(UnsupportedOperationException.class, () -> hold.cells().clear());
            try (var channel = FileChannel.open(owner(file), WRITE)) {
                assertThrows(OverlappingFileLockException.class, channel::tryLock);
            }
            assertThrows(ExecutionException.class, () -> get(SqliteRepository.openCatalogOnly(file, 4)));
        } finally {
            get(hold.closeAsync());
        }
        assertOwnerAvailable(file);
        assertPreserved(file, database, marker);
    }

    @Test void settledCompactionProtectsOnlyTheCurrentReservation() throws Exception {
        var file = seed(true, true);
        var projection = new CompletableFuture<SqliteRepository.HeldReservations>();
        assertHold(SqliteRepository.openCatalogOnly(file, 4, projection::complete),
                NativeStartupHold.Reason.RECORDED_STATE);
        var hold = get(projection);
        try {
            assertEquals(Set.of(new Cell(WORLD, 4, 70, 4)), hold.cells());
        } finally {
            get(hold.closeAsync());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DELETE FROM structure_cells WHERE y=72",
            "DELETE FROM structure_cells",
            "UPDATE structure_cells SET world='1-1-1-1-1' WHERE y=72",
            "UPDATE structure_cells SET x=4294967300 WHERE y=72",
            "UPDATE structure_cells SET x=4.25 WHERE y=72",
            "UPDATE towers SET hp=74",
            "UPDATE towers SET row_version=row_version+0.5",
            "UPDATE towers SET generation=generation+0.5",
            "UPDATE towers SET repair_after=repair_after+0.5",
            "UPDATE towers SET active_operation=NULL",
            "UPDATE manifest_components SET deployment_revision=deployment_revision+0.5",
            "UPDATE operations SET expected_version=expected_version+0.5 WHERE phase='INTENT'",
            "UPDATE operations SET expected_generation=expected_generation+0.5 WHERE phase='INTENT'",
            "UPDATE operations SET expected_revision=expected_revision+0.5 WHERE phase='INTENT'",
            "UPDATE operations SET effects_version=4294967297 WHERE phase='INTENT'",
            "UPDATE structure_cells SET operation_id=NULL",
            "UPDATE operations SET after_state=X'00' WHERE phase='INTENT'"
    })
    void unsafeProjectionPreservesHoldWithoutReturningAnyCells(String mutation) throws Exception {
        var file = seed(false, false);
        try (var connection = PrivateSqlite.open(file); var query = connection.createStatement()) {
            query.execute(mutation);
        }
        byte[] database = Files.readAllBytes(file);
        byte[] marker = Files.readAllBytes(owner(file));
        var projection = new AtomicReference<SqliteRepository.HeldReservations>();
        assertHold(SqliteRepository.openCatalogOnly(file, 4, projection::set), NativeStartupHold.Reason.PENDING_OPERATIONS);
        assertNull(projection.get());
        assertPreserved(file, database, marker);
    }

    @Test void readLimitAndSidecarNeverPublishScope() throws Exception {
        var file = seed(false, false);
        var projection = new AtomicReference<SqliteRepository.HeldReservations>();
        var sidecar = Files.writeString(file.resolveSibling("lanterns.db-wal"), "held WAL");
        assertHold(SqliteRepository.openCatalogOnly(file, 4, projection::set), NativeStartupHold.Reason.SIDECARS);
        assertNull(projection.get());
        assertEquals("held WAL", Files.readString(sidecar));
        Files.delete(sidecar);

        try (var connection = PrivateSqlite.open(file); var query = connection.createStatement()) {
            query.execute("""
                    WITH RECURSIVE sequence(n) AS (
                        VALUES(1) UNION ALL SELECT n+1 FROM sequence WHERE n < 1024
                    )
                    INSERT INTO towers
                    SELECT printf('00000000-0000-0000-0001-%012x', n), 'fixture', 75,100,
                           'PORTABLE',1,0,NULL,NULL,NULL,NULL,NULL,NULL,1,0,30,40,0,NULL
                    FROM sequence
                    """);
        }
        assertHold(SqliteRepository.openCatalogOnly(file, 4, projection::set), NativeStartupHold.Reason.PENDING_OPERATIONS);
        assertNull(projection.get());
    }

    @Test void knownEmptyProjectionDoesNotRetainTheOwner() throws Exception {
        var file = seed(true, true);
        try (var connection = PrivateSqlite.open(file); var query = connection.createStatement()) {
            query.execute("DELETE FROM structure_cells");
            query.execute("DELETE FROM manifest_components");
            query.execute("DELETE FROM operations");
            query.execute("DELETE FROM towers");
        }
        var received = new CompletableFuture<Set<Cell>>();
        var runtime = start(received, CompletableFuture.completedFuture(true));
        try {
            assertEquals(NativeCatalogRuntime.Result.UNAVAILABLE, get(runtime.startup()));
            assertEquals(Set.of(), get(received));
            assertTrue(runtime.status().contains("no reserved cells recorded at inspection"));
            // The failed startup future can complete just before its worker releases the inspection lock.
            awaitOwnerAvailable(file);
        } finally {
            get(runtime.stop());
        }
    }

    @Test void statusWaitsForPublicationAndReloadRemainsHeld() throws Exception {
        var file = seed(false, false);
        var received = new CompletableFuture<Set<Cell>>();
        var ownerAcknowledgment = new CompletableFuture<Boolean>();
        var runtime = start(received, ownerAcknowledgment);
        try {
            assertEquals(NativeCatalogRuntime.Result.UNAVAILABLE, get(runtime.startup()));
            assertEquals(FULL, get(received));
            assertTrue(runtime.status().contains("native reservation coverage unknown"));
            assertEquals(NativeCatalogRuntime.Result.UNAVAILABLE, get(runtime.reload(new CommitPermit(), () -> {
                throw new AssertionError("Held startup must not begin reload");
            })));
            ownerAcknowledgment.complete(true);
            assertTrue(runtime.status().contains("recorded native cells guarded"));
            assertFalse(runtime.status().contains(WORLD.toString()));
        } finally {
            get(runtime.stop());
        }
        assertOwnerAvailable(file);
    }

    @Test void rejectedOrLateOwnerPublicationCannotKeepTheLease() throws Exception {
        var file = seed(false, false);
        var received = new CompletableFuture<Set<Cell>>();
        var acknowledgment = new CompletableFuture<Boolean>();
        var first = start(received, acknowledgment);
        try {
            assertEquals(FULL, get(received));
            acknowledgment.complete(false);
            assertEquals(NativeCatalogRuntime.Result.UNAVAILABLE, get(first.startup()));
            assertTrue(first.status().contains("native reservation coverage unknown"));
        } finally {
            get(first.stop());
        }
        assertOwnerAvailable(file);

        var late = new CompletableFuture<Boolean>();
        var second = start(new CompletableFuture<>(), late);
        try {
            assertEquals(NativeCatalogRuntime.Result.UNAVAILABLE, get(second.startup()));
            get(second.stop());
            late.complete(true);
            assertEquals("closed; native gameplay unavailable", second.status());
            assertOwnerAvailable(file);
        } finally {
            get(second.stop());
        }
    }

    private NativeCatalogRuntime start(CompletableFuture<Set<Cell>> received, CompletableFuture<Boolean> ack) {
        return new NativeCatalogRuntime(directory, new CleanupInspection(true, true, Set.of()),
                Runnable::run, NO_WORLD, false, cells -> {
                    received.complete(cells);
                    return ack;
                });
    }

    private Path seed(boolean settled, boolean compacted) throws Exception {
        var parent = Files.createDirectory(directory.resolve("native-runtime"));
        var file = parent.resolve("lanterns.db");
        var repository = get(SqliteRepository.open(file, 4));
        try {
            get(repository.createVariant(new Variant("fixture", 0, VariantStatus.ACTIVE, new Document(1, "fixture"), 0)));
            get(repository.createTower(tower(Form.PORTABLE, 1, 0)));
            var compact = new State(tower(Form.COMPACT, 2, 1), null, List.of(component(70, "minecraft:barrier")));
            complete(repository, get(repository.beginIntent(intent(0, 1, 0, compact))));
            var expanded = new State(tower(Form.EXPANDED, 2, 2), null, List.of(component(70, "minecraft:barrier"),
                    component(71, "minecraft:barrier"), component(72, "minecraft:air")));
            var expansion = get(repository.beginIntent(intent(2, 2, 1, expanded)));
            if (settled) complete(repository, expansion);
            if (compacted) {
                var after = new State(tower(Form.COMPACT, 2, 3), null, compact.components());
                complete(repository, get(repository.beginIntent(intent(4, 2, 2, after))));
            }
        } finally {
            get(repository.closeAsync());
        }
        try (var connection = PrivateSqlite.open(file); var query = connection.createStatement()) {
            query.execute("PRAGMA application_id=" + Schema.NATIVE_APPLICATION_ID);
        }
        Files.writeString(owner(file), "native-stage-v1:" + UUID.randomUUID());
        return file;
    }

    private static Tower tower(Form form, long generation, long revision) {
        return new Tower(TOWER, "fixture", 75, 100, form, generation, revision,
                form == Form.EXPANDED ? ACTOR : null,
                form == Form.PORTABLE ? null : new Position(WORLD, 4, 70, 4, "north"), true, false, 30, 40);
    }

    private static Component component(int y, String expected) {
        return new Component("cell." + y, ComponentKind.BLOCK, WORLD, 4, y, 4,
                "minecraft:air", expected, null, null);
    }

    private static Intent intent(long version, long generation, long revision, State after) {
        return new Intent(UUID.randomUUID(), TOWER, version, generation, revision, "fixture-structure",
                after, new Document(1, "synthetic footprint"));
    }

    private static void complete(SqliteRepository repository, Operation operation) throws Exception {
        var applied = get(repository.advanceOperation(operation.intent().id(), operation.rowVersion(), Phase.INTENT,
                Phase.EFFECTS_APPLIED, "fixture owner verified"));
        get(repository.completeOperation(applied.intent().id(), applied.rowVersion(), "fixture complete"));
    }

    private static Path owner(Path file) { return file.resolveSibling("lanterns.db.owner"); }

    private static void assertOwnerAvailable(Path file) throws Exception {
        try (var channel = FileChannel.open(owner(file), WRITE); var lock = channel.tryLock()) {
            assertNotNull(lock);
        }
    }

    private static void awaitOwnerAvailable(Path file) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        try (var channel = FileChannel.open(owner(file), WRITE)) {
            while (System.nanoTime() < deadline) {
                try (var lock = channel.tryLock()) {
                    if (lock != null) return;
                } catch (OverlappingFileLockException stillClosing) {
                    // Wait for the asynchronous inspection owner to finish.
                }
                Thread.sleep(5);
            }
        }
        fail("The empty projection retained the native owner");
    }

    private static void assertPreserved(Path file, byte[] database, byte[] marker) throws Exception {
        assertArrayEquals(database, Files.readAllBytes(file));
        assertArrayEquals(marker, Files.readAllBytes(owner(file)));
        try (var files = Files.list(file.getParent())) {
            assertEquals(List.of("lanterns.db", "lanterns.db.owner"),
                    files.map(path -> path.getFileName().toString()).sorted().toList());
        }
    }

    private static void assertHold(CompletableFuture<SqliteRepository> future, NativeStartupHold.Reason reason) {
        var failure = assertThrows(ExecutionException.class, () -> get(future));
        assertEquals(reason, assertInstanceOf(NativeStartupHold.class, failure.getCause()).reason());
    }

    private static <T> T get(CompletableFuture<T> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }
}
