package com.ninja6.infernolantern.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static com.ninja6.infernolantern.repository.RepositoryRecords.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeCatalogStorageTest {
    @TempDir Path data;

    @Test void nativeStoreHasOneOwnerAndRefusesLegacyOpen() throws Exception {
        var file = data.resolve("lanterns.db");
        var nativeStore = get(SqliteRepository.openCatalogOnly(file, 8));
        try {
            assertThrows(ExecutionException.class, () -> get(SqliteRepository.openCatalogOnly(file, 8)));
            assertThrows(ExecutionException.class, () -> get(SqliteRepository.open(file, 8)));
            assertThrows(ExecutionException.class, () -> get(nativeStore.createVariant(legacyVariant())));
            assertTrue(get(nativeStore.variant("legacy")).isEmpty());
        } finally {
            get(nativeStore.closeAsync());
        }
        try (var connection = PrivateSqlite.open(file)) {
            assertEquals(Schema.NATIVE_APPLICATION_ID, Schema.pragma(connection, "application_id"));
        }
        get(get(SqliteRepository.openCatalogOnly(file, 8)).closeAsync());
    }

    @Test void copiedPrototypeCannotBeAdoptedAndRemainsUsable() throws Exception {
        var held = data.resolve("item-prototype.db");
        var prototype = get(SqliteRepository.open(held, 8));
        try {
            get(prototype.createVariant(legacyVariant()));
        } finally {
            get(prototype.closeAsync());
        }
        var nativePath = Files.copy(held, data.resolve("lanterns.db"));
        byte[] saved = Files.readAllBytes(held);
        assertThrows(ExecutionException.class, () -> get(SqliteRepository.openCatalogOnly(nativePath, 8)));
        assertArrayEquals(saved, Files.readAllBytes(nativePath));
        assertArrayEquals(saved, Files.readAllBytes(held));
        var resumed = get(SqliteRepository.open(held, 8));
        try {
            assertEquals(legacyVariant(), get(resumed.variant("legacy")).orElseThrow());
        } finally {
            get(resumed.closeAsync());
        }
    }

    @Test void unexpectedlyNonemptyNativeStoreFailsWithoutCatalogPublication() throws Exception {
        var file = data.resolve("lanterns.db");
        get(get(SqliteRepository.openCatalogOnly(file, 8)).closeAsync());
        try (var connection = PrivateSqlite.open(file); var sql = connection.createStatement()) {
            sql.execute("INSERT INTO variants VALUES('legacy',0,'ACTIVE',1,'held definition',0)");
            sql.execute("INSERT INTO towers VALUES('00000000-0000-0000-0000-000000000001','legacy',0,100,'PORTABLE',1,0,null,null,null,null,null,null,0,0,0,0,0,null)");
        }
        byte[] saved = Files.readAllBytes(file);
        assertThrows(ExecutionException.class, () -> get(SqliteRepository.openCatalogOnly(file, 8)));
        assertArrayEquals(saved, Files.readAllBytes(file));
        try (var connection = PrivateSqlite.open(file); var sql = connection.createStatement();
             var rows = sql.executeQuery("SELECT (SELECT count(*) FROM towers), (SELECT count(*) FROM catalog_state)")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
            assertEquals(0, rows.getInt(2));
        }
    }

    @Test void nativeFileAliasesAndJournalWithoutDatabaseStayUntouched() throws Exception {
        var held = Files.writeString(data.resolve("held"), "preserve this");
        var file = data.resolve("lanterns.db");
        var alias = Files.createSymbolicLink(file, held);
        assertThrows(ExecutionException.class, () -> get(SqliteRepository.openCatalogOnly(alias, 8)));
        assertTrue(Files.isSymbolicLink(alias));
        assertEquals("preserve this", Files.readString(held));

        if (file.getFileSystem().supportedFileAttributeViews().contains("unix")) {
            var owned = data.resolve("owned.db");
            get(get(SqliteRepository.openCatalogOnly(owned, 8)).closeAsync());
            byte[] original = Files.readAllBytes(owned);
            var linked = Files.createLink(data.resolve("hardlink.db"), owned);
            assertThrows(ExecutionException.class, () -> get(SqliteRepository.openCatalogOnly(linked, 8)));
            assertArrayEquals(original, Files.readAllBytes(owned));
            assertArrayEquals(original, Files.readAllBytes(linked));
        }

        var journal = Files.writeString(data.resolve("absent.db-wal"), "held journal");
        assertThrows(ExecutionException.class, () -> get(SqliteRepository.openCatalogOnly(data.resolve("absent.db"), 8)));
        assertEquals("held journal", Files.readString(journal));
        assertFalse(Files.exists(data.resolve("absent.db")));
    }

    @Test void interruptedFirstOpenLeavesOnlyAnIsolatedStageAndCanRetry() throws Exception {
        var file = data.resolve("lanterns.db");
        var log = data.resolve("halt.log");
        String compiledTests = Path.of(InterruptedInitializer.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String classPath = System.getProperty("pluginJar") + java.io.File.pathSeparator + compiledTests;
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--class-path", classPath, InterruptedInitializer.class.getName(), file.toString())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        boolean finished = process.waitFor(20, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertTrue(finished);
        assertEquals(23, process.exitValue(), Files.readString(log));
        assertFalse(Files.exists(file));
        Path abandoned;
        try (var children = Files.list(data)) {
            abandoned = children.filter(Files::isDirectory).findFirst().orElseThrow().resolve(file.getFileName());
        }
        byte[] evidence = Files.readAllBytes(abandoned);
        var runtime = get(SqliteRepository.openCatalogOnly(file, 8));
        try {
            assertEquals(0, get(runtime.catalog()).revision());
        } finally {
            get(runtime.closeAsync());
        }
        assertArrayEquals(evidence, Files.readAllBytes(abandoned));
    }

    @Test void interruptedPublicationFinishesOnlyTheExactStageLink() throws Exception {
        var file = data.resolve("lanterns.db");
        var stage = UUID.randomUUID();
        var stageDir = Files.createDirectory(data.resolve("lanterns.db.initializing-" + stage));
        var stageFile = stageDir.resolve(file.getFileName());
        try (var connection = PrivateSqlite.open(stageFile)) {
            Schema.initialize(connection, Schema.NATIVE_APPLICATION_ID);
        }
        var other = Files.writeString(stageDir.resolve("evidence"), "preserve this evidence");
        Files.writeString(data.resolve("lanterns.db.owner"), "native-stage-v1:" + stage);
        Files.createLink(file, stageFile);
        var runtime = get(SqliteRepository.openCatalogOnly(file, 8));
        try {
            assertEquals(0, get(runtime.catalog()).revision());
            assertFalse(Files.exists(stageFile));
            assertEquals("preserve this evidence", Files.readString(other));
        } finally {
            get(runtime.closeAsync());
        }
    }

    @Test void newPublishCannotReplaceAnUnexpectedDestination() throws Exception {
        var file = data.resolve("lanterns.db");
        var ownerFile = data.resolve("lanterns.db.owner");
        try (var owner = FileChannel.open(ownerFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ,
                StandardOpenOption.WRITE); var lock = owner.lock()) {
            assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> NativeStoreFiles.prepare(file, owner, candidate -> {
                try (var connection = PrivateSqlite.open(candidate)) {
                    Schema.initialize(connection, Schema.NATIVE_APPLICATION_ID);
                }
                Files.createFile(file);
            }));
        }
        assertEquals(0, Files.size(file));
        assertThrows(ExecutionException.class, () -> get(SqliteRepository.openCatalogOnly(file, 8)));
        assertEquals(0, Files.size(file));
    }

    public static final class InterruptedInitializer {
        public static void main(String[] args) throws Exception {
            Path file = Path.of(args[0]);
            try (var owner = FileChannel.open(file.resolveSibling("lanterns.db.owner"), StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.READ, StandardOpenOption.WRITE); var lock = owner.lock()) {
                NativeStoreFiles.prepare(file, owner, candidate -> {
                    try (var connection = PrivateSqlite.open(candidate)) {
                        Runtime.getRuntime().halt(23);
                    }
                });
            }
        }
    }

    private static Variant legacyVariant() {
        return new Variant("legacy", 0, VariantStatus.ACTIVE, new Document(1, "held definition"), 0);
    }

    private static <T> T get(CompletableFuture<T> operation) throws Exception {
        return operation.get(10, TimeUnit.SECONDS);
    }
}
