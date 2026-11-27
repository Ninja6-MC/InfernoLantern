package com.ninja6.infernolantern.repository;

import com.ninja6.infernolantern.repository.RepositoryException.NativeStartupHold;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Publishes a complete first native database without replacing the destination. */
final class NativeStoreFiles {
    private static final List<String> SIDECARS = List.of("-wal", "-shm", "-journal");
    private static final byte[] SQLITE_MAGIC = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);
    private static final String MARKER_PREFIX = "native-stage-v1:";
    private static final int MARKER_SIZE = MARKER_PREFIX.length() + 36;

    @FunctionalInterface
    interface Initializer {
        void initialize(Path database) throws Exception;
    }

    private NativeStoreFiles() {}

    static void checkPaths(Path file) throws IOException {
        if (Files.isSymbolicLink(file.getParent())) throw new IOException("Native storage directory must not be a symbolic link");
        if (present(file)) {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Unexpected native database path");
            }
            verifyHeader(file);
        }
        for (String suffix : SIDECARS) checkRegular(sidecar(file, suffix));
        checkRegular(sidecar(file, ".owner"));
    }

    static boolean checkStartupPaths(Path file) throws IOException {
        checkPaths(file);
        for (String suffix : SIDECARS) {
            if (present(sidecar(file, suffix))) throw new NativeStartupHold(NativeStartupHold.Reason.SIDECARS);
        }
        return present(file);
    }

    /** Assess the existing publication link without unlinking anything. */
    static boolean inspectExisting(Path file, FileChannel owner) throws IOException {
        if (!checkStartupPaths(file)) return false;
        if (parseMarker(readMarker(owner)) == null) throw new IOException("Unrecognized native owner metadata");
        publicationCandidate(file, owner);
        return true;
    }

    /** The caller holds the process and filesystem owner locks for the entire operation. */
    static void prepare(Path file, FileChannel owner, Initializer initializer) throws Exception {
        checkPaths(file);
        if (present(file)) {
            finishLink(file, owner);
            checkRegular(file);
            return;
        }
        requireAbsentSidecars(file);
        requireOwnedMarker(readMarker(owner));

        UUID stageId = UUID.randomUUID();
        Path staging = stagingDirectory(file, stageId);
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.createDirectory(staging, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } else {
            Files.createDirectory(staging);
        }
        Path candidate = staging.resolve(file.getFileName());
        initializer.initialize(candidate);
        checkRegular(candidate);
        verifyHeader(candidate);
        requireAbsentSidecars(candidate);
        try (var channel = FileChannel.open(candidate, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            channel.force(true);
        }

        // The synced marker permits finishing only this exact link after interruption.
        writeMarker(owner, MARKER_PREFIX + stageId);
        requireAbsentSidecars(file);
        Files.createLink(file, candidate);
        finishLink(file, owner);
        checkRegular(file);
    }

    private static void finishLink(Path file, FileChannel owner) throws IOException {
        Path candidate = publicationCandidate(file, owner);
        if (candidate == null) return;
        Path staging = candidate.getParent();
        if (supportsUnix(file)) {
            try (var directory = FileChannel.open(file.getParent(), StandardOpenOption.READ)) {
                directory.force(true);
            }
        }
        Files.delete(candidate);
        try {
            Files.delete(staging);
        } catch (DirectoryNotEmptyException preserve) {
            // Evidence unrelated to the exact publication link stays in the staging directory.
        }
    }

    private static Path publicationCandidate(Path file, FileChannel owner) throws IOException {
        Integer links = links(file);
        if (Integer.valueOf(1).equals(links)) return null;
        UUID stageId = parseMarker(readMarker(owner));
        Path staging = stageId == null ? null : stagingDirectory(file, stageId);
        Path candidate = staging == null ? null : staging.resolve(file.getFileName());
        if (candidate == null || !Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                || !Files.isSameFile(file, candidate)) {
            if (links != null) throw new IOException("Unrecognized native database link");
            return null;
        }
        if (links != null && links != 2) throw new IOException("Native database has unexpected links");
        requireAbsentSidecars(file);
        requireAbsentSidecars(candidate);
        return candidate;
    }

    private static void verifyHeader(Path file) throws IOException {
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] header = input.readNBytes(72);
            if (header.length != 72 || !Arrays.equals(Arrays.copyOf(header, 16), SQLITE_MAGIC)
                    || ByteBuffer.wrap(header).getInt(68) != Schema.NATIVE_APPLICATION_ID
                    || ByteBuffer.wrap(header).getInt(60) != Schema.VERSION) {
                throw new IOException("Unrecognized native database header");
            }
        }
    }

    private static void requireAbsentSidecars(Path file) throws IOException {
        for (String suffix : SIDECARS) {
            if (present(sidecar(file, suffix))) throw new IOException("Unexpected native initialization journal");
        }
    }

    private static void checkRegular(Path path) throws IOException {
        if (!present(path)) return;
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unexpected native storage path");
        Integer count = links(path);
        if (count != null && count != 1) throw new IOException("Native storage must not share a hard link");
    }

    private static boolean present(Path file) {
        return !Files.notExists(file, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean supportsUnix(Path file) {
        return file.getFileSystem().supportedFileAttributeViews().contains("unix");
    }

    private static Integer links(Path file) throws IOException {
        return supportsUnix(file) ? (Integer) Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS) : null;
    }

    private static Path sidecar(Path file, String suffix) {
        return file.resolveSibling(file.getFileName() + suffix);
    }

    private static Path stagingDirectory(Path file, UUID id) {
        return sidecar(file, ".initializing-" + id);
    }

    private static String readMarker(FileChannel owner) throws IOException {
        long length = owner.size();
        if (length > MARKER_SIZE) throw new IOException("Unexpected native owner metadata");
        var buffer = ByteBuffer.allocate((int) length);
        owner.position(0);
        while (buffer.hasRemaining()) {
            if (owner.read(buffer) < 0) throw new IOException("Truncated native owner metadata");
        }
        return new String(buffer.array(), StandardCharsets.US_ASCII);
    }

    private static void requireOwnedMarker(String value) throws IOException {
        // A torn first marker can exist only before publication. It grants no authority to adopt a stage.
        if (MARKER_PREFIX.startsWith(value)) return;
        if (!value.startsWith(MARKER_PREFIX) || !value.substring(MARKER_PREFIX.length()).matches("[0-9a-f-]{0,36}")) {
            throw new IOException("Unrecognized native owner metadata");
        }
    }

    private static UUID parseMarker(String value) {
        if (!value.startsWith(MARKER_PREFIX) || value.length() != MARKER_SIZE) return null;
        String suffix = value.substring(MARKER_PREFIX.length());
        try {
            UUID id = UUID.fromString(suffix);
            return id.toString().equals(suffix) ? id : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static void writeMarker(FileChannel owner, String marker) throws IOException {
        ByteBuffer bytes = StandardCharsets.US_ASCII.encode(marker);
        owner.position(0);
        while (bytes.hasRemaining()) owner.write(bytes);
        owner.force(true);
    }
}
