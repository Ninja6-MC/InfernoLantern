package com.ninja6.infernolantern.runtime;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/** Validates the texture-only Java archive emitted by the resource pack generator. */
final class JavaTextureOverride {
    static final String FILE = "java-override.zip";

    private static final int MAX_ARCHIVE = 5 * 1024 * 1024;
    private static final int MAX_TEXTURE = 1024 * 1024;
    private static final int MAX_METADATA = 4096;
    private static final String METADATA = "pack.mcmeta";
    private static final String SIDECAR = ".mcmeta";
    private static final int FRAME_SIZE = 64;
    private static final int GLOW_HEIGHT = 512;
    private static final long PNG_SIGNATURE = 0x89504E470D0A1A0AL;
    private static final int IHDR = 0x49484452;
    private static final int IDAT = 0x49444154;
    private static final int IEND = 0x49454E44;
    private static final int ANCILLARY_CHUNK = 0x20000000;
    private static final int RGBA8 = 0x08060000;
    private static final Map<String, Integer> TEXTURES = Map.of(
            "assets/infernolantern/textures/generic/ember_frame.png", FRAME_SIZE,
            "assets/infernolantern/textures/generic/frost_frame.png", FRAME_SIZE,
            "assets/infernolantern/textures/generic/ember_glow.png", GLOW_HEIGHT,
            "assets/infernolantern/textures/generic/frost_glow.png", GLOW_HEIGHT);

    private JavaTextureOverride() {}

