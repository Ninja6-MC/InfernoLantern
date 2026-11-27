package com.ninja6.infernolantern.runtime;

import com.ninja6.infernolantern.prototype.PrototypeLedger;
import com.ninja6.infernolantern.pack.PackPlan;
import com.ninja6.infernolantern.repository.CommitPermit;
import com.ninja6.infernolantern.repository.RepositoryException.CommitRevoked;
import com.ninja6.infernolantern.repository.SqliteRepository;
import com.ninja6.infernolantern.repository.SqliteTestConnections;
import com.ninja6.infernolantern.scheduling.WorldScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static com.ninja6.infernolantern.runtime.NativeCatalogRuntime.Result.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeCatalogRuntimeTest {
    @TempDir Path data;

    private static final String BASE_PACK = "infernolantern-generic-java.zip";
    private static final String OVERRIDE_PACK = "infernolantern-override-java.zip";
    private static final URI PACK_URL = URI.create("https://assets.example.invalid/" + BASE_PACK);
    private static final URI OVERRIDE_URL = URI.create("https://assets.example.invalid/" + OVERRIDE_PACK);

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
            assertNull(runtime.javaPack(PACK_URL, null, false));
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

    @Test void pendingNativeJournalBlocksReloadAndPackBindingsWithoutPublication() throws Exception {
        var repository = get(SqliteRepository.openCatalogOnly(database(), 4));
        get(repository.closeAsync());
        try (var connection = SqliteTestConnections.open(database()); var sql = connection.createStatement()) {
            sql.execute("INSERT INTO variants VALUES('held',0,'ACTIVE',1,'held definition',0)");
            sql.execute("INSERT INTO towers VALUES('00000000-0000-0000-0000-000000000001',"
                    + "'held',0,100,'PORTABLE',1,0,null,null,null,null,null,null,0,0,0,0,0,null)");
            sql.execute("INSERT INTO operations VALUES("
                    + "'00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001',"
                    + "0,1,0,'test',X'00',X'00',1,'held recipe','INTENT',0,'held evidence')");
        }
        byte[] saved = Files.readAllBytes(database());
        assertNativeStorageBlocked("blocked by pending native operations");
        assertArrayEquals(saved, Files.readAllBytes(database()));
    }

    @Test void orphanSidecarReportsHoldWithoutCreatingAnyOwnerOrDatabase() throws Exception {
        Files.createDirectories(database().getParent());
        Path sidecar = Files.writeString(database().resolveSibling("lanterns.db-wal"), "held journal bytes");
        assertNativeStorageBlocked("blocked by native SQLite sidecars");
        assertEquals("held journal bytes", Files.readString(sidecar));
        assertFalse(Files.exists(database()));
        assertFalse(Files.exists(database().resolveSibling("lanterns.db.owner")));
    }

    private void assertNativeStorageBlocked(String message) throws Exception {
        var runtime = start(VALID);
        try {
            assertEquals(UNAVAILABLE, get(runtime.startup()));
            assertTrue(runtime.status().startsWith(message), runtime::status);
            assertEquals(UNAVAILABLE, get(runtime.reload(new CommitPermit(), () -> {
                throw new AssertionError("Blocked native storage must not begin a catalog reload");
            })));
            assertNull(runtime.javaPack(PACK_URL, null, false));
            assertNull(runtime.javaCombinedPack());
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
            var pack = runtime.javaPack(PACK_URL, null, false);
            pack.availability().requireValid();
            try (var connection = SqliteTestConnections.open(database()); var sql = connection.createStatement()) {
                sql.execute("CREATE TRIGGER reject_publication BEFORE UPDATE ON catalog_state "
                        + "BEGIN SELECT RAISE(ABORT,'fixture publication failure'); END");
                try {
                    write(VALID);
                    assertEquals(UNAVAILABLE, get(reload(runtime)));
                    assertEquals(UNAVAILABLE, get(reload(runtime)));
                    assertNull(runtime.javaPack(PACK_URL, null, false));
                    assertThrows(CommitRevoked.class, pack.availability()::requireValid);
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

    @Test void javaDefaultUsesVerifiedArchiveAndCoverageAcrossRejectedReload() throws Exception {
        var runtime = start(VALID);
        try {
            assertEquals(PUBLISHED, get(runtime.startup()));
            var pack = runtime.javaPack(PACK_URL, null, false);
            assertNotNull(pack);
            var plan = pack.plan();
            var download = plan.downloads().getFirst();
            assertEquals(PACK_URL, download.uri());
            assertFalse(plan.required());
            assertEquals(PackPlan.Mode.DEFAULT, plan.identity().mode());
            assertEquals(PackPlan.Edition.JAVA, plan.identity().edition());
            assertEquals(2, plan.models().size());

            assertDownloadDigests(BASE_PACK, download);

            write("schema: 1\nvariants: null\n");
            assertEquals(REJECTED, get(reload(runtime)));
            pack.availability().requireValid();
            assertEquals(plan, runtime.javaPack(PACK_URL, null, false).plan());
            get(runtime.stop());
            assertThrows(CommitRevoked.class, pack.availability()::requireValid);
        } finally {
            get(runtime.stop());
        }
    }

    @Test void stackedJavaPlanBindsBothVerifiedArchivesInLoadOrderAndRequiredPolicy() throws Exception {
        var runtime = start(VALID);
        try {
            assertEquals(PUBLISHED, get(runtime.startup()));
            var plan = runtime.javaPack(PACK_URL, OVERRIDE_URL, true).plan();
            assertTrue(plan.required());
            assertEquals(PackPlan.Mode.STACKED, plan.identity().mode());
            assertEquals(PackPlan.Edition.JAVA, plan.identity().edition());
            assertEquals(List.of(PackPlan.Slot.BASE, PackPlan.Slot.OVERRIDE),
                    plan.identity().packs().stream().map(PackPlan.Archive::slot).toList());
            assertEquals(List.of(PACK_URL, OVERRIDE_URL), plan.downloads().stream().map(PackPlan.Download::uri).toList());
            assertDownloadDigests(BASE_PACK, plan.downloads().getFirst());
            assertDownloadDigests(OVERRIDE_PACK, plan.downloads().getLast());

            var defaultRequired = runtime.javaPack(PACK_URL, null, true).plan();
            assertTrue(defaultRequired.required());
            assertEquals(PackPlan.Mode.DEFAULT, defaultRequired.identity().mode());
        } finally {
            get(runtime.stop());
        }
    }

    @Test void combinedJavaPlanIsBoundToTheVerifiedArchiveWithoutDelivery() throws Exception {
        var runtime = start(VALID);
        try {
            assertEquals(PUBLISHED, get(runtime.startup()));
            var verified = runtime.javaCombinedPack();
            var plan = verified.plan();
            assertEquals(PackPlan.Edition.JAVA, plan.identity().edition());
            assertEquals(PackPlan.Mode.COMBINED, plan.identity().mode());
            assertFalse(plan.identity().pluginDelivered());
            assertFalse(plan.required());
            assertTrue(plan.downloads().isEmpty());
            assertEquals(2, plan.models().size());
            byte[] archive;
            try (var input = BundledModels.class.getResourceAsStream("/resource-packs/infernolantern-combined-java.zip")) {
                archive = input.readAllBytes();
            }
            assertEquals(List.of(new PackPlan.Archive(PackPlan.Slot.COMBINED,
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive)))), plan.identity().packs());
            assertEquals(PUBLISHED, get(reload(runtime)));
            assertEquals(plan, runtime.javaCombinedPack().plan());
            get(runtime.stop());
            assertThrows(CommitRevoked.class, verified.availability()::requireValid);
            assertNull(runtime.javaCombinedPack());
        } finally {
            get(runtime.stop());
        }
    }

    @Test void aCorruptCombinedArchiveLeavesCombinedReadinessUnavailable() throws Exception {
        write(VALID);
        var runtime = new NativeCatalogRuntime(data, PrototypeLedger.inspectCleanup(data), Runnable::run, NO_WORLD,
                () -> BundledModels.load(name -> name.endsWith("infernolantern-combined-java.zip")
                        ? new ByteArrayInputStream(new byte[]{1, 2, 3})
                        : BundledModels.class.getResourceAsStream(name)),
                () -> SqliteRepository.openCatalogOnly(database(), 4));
        try {
            assertEquals(PUBLISHED, get(runtime.startup()));
            assertNull(runtime.javaCombinedPack());
            assertNotNull(runtime.javaPack(PACK_URL, null, false));
        } finally {
            get(runtime.stop());
        }
    }

    @Test void tamperedOverrideCanNeverFallBackToRequiredBaseDelivery() throws Exception {
        write(VALID);
        var runtime = new NativeCatalogRuntime(data, PrototypeLedger.inspectCleanup(data), Runnable::run, NO_WORLD,
                () -> BundledModels.load(name -> name.endsWith(OVERRIDE_PACK)
                        ? new ByteArrayInputStream(new byte[]{1, 2, 3})
                        : BundledModels.class.getResourceAsStream(name)),
                () -> SqliteRepository.openCatalogOnly(database(), 4));
        try {
            assertEquals(PUBLISHED, get(runtime.startup()));
            assertNull(runtime.javaPack(PACK_URL, OVERRIDE_URL, true));
            var defaultPack = runtime.javaPack(PACK_URL, null, false).plan();
            assertFalse(defaultPack.required());
            assertEquals(PackPlan.Mode.DEFAULT, defaultPack.identity().mode());
        } finally {
            get(runtime.stop());
        }
    }

    @Test void localOverrideBindsTheStartupSnapshotAndRejectedArchiveCannotFallBack() throws Exception {
        write(VALID);
        byte[] archive = JavaTextureOverrideTest.alternateTextureArchive();
        Path local = Files.write(data.resolve(JavaTextureOverride.FILE), archive);
        var runtime = new NativeCatalogRuntime(data, PrototypeLedger.inspectCleanup(data), Runnable::run, NO_WORLD, true);
        try {
            assertEquals(PUBLISHED, get(runtime.startup()));
            var pack = runtime.javaPack(PACK_URL, OVERRIDE_URL, true);
            assertTrue(pack.localOverride());
            assertTrue(pack.plan().required());
            assertDownloadDigests(BASE_PACK, pack.plan().downloads().getFirst());
            var download = pack.plan().downloads().getLast();
            assertEquals(OVERRIDE_URL, download.uri());
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive)),
                    download.archive().sha256());
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(archive)), download.sha1());
            Files.write(local, new byte[]{1, 2, 3});
            assertEquals(PUBLISHED, get(reload(runtime)));
            assertEquals(pack.plan(), runtime.javaPack(PACK_URL, OVERRIDE_URL, true).plan());
        } finally {
            get(runtime.stop());
        }

        var restarted = new NativeCatalogRuntime(data, PrototypeLedger.inspectCleanup(data), Runnable::run, NO_WORLD, true);
        try {
            assertEquals(PUBLISHED, get(restarted.startup()));
            assertNull(restarted.javaPack(PACK_URL, OVERRIDE_URL, true));
        } finally {
            get(restarted.stop());
        }

        var bundled = start(VALID);
        try {
            assertEquals(PUBLISHED, get(bundled.startup()));
            var pack = bundled.javaPack(PACK_URL, OVERRIDE_URL, true);
            assertFalse(pack.localOverride());
            assertDownloadDigests(OVERRIDE_PACK, pack.plan().downloads().getLast());
        } finally {
            get(bundled.stop());
        }
    }

    private static void assertDownloadDigests(String filename, PackPlan.Download download) throws Exception {
        try (var input = BundledModels.class.getResourceAsStream("/resource-packs/" + filename)) {
            byte[] bytes = input.readAllBytes();
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                    download.archive().sha256());
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes)), download.sha1());
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
