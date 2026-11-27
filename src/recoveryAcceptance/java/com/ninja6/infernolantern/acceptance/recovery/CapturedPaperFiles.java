package com.ninja6.infernolantern.acceptance.recovery;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.TagType;
import net.minecraft.nbt.visitors.CollectToTag;

/** Bounded reads of sealed files produced by the disposable fixture. */
final class CapturedPaperFiles {
    private static final int MAX_INFLATED = 4 * 1024 * 1024;
    private static final byte[] GZIP_HEADER = HexFormat.of().parseHex("1f8b08000000000000ff");

    private CapturedPaperFiles() {}

    static CompoundTag compound(byte[] bytes) throws IOException {
        try (var root = new DataInputStream(new ByteArrayInputStream(bytes))) {
            var visitor = new UniqueTags();
            NbtIo.parse(root, visitor, NbtAccounter.create(MAX_INFLATED));
            if (root.read() != -1 || !(visitor.getResult() instanceof CompoundTag compound)) {
                throw new IOException("Expected exactly one captured NBT compound");
            }
            return compound;
        }
    }

    static byte[] sealed(Path directory, Path leaf, int limit, String expected) throws IOException {
        byte[] bytes = readStable(directory, leaf, limit);
        if (!sha256(bytes).equals(expected)) throw new IOException("Sealed fixture file changed");
        return bytes;
    }

    static byte[] readStable(Path directory, Path leaf, int limit) throws IOException {
        Path absolute = directory.toAbsolutePath().normalize();
        try (var opened = Files.newDirectoryStream(absolute.getRoot())) {
            if (!(opened instanceof SecureDirectoryStream<Path> secure)) throw new IOException("Secure descent unavailable");
            return descend(secure, absolute, 0, leaf, limit);
        }
    }

    private static byte[] descend(SecureDirectoryStream<Path> current, Path directory, int depth, Path leaf, int limit) throws IOException {
        if (depth < directory.getNameCount()) {
            try (var child = current.newDirectoryStream(directory.getName(depth), LinkOption.NOFOLLOW_LINKS)) {
                return descend(child, directory, depth + 1, leaf, limit);
            }
        }
        var view = current.getFileAttributeView(leaf, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        BasicFileAttributes before = view.readAttributes();
        if (!before.isRegularFile() || before.fileKey() == null || linkCount(directory.resolve(leaf)) != 1) {
            throw new IOException("Unexpected capture leaf identity");
        }
        if (before.size() > limit) throw new Bound();
        byte[] bytes;
        try (var channel = current.newByteChannel(leaf, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
             var input = Channels.newInputStream(channel)) {
            bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new Bound();
        }
        BasicFileAttributes after = view.readAttributes();
        if (!after.isRegularFile() || !Objects.equals(before.fileKey(), after.fileKey())
                || !before.lastModifiedTime().equals(after.lastModifiedTime()) || before.size() != after.size()
                || after.size() != bytes.length || linkCount(directory.resolve(leaf)) != 1) {
            throw new IOException("Capture leaf changed during read");
        }
        return bytes;
    }

    private static int linkCount(Path path) throws IOException {
        return (Integer) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
    }

    /** Accept the exact single-member gzip wrapper written by the pinned Paper build. */
    static byte[] inflateGzip(byte[] input) throws IOException, DataFormatException {
        int trailerSize = 8;
        if (input.length <= GZIP_HEADER.length + trailerSize
                || !Arrays.equals(input, 0, GZIP_HEADER.length, GZIP_HEADER, 0, GZIP_HEADER.length)) {
            throw new IOException("Unsupported player save gzip wrapper");
        }
        int compressedSize = input.length - GZIP_HEADER.length - trailerSize;
        var inflater = new Inflater(true);
        try (var output = new ByteArrayOutputStream()) {
            inflater.setInput(input, GZIP_HEADER.length, compressedSize);
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0 && !inflater.finished()) throw new IOException("Incomplete player save gzip member");
                if (output.size() + count > MAX_INFLATED) throw new Bound();
                output.write(buffer, 0, count);
            }
            if (inflater.getBytesRead() != compressedSize) throw new IOException("Extra compressed data");
            byte[] bytes = output.toByteArray();
            var crc = new CRC32();
            crc.update(bytes);
            var trailer = ByteBuffer.wrap(input, input.length - trailerSize, trailerSize).order(ByteOrder.LITTLE_ENDIAN);
            if (trailer.getInt() != (int) crc.getValue() || trailer.getInt() != bytes.length) {
                throw new IOException("Player save gzip checksum or length differs");
            }
            return bytes;
        } finally {
            inflater.end();
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** CompoundTag's map cannot retain duplicate keys for validation after parsing. */
    private static final class UniqueTags extends CollectToTag {
        private final Map<Integer, Set<String>> fields = new HashMap<>();

        @Override
        public EntryResult visitEntry(TagType<?> type, String name) {
            if (!fields.computeIfAbsent(depth(), ignored -> new HashSet<>()).add(name)) {
                throw new IllegalArgumentException("Duplicate player save NBT key");
            }
            return super.visitEntry(type, name);
        }

        @Override
        public ValueResult visitContainerEnd() {
            fields.remove(depth());
            return super.visitContainerEnd();
        }
    }

    static final class Bound extends IOException {}
}