    static byte[] read(Path path, byte[] base) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Java override must be a regular file");
        }
        byte[] snapshot;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            snapshot = input.readNBytes(MAX_ARCHIVE + 1);
        }
        if (snapshot.length == 0 || snapshot.length > MAX_ARCHIVE) {
            throw new IOException("Java override archive size out of range");
        }

        // The client uses the ZIP directory. Inspect the same bytes used for delivery
        // identity, even if the operator replaces the original archive during startup.
        Path copy = Files.createTempFile("infernolantern-override-", ".zip");
        try {
            Files.write(copy, snapshot);
            try (var zip = new ZipFile(copy.toFile())) {
                validate(zip, expectedMetadata(base));
            }
        } finally {
            Files.deleteIfExists(copy);
        }
        return snapshot;
    }

    private static Map<String, byte[]> expectedMetadata(byte[] base) throws IOException {
        var metadata = new HashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(base))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                String name = entry.getName();
                if (METADATA.equals(name) || (name.endsWith(SIDECAR)
                        && TEXTURES.getOrDefault(textureName(name), 0) == GLOW_HEIGHT)) {
                    byte[] bytes = zip.readNBytes(MAX_METADATA + 1);
                    if (bytes.length > MAX_METADATA) throw new IOException("Java base metadata exceeds its size limit");
                    metadata.put(name, bytes);
                }
            }
        }
        if (metadata.size() != 3 || !metadata.containsKey(METADATA)) {
            throw new IOException("Java base texture metadata is incomplete");
        }
        String pack = new String(metadata.get(METADATA), StandardCharsets.UTF_8);
        String baseDescription = "\"InfernoLantern generic\"";
        if (!pack.contains(baseDescription)) throw new IOException("Java base pack metadata does not match");
        metadata.put(METADATA, pack.replace(baseDescription, "\"InfernoLantern override\"").getBytes(StandardCharsets.UTF_8));
        return metadata;
    }

    private static void validate(ZipFile zip, Map<String, byte[]> metadata) throws IOException {
        if (zip.size() > TEXTURES.size() + metadata.size()) {
            throw new IOException("Too many entries in Java override");
        }
        var names = new HashSet<String>();
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            var entry = entries.nextElement();
            String name = entry.getName();
            Integer height = TEXTURES.get(name);
            byte[] expected = metadata.get(name);
            if (!names.add(name) || (height == null && expected == null)) {
                throw new IOException("Unrecognized or duplicate Java override entry");
            }
            byte[] bytes = readEntry(zip, entry, height == null ? MAX_METADATA : MAX_TEXTURE);
            if (height != null) {
                validateTexture(bytes, height);
            } else if (!Arrays.equals(expected, bytes)) {
                throw new IOException("Java override metadata does not match the generator");
            }
        }
        if (!names.contains(METADATA) || names.stream().noneMatch(TEXTURES::containsKey)) {
            throw new IOException("Java override needs pack metadata and at least one texture");
        }
        for (String name : metadata.keySet()) {
            if (!METADATA.equals(name) && names.contains(name) != names.contains(textureName(name))) {
                throw new IOException("Java override needs matching glow texture and animation metadata");
            }
        }
    }

    private static String textureName(String name) {
        return name.substring(0, name.length() - SIDECAR.length());
    }

    private static byte[] readEntry(ZipFile zip, ZipEntry entry, int limit) throws IOException {
        if (entry.getSize() <= 0 || entry.getSize() > limit) {
            throw new IOException("Java override entry size out of range");
        }
        try (var input = zip.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(limit + 1);
            var crc = new CRC32();
            crc.update(bytes);
            if (bytes.length != entry.getSize() || crc.getValue() != entry.getCrc()) {
                throw new IOException("Corrupt Java override entry");
            }
            return bytes;
        }
    }

    private static void validateTexture(byte[] bytes, int height) throws IOException {
        var data = ByteBuffer.wrap(bytes);
        if (data.remaining() < 8 || data.getLong() != PNG_SIGNATURE) {
            throw new IOException("Invalid PNG signature");
        }
        var compressed = new ByteArrayOutputStream();
        boolean header = false;
        boolean dataEnded = false;
        while (data.remaining() >= 12) {
            int length = data.getInt();
            int typeOffset = data.position();
            int type = data.getInt();
            if (length < 0 || length > data.remaining() - 4) throw new IOException("Truncated PNG chunk");
            int payload = data.position();
            data.position(payload + length);
            var crc = new CRC32();
            crc.update(bytes, typeOffset, 4 + length);
            if (crc.getValue() != Integer.toUnsignedLong(data.getInt())) throw new IOException("Corrupt PNG chunk");

            if (!header) {
                if (type != IHDR || length != 13
                        || data.getInt(payload) != FRAME_SIZE || data.getInt(payload + 4) != height
                        || data.getInt(payload + 8) != RGBA8 || data.get(payload + 12) != 0) {
                    throw new IOException("Expected original dimensions and non-interlaced 8-bit RGBA");
                }
                header = true;
            } else if (type == IDAT && !dataEnded) {
                compressed.write(bytes, payload, length);
            } else if (type == IEND && length == 0 && !data.hasRemaining()) {
                validateRows(compressed.toByteArray(), height);
                return;
            } else if ((type & ANCILLARY_CHUNK) == 0) {
                throw new IOException("Unsupported critical PNG chunk");
            } else if (compressed.size() > 0) {
                dataEnded = true;
            }
        }
        throw new IOException("Incomplete PNG texture");
    }

    private static void validateRows(byte[] compressed, int height) throws IOException {
        int stride = 1 + FRAME_SIZE * 4;
        int expected = stride * height;
        byte[] rows = new byte[expected + 1];
        try (var inflater = new Inflater()) {
            inflater.setInput(compressed);
            int total = 0;
            while (!inflater.finished() && total < rows.length) {
                int count = inflater.inflate(rows, total, rows.length - total);
                if (count == 0) break;
                total += count;
            }
            if (total != expected || !inflater.finished() || inflater.getRemaining() != 0) {
                throw new IOException("Invalid PNG scanlines");
            }
        } catch (DataFormatException corrupt) {
            throw new IOException("Corrupt PNG scanlines", corrupt);
        }
        for (int offset = 0; offset < expected; offset += stride) {
            if (Byte.toUnsignedInt(rows[offset]) > 4) throw new IOException("Invalid PNG row filter");
        }
    }
}
