package com.ninja6.infernolantern.repository;

import com.ninja6.infernolantern.recovery.RecoveryEnvelope;
import com.ninja6.infernolantern.repository.RepositoryException.NativeStartupHold;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.UUID;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.READ;

/** Standalone inspection of one journal record in a sidecar-free native store copy. */
public final class OfflineRecoveryInspection {
    private static final long MAX_DATABASE_BYTES = 256L * 1024 * 1024;

    private OfflineRecoveryInspection() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream output, PrintStream errors) {
        boolean locators = args.length == 3 && "--locators".equals(args[2]);
        final Path directory;
        final UUID operationId;
        try {
            if (args.length != 2 && !locators) throw new IllegalArgumentException();
            directory = Path.of(args[0]);
            operationId = UUID.fromString(args[1]);
            if (!operationId.toString().equals(args[1])) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            errors.println("Usage: offlineRecoveryInspection <native-runtime-copy> <operation-uuid> [--locators]");
            return 2;
        }

        try {
            output.println(inspect(directory, operationId, locators));
            output.println("Minecraft saves: unexamined");
            return 0;
        } catch (NativeStartupHold sidecars) {
            errors.println("Inspection unavailable: the capture contains SQLite sidecars.");
        } catch (OverlappingFileLockException busy) {
            errors.println("Inspection unavailable: the capture owner is locked.");
        } catch (Refused failure) {
            errors.println(failure.getMessage());
        } catch (Exception unavailable) {
            errors.println("Inspection unavailable: the capture could not be validated or read.");
        }
        return 1;
    }

    private static String inspect(Path directory, UUID operationId, boolean locators) throws Exception {
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) {
            throw new Refused("Inspection unavailable: expected an offline native store directory.");
        }
        Path capture = directory.toRealPath();
        Path file = capture.resolve("lanterns.db");
        Path ownerPath = capture.resolve("lanterns.db.owner");
        if (!NativeStoreFiles.checkStartupPaths(file)) {
            throw new Refused("Inspection unavailable: the native database is missing.");
        }

        try (var owner = FileChannel.open(ownerPath, READ, NOFOLLOW_LINKS);
             var lock = owner.tryLock(0, Long.MAX_VALUE, true)) {
            if (lock == null) throw new Refused("Inspection unavailable: the capture owner is locked.");
            NativeStoreFiles.checkCapturePath(file);
            var ownerBefore = attributes(ownerPath);
            var before = attributes(file);
            if (before.size() > MAX_DATABASE_BYTES) {
                throw new Refused("Inspection unavailable: the native database exceeds 256 MiB.");
            }
            if (!NativeStoreFiles.inspectExisting(file, owner)) throw new IOException("Database unavailable");
            String result = read(file, operationId, locators);
            NativeStoreFiles.checkCapturePath(file);
            if (!NativeStoreFiles.inspectExisting(file, owner)
                    || changed(before, attributes(file)) || changed(ownerBefore, attributes(ownerPath))) {
                throw new Refused("Inspection unavailable: the capture changed during inspection.");
            }
            return result;
        }
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
    }

    private static boolean changed(BasicFileAttributes before, BasicFileAttributes after) {
        return !after.isRegularFile() || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey());
    }

    private static String read(Path file, UUID operationId, boolean locators) throws Exception {
        try (var connection = PrivateSqlite.inspectNative(file)) {
            Schema.verifyNative(connection);
            var operation = SqliteRepository.loadOperation(connection, operationId);
            if (operation == null) return "Journal: operation not found";

            String phase = "Journal phase: " + operation.phase();
            if (!operation.pending()) return phase + "\nFrozen tower row: not inspected for terminal receipt";
            var intent = operation.intent();
            var tower = SqliteRepository.loadTower(connection, intent.towerId());
            if (tower == null || !intent.id().equals(tower.activeOperation())
                    || tower.rowVersion() != intent.expectedRowVersion() + 1
                    || !operation.before().equals(tower.state())) {
                return phase + "\nFrozen tower row: inconsistent";
            }

            String journal = phase + "\nFrozen tower row: matches pending journal";
            var recipe = RecoveryEnvelope.decode(intent.effects()).orElse(null);
            if (recipe == null || !"item-drop".equals(recipe.kind())
                    || !RecoveryEnvelope.supports(operation, recipe)) {
                return journal + "\nItem-drop recipe: unsupported or unavailable";
            }

            String report = journal + "\nItem-drop recipe: supported";
            if (!locators) return report;
            var source = recipe.inventory();
            var anchor = recipe.anchor();
            return report + "\nPlayer UUID: " + source.actor() + "\nPlayer slot: " + source.slot()
                    + "\nWorld UUID: " + anchor.world() + "\nChunk X: " + anchor.chunkX()
                    + "\nChunk Z: " + anchor.chunkZ() + "\nGround entity UUID: " + intent.after().ground().entity();
        }
    }

    private static final class Refused extends IOException {
        Refused(String message) {
            super(message);
        }
    }
}
