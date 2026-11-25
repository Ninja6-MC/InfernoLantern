package com.ninja6.infernolantern.runtime;

import com.ninja6.infernolantern.prototype.PrototypeLedger;
import com.ninja6.infernolantern.repository.CommitPermit;
import com.ninja6.infernolantern.repository.SqliteRepository;
import com.ninja6.infernolantern.repository.SqliteTestConnections;
import com.ninja6.infernolantern.scheduling.WorldScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static com.ninja6.infernolantern.runtime.NativeCatalogRuntime.Result.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeCatalogRuntimeTest {
    @TempDir Path data;

    private static final WorldScheduler NO_WORLD = new WorldScheduler() {
        @Override
        public <T> CompletableFuture<T> supply(Anchor anchor, Supplier<T> action) {
            throw new AssertionError("A catalog-only publication must not schedule world work");
        }
    };

    private static final String VALID = """
            schema: 1
            variants:
              example:
                player-switches: {light: true, defence: false}
                light-level: 12
                maximum-hp: 100
                expansion-cooldown-ms: 0
                geometry:
                  compact-model: infernolantern:generic_ember_compact
                  expanded-model: infernolantern:generic_ember_expanded
                  width: 1
                  height: 3
                  depth: 1
                  light-x: 0
                  light-y: 2
                  light-z: 0
            """;

    @Test void bundledCatalogPublishesAndInvalidReloadKeepsDurableState() throws Exception {
        var heldPrototype = Files.writeString(data.resolve("item-prototype.db"), "held bytes");
        var runtime = start(VALID);
        try {
            assertEquals(PUBLISHED, get(runtime.startup()));
            assertEquals("catalog published; native gameplay unavailable", runtime.status());
            assertEquals("held bytes", Files.readString(heldPrototype));
            write(VALID.replace("generic_ember_expanded", "generic_frost_expanded"));
            assertEquals(REJECTED, get(reload(runtime)));
            write("schema: 1\nvariants: null\n");
            assertEquals(REJECTED, get(reload(runtime)));
            assertEquals("catalog published; native gameplay unavailable", runtime.status());
        } finally {
            get(runtime.stop());
        }
        SqliteRepository repository = get(SqliteRepository.openCatalogOnly(database(), 4));
        try {
            var snapshot = get(repository.catalog());
            assertEquals(1, snapshot.revision());
            assertEquals("example", snapshot.variants().getFirst().id());
        } finally {
            get(repository.closeAsync());
        }
    }

    @Test void rejectedStartupRemainsExclusiveAndCanReloadValidCatalog() throws Exception {
        var runtime = start("schema: 1\nvariants: null\n");
        try {
            assertEquals(REJECTED, get(runtime.startup()));
            assertEquals("no published catalog for this startup; native gameplay unavailable", runtime.status());
            assertFalse(Files.exists(data.resolve("item-prototype.db")));
            write(VALID);
            assertEquals(PUBLISHED, get(reload(runtime)));
        } finally {
            get(runtime.stop());
        }
    }

    @Test void overlappingReloadAndRevokedOrDeniedAuthorityDoNotPublish() throws Exception {
        var runtime = start("schema: 1\nvariants: {}\n");
        try {
            assertEquals(PUBLISHED, get(runtime.startup()));
            write(VALID);
            var authorization = new CompletableFuture<Boolean>();
            var sender = new CommitPermit();
            var requested = runtime.reload(sender, () -> authorization);
            assertEquals(BUSY, get(reload(runtime)));
            sender.revoke();
            authorization.complete(true);
            assertEquals(REJECTED, get(requested));
            assertEquals(REJECTED, get(runtime.reload(new CommitPermit(), () -> CompletableFuture.completedFuture(false))));
        } finally {
            get(runtime.stop());
        }
        var repository = get(SqliteRepository.openCatalogOnly(database(), 4));
        try {
            assertEquals(1, get(repository.catalog()).revision());
            assertTrue(get(repository.catalog()).variants().isEmpty());
        } finally {
            get(repository.closeAsync());
        }
    }

    @Test void pendingInvalidAndStagedPrototypeCleanupBlockNativeAdmission() throws Exception {
        Path manifest = data.resolve("prototype-cleanup.bin");
        for (byte[] evidence : new byte[][]{
                ByteBuffer.allocate(8).putInt(0x494C5031).putInt(1).array(),
                new byte[]{1, 2, 3},
                ByteBuffer.allocate(9).putInt(0x494C5031).putInt(0).put((byte) 1).array()
        }) {
            Files.write(manifest, evidence);
            assertCleanupBlocked();
            assertArrayEquals(evidence, Files.readAllBytes(manifest));
        }

        byte[] empty = ByteBuffer.allocate(8).putInt(0x494C5031).putInt(0).array();
        Files.write(manifest, empty);
        Path staged = Files.writeString(data.resolve("prototype-cleanup.bin.tmp"), "held evidence");
        assertCleanupBlocked();
        assertEquals("held evidence", Files.readString(staged));
        Files.delete(staged);
        var runtime = start(VALID);
        try {
            assertEquals(PUBLISHED, get(runtime.startup()));
            assertArrayEquals(empty, Files.readAllBytes(manifest));
        } finally {
            get(runtime.stop());
        }
    }

    private void assertCleanupBlocked() throws Exception {
        var runtime = start(VALID);
        try {
            assertEquals(UNAVAILABLE, get(runtime.startup()));
            assertEquals(UNAVAILABLE, get(reload(runtime)));
            assertTrue(runtime.status().startsWith("blocked by prototype cleanup evidence"));
            assertFalse(Files.exists(database()));
        } finally {
            get(runtime.stop());
        }
    }

    @Test void closingBeforeAuthorizationDoesNotPublishAndReleasesOwner() throws Exception {
        var runtime = start("schema: 1\nvariants: {}\n");
        assertEquals(PUBLISHED, get(runtime.startup()));
        write(VALID);
        var authorization = new CompletableFuture<Boolean>();
        var requested = runtime.reload(new CommitPermit(), () -> authorization);
        get(runtime.stop());
        authorization.complete(true);
        assertEquals(UNAVAILABLE, get(requested));
        var repository = get(SqliteRepository.openCatalogOnly(database(), 4));
        try {
            assertEquals(1, get(repository.catalog()).revision());
        } finally {
            get(repository.closeAsync());
        }
    }

    @Test void storageFailureClosesAdmissionAndPreservesThePreviousPublication() throws Exception {
        var runtime = start("schema: 1\nvariants: {}\n");
        try {
            assertEquals(PUBLISHED, get(runtime.startup()));
            try (var connection = SqliteTestConnections.open(database()); var sql = connection.createStatement()) {
                sql.execute("CREATE TRIGGER reject_publication BEFORE UPDATE ON catalog_state "
                        + "BEGIN SELECT RAISE(ABORT,'fixture publication failure'); END");
                try {
                    write(VALID);
                    assertEquals(UNAVAILABLE, get(reload(runtime)));
                    assertEquals(UNAVAILABLE, get(reload(runtime)));
                } finally {
                    sql.execute("DROP TRIGGER reject_publication");
                }
            }
        } finally {
            get(runtime.stop());
        }
        var repository = get(SqliteRepository.openCatalogOnly(database(), 4));
        try {
            assertEquals(1, get(repository.catalog()).revision());
            assertTrue(get(repository.catalog()).variants().isEmpty());
        } finally {
            get(repository.closeAsync());
        }
    }

    @Test void stoppedDuringOpeningClosesTheLateRepository() throws Exception {
        write(VALID);
        var opening = new CompletableFuture<SqliteRepository>();
        var runtime = new NativeCatalogRuntime(data, PrototypeLedger.inspectCleanup(data), Runnable::run, NO_WORLD, BundledModels::load, () -> opening);
        var closing = runtime.stop();
        assertFalse(closing.isDone());
        opening.complete(get(SqliteRepository.openCatalogOnly(database(), 4)));
        assertEquals(UNAVAILABLE, get(runtime.startup()));
        get(closing);
        var repository = get(SqliteRepository.openCatalogOnly(database(), 4));
        try {
            assertEquals(0, get(repository.catalog()).revision());
        } finally {
            get(repository.closeAsync());
        }
    }

    @Test void alteredEmbeddedPackCannotAuthorizeOpening() throws Exception {
        write(VALID);
        var runtime = new NativeCatalogRuntime(data, PrototypeLedger.inspectCleanup(data), Runnable::run, NO_WORLD,
                () -> BundledModels.load(name -> name.endsWith("-java.zip")
                        ? new ByteArrayInputStream(new byte[]{1, 2, 3})
                        : BundledModels.class.getResourceAsStream(name)),
                () -> {
                    fail("Unverified asset set cannot open storage");
                    return null;
                });
        try {
            assertEquals(UNAVAILABLE, get(runtime.startup()));
            assertFalse(Files.exists(database()));
        } finally {
            get(runtime.stop());
        }
    }

    private CompletableFuture<NativeCatalogRuntime.Result> reload(NativeCatalogRuntime runtime) {
        return runtime.reload(new CommitPermit(), () -> CompletableFuture.completedFuture(true));
    }

    private NativeCatalogRuntime start(String text) throws Exception {
        write(text);
        return new NativeCatalogRuntime(data, PrototypeLedger.inspectCleanup(data), Runnable::run, NO_WORLD);
    }

    private void write(String text) throws Exception {
        Files.writeString(data.resolve("catalog.yml"), text);
    }

    private Path database() {
        return data.resolve("native-runtime/lanterns.db");
    }

    private static <T> T get(CompletableFuture<T> operation) throws Exception {
        return operation.get(10, TimeUnit.SECONDS);
    }
}
