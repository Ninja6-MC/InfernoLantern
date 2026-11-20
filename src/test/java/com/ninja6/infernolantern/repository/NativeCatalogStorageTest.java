package com.ninja6.infernolantern.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
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

    private static Variant legacyVariant() {
        return new Variant("legacy", 0, VariantStatus.ACTIVE, new Document(1, "held definition"), 0);
    }

    private static <T> T get(CompletableFuture<T> operation) throws Exception {
        return operation.get(10, TimeUnit.SECONDS);
    }
}
