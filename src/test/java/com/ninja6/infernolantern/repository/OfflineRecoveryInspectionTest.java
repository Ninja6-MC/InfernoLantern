package com.ninja6.infernolantern.repository;

import com.ninja6.infernolantern.recovery.RecoveryEnvelope;
import com.ninja6.infernolantern.scheduling.WorldScheduler.Anchor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.ninja6.infernolantern.repository.RepositoryRecords.*;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.junit.jupiter.api.Assertions.*;

class OfflineRecoveryInspectionTest {
    @TempDir Path root;
    private final UUID actor = UUID.randomUUID();
    private final UUID session = UUID.randomUUID();
    private final UUID world = UUID.randomUUID();
    private final UUID entity = UUID.randomUUID();
    private final UUID tower = UUID.randomUUID();
    private final UUID operation = UUID.randomUUID();

    @ParameterizedTest
    @EnumSource(value = Phase.class, names = {"INTENT", "EFFECTS_APPLIED", "RECONCILING"})
    void supportsExactPendingJournalWithoutChangingTheCapture(Phase phase) throws Exception {
        var capture = fixture(phase, recipe());
        var database = capture.resolve("lanterns.db");
        var owner = capture.resolve("lanterns.db.owner");
        byte[] originalDatabase = Files.readAllBytes(database);
        byte[] originalOwner = Files.readAllBytes(owner);
        var report = inspect(capture);
        assertEquals(0, report.status());
        assertTrue(report.output().contains("Journal phase: " + phase));
        assertTrue(report.output().contains("Frozen tower row: matches pending journal"));
        assertTrue(report.output().contains("Item-drop recipe: supported"));
        assertTrue(report.output().contains("Minecraft saves: unexamined"));
        assertNoPrivateValues(report);

        report = inspect(capture, "--locators");
        assertEquals(0, report.status());
        assertTrue(report.output().contains("Player UUID: " + actor));
        assertTrue(report.output().contains("Player slot: 4"));
        assertTrue(report.output().contains("World UUID: " + world));
        assertTrue(report.output().contains("Chunk X: -7"));
        assertTrue(report.output().contains("Chunk Z: 11"));
        assertTrue(report.output().contains("Ground entity UUID: " + entity));
        assertFalse(report.output().contains("AQIDBA=="));
        assertFalse(report.output().contains(session.toString()));
        assertFalse(report.output().contains(tower.toString()));

        assertArrayEquals(originalDatabase, Files.readAllBytes(database));
        assertArrayEquals(originalOwner, Files.readAllBytes(owner));
        try (var paths = Files.list(capture)) {
            assertEquals(List.of("lanterns.db", "lanterns.db.owner"),
                    paths.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }

    @ParameterizedTest
    @EnumSource(value = Phase.class, names = {"COMPLETE", "ROLLED_BACK"})
    void terminalReceiptsDoNotProvideLocators(Phase phase) throws Exception {
        var report = inspect(fixture(phase, recipe()), "--locators");
        assertEquals(0, report.status());
        assertTrue(report.output().contains("Journal phase: " + phase));
        assertTrue(report.output().contains("not inspected for terminal receipt"));
        assertNoPrivateValues(report);
    }

    @Test void journalMismatchAndLegacyRecipeDoNotProvideLocators() throws Exception {
        var capture = fixture(Phase.INTENT, new Document(1, "legacy"));
        var report = inspect(capture, "--locators");
        assertEquals(0, report.status());
        assertTrue(report.output().contains("Item-drop recipe: unsupported or unavailable"));
        assertNoPrivateValues(report);

        try (var connection = PrivateSqlite.open(capture.resolve("lanterns.db"));
             var sql = connection.createStatement()) {
            sql.execute("UPDATE towers SET hp=20");
        }
        report = inspect(capture, "--locators");
        assertEquals(0, report.status());
        assertTrue(report.output().contains("Frozen tower row: inconsistent"));
        assertFalse(report.output().contains("Item-drop recipe"));
        assertNoPrivateValues(report);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-wal", "-shm", "-journal"})
    void sidecarsAreRefusedAndPreserved(String suffix) throws Exception {
        var capture = fixture(Phase.INTENT, recipe());
        var sidecar = Files.writeString(capture.resolve("lanterns.db" + suffix), "saved evidence");
        var report = inspect(capture, "--locators");
        assertEquals(1, report.status());
        assertTrue(report.errors().contains("SQLite sidecars"));
        assertEquals("", report.output());
        assertEquals("saved evidence", Files.readString(sidecar));
    }

    @Test void notFoundIsDistinctFromInvalidOrBusyStore() throws Exception {
        var capture = fixture(Phase.INTENT, recipe());
        var missing = inspect(capture, UUID.randomUUID());
        assertEquals(0, missing.status());
        assertTrue(missing.output().contains("operation not found"));

        var owner = capture.resolve("lanterns.db.owner");
        try (var channel = FileChannel.open(owner, WRITE); var lock = channel.lock()) {
            var busy = inspect(capture);
            assertEquals(1, busy.status());
            assertTrue(busy.errors().contains("owner is locked"));
        }

        Files.delete(owner);
        assertEquals(1, inspect(capture).status());
        assertFalse(Files.exists(owner));
    }

    @Test void oversizedDatabaseIsRefusedBeforeSqliteInspection() throws Exception {
        var capture = fixture(Phase.INTENT, recipe());
        var database = capture.resolve("lanterns.db");
        try (var channel = FileChannel.open(database, WRITE)) {
            channel.write(ByteBuffer.wrap(new byte[]{0}), 256L * 1024 * 1024);
        }
        var report = inspect(capture);
        assertEquals(1, report.status());
        assertTrue(report.errors().contains("exceeds 256 MiB"));
        assertEquals(256L * 1024 * 1024 + 1, Files.size(database));
    }

    @Test void invalidArgumentsKeepInputPrivate() {
        var report = run(root.toString(), "invalid-operation", "--locators");
        assertEquals(2, report.status());
        assertEquals("", report.output());
        assertTrue(report.errors().startsWith("Usage:"));
        assertFalse(report.errors().contains("invalid-operation"));
        assertFalse(report.errors().contains(root.toString()));
    }

    @Test void unrecognizedSchemaAndAliasedStoreCannotBeInspected() throws Exception {
        var capture = fixture(Phase.INTENT, recipe());
        var database = capture.resolve("lanterns.db");
        try (var connection = PrivateSqlite.open(database); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE unexpected(value TEXT)");
        }
        var report = inspect(capture);
        assertEquals(1, report.status());
        assertEquals("", report.output());

        Files.move(database, capture.resolve("preserved.db"));
        Files.createSymbolicLink(database, Path.of("preserved.db"));
        assertEquals(1, inspect(capture).status());
        assertTrue(Files.isSymbolicLink(database));
    }

    private Path fixture(Phase phase, Document effects) throws Exception {
        var capture = Files.createDirectory(root.resolve("native capture #%"));
        var database = capture.resolve("lanterns.db");
        var repository = SqliteRepository.openCatalogOnly(database, 8).get(10, TimeUnit.SECONDS);
        repository.closeAsync().get(10, TimeUnit.SECONDS);
        var before = new State(tower(Form.PORTABLE, 1), null, List.of());
        var after = new State(tower(Form.GROUND, 2),
                new Ground(entity, world, -7, 11, operation, "active"), List.of());
        try (var connection = PrivateSqlite.open(database); var sql = connection.createStatement();
             var row = connection.prepareStatement("INSERT INTO towers VALUES(?,"
                     + "'fixture',25,100,'PORTABLE',1,0,null,null,null,null,null,null,0,1,300,400,1,?)");
             var journal = connection.prepareStatement("INSERT INTO operations VALUES("
                     + "?,?,0,1,0,'item-drop',?,?,?,?,?,0,'private journal evidence')")) {
            sql.execute("INSERT INTO variants VALUES('fixture',0,'ACTIVE',1,'private definition',0)");
            insertTower(row, phase);
            journal.setString(1, operation.toString());
            journal.setString(2, tower.toString());
            journal.setBytes(3, SnapshotCodec.encode(before));
            journal.setBytes(4, SnapshotCodec.encode(after));
            journal.setInt(5, effects.version());
            journal.setString(6, effects.content());
            journal.setString(7, phase.name());
            journal.executeUpdate();
        }
        return capture;
    }

    private void insertTower(PreparedStatement row, Phase phase) throws Exception {
        row.setString(1, tower.toString());
        row.setString(2, phase == Phase.COMPLETE || phase == Phase.ROLLED_BACK ? null : operation.toString());
        row.executeUpdate();
    }

    private Tower tower(Form form, long generation) {
        return new Tower(tower, "fixture", 25, 100, form, generation, 0, null, null,
                false, true, 300, 400);
    }

    private Document recipe() {
        var original = new Document(1, "action=drop;actor=" + actor + ";session=" + session
                + ";slot=4;source=AQIDBA==");
        return RecoveryEnvelope.item("item-drop", new Anchor(world, -7, 11), original);
    }

    private void assertNoPrivateValues(Report report) {
        var all = report.output() + report.errors();
        for (var id : List.of(actor, world, entity, tower, operation, session)) {
            assertFalse(all.contains(id.toString()));
        }
        assertFalse(all.contains(root.toString()));
        assertFalse(all.contains("AQIDBA=="));
        assertFalse(all.contains("private journal evidence"));
    }

    private Report inspect(Path capture, String... options) {
        if (options.length == 0) return inspect(capture, operation);
        return run(capture.toString(), operation.toString(), options[0]);
    }

    private Report inspect(Path capture, UUID id) {
        return run(capture.toString(), id.toString());
    }

    private static Report run(String... args) {
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        var status = OfflineRecoveryInspection.run(args,
                new PrintStream(output, false, StandardCharsets.UTF_8),
                new PrintStream(errors, false, StandardCharsets.UTF_8));
        return new Report(status, output.toString(StandardCharsets.UTF_8),
                errors.toString(StandardCharsets.UTF_8));
    }

    private record Report(int status, String output, String errors) {}
}
