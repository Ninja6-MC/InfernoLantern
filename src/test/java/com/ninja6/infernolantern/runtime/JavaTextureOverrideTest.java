package com.ninja6.infernolantern.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class JavaTextureOverrideTest {
    private static final String TEXTURES = "assets/infernolantern/textures/generic/";
    private static final String EMBER = TEXTURES + "ember_glow.png";
    private static final String FROST = TEXTURES + "frost_glow.png";
    private static final String FROST_FRAME = TEXTURES + "frost_frame.png";
    private static final String SIDECAR = ".mcmeta";
    private static final String METADATA = "pack.mcmeta";

    @TempDir Path data;

    @Test void snapshotAcceptsGeneratorOutputAndAlternateTextureWithItsOriginalSidecar() throws Exception {
        byte[] base = bundled("generic");
        byte[] generator = bundled("override");
        assertArrayEquals(generator, read(generator, base));
        byte[] alternate = alternateTextureArchive();
        assertFalse(Arrays.equals(generator, alternate));
        assertArrayEquals(alternate, read(alternate, base));
    }

    @Test void invalidPathsMetadataAndPngAreRejected() throws Exception {
        byte[] base = bundled("generic");
        var generic = entries(base);
        var override = entries(bundled("override"));
        byte[] texture = override.get(EMBER);
        byte[] metadata = override.get(METADATA);
        byte[] sidecar = override.get(EMBER + SIDECAR);
        byte[] corrupt = texture.clone();
        corrupt[corrupt.length - 1] ^= 1;
        for (var invalid : java.util.List.of(
                Map.of(METADATA, metadata, "../other.png", texture),
                Map.of(METADATA, metadata, TEXTURES + "other.png", texture),
                Map.of(METADATA, metadata, EMBER, texture),
                Map.of(METADATA, metadata, EMBER, texture, EMBER + SIDECAR, new byte[]{1}),
                Map.of(METADATA, metadata, EMBER, texture, EMBER + SIDECAR, sidecar, FROST + SIDECAR, sidecar),
                Map.of(METADATA, metadata, EMBER, generic.get(FROST_FRAME), EMBER + SIDECAR, sidecar),
                Map.of(METADATA, metadata, EMBER, corrupt, EMBER + SIDECAR, sidecar),
                Map.of(METADATA, new byte[]{1}, EMBER, texture, EMBER + SIDECAR, sidecar),
                Map.of(METADATA, metadata),
                Map.of(EMBER, texture, EMBER + SIDECAR, sidecar))) {
            assertThrows(IOException.class, () -> read(archive(invalid), base));
        }
    }

    @Test void centralDirectoryAndBothSizeBoundsAreRequired() throws Exception {
        byte[] base = bundled("generic");
        var override = entries(bundled("override"));
        var expanded = new LinkedHashMap<>(override);
        expanded.put(FROST, expanded.get(EMBER));
        expanded.put(FROST + SIDECAR, expanded.get(EMBER + SIDECAR));
        // The names have equal lengths. Use a ZIP with repeated names in its directory.
        byte[] duplicate = new String(archive(expanded), StandardCharsets.ISO_8859_1)
                .replace(FROST, EMBER).getBytes(StandardCharsets.ISO_8859_1);
        assertThrows(IOException.class, () -> read(duplicate, base));
        byte[] good = archive(override);
        assertThrows(IOException.class, () -> read(Arrays.copyOf(good, good.length - 22), base));
        assertThrows(IOException.class, () -> read(new byte[5 * 1024 * 1024 + 1], base));
        override.put(EMBER, new byte[1024 * 1024 + 1]);
        assertThrows(IOException.class, () -> read(archive(override), base));
    }

    @Test void rejectsAnOverrideSymlink() throws Exception {
        var target = Files.write(data.resolve("target.zip"), bundled("override"));
        Path link = Files.createSymbolicLink(data.resolve(JavaTextureOverride.FILE), target);
        assertThrows(IOException.class, () -> JavaTextureOverride.read(link, bundled("generic")));
    }

    static byte[] alternateTextureArchive() throws IOException {
        var base = entries(bundled("generic"));
        var override = entries(bundled("override"));
        return archive(Map.of(METADATA, override.get(METADATA),
                FROST, base.get(FROST), FROST + SIDECAR, base.get(FROST + SIDECAR)));
    }

    private byte[] read(byte[] bytes, byte[] base) throws IOException {
        Path path = Files.write(data.resolve(JavaTextureOverride.FILE), bytes);
        return JavaTextureOverride.read(path, base);
    }

    private static byte[] bundled(String label) throws IOException {
        try (var input = JavaTextureOverrideTest.class.getResourceAsStream(
                "/resource-packs/infernolantern-" + label + "-java.zip")) {
            return input.readAllBytes();
        }
    }

    private static Map<String, byte[]> entries(byte[] bytes) throws IOException {
        var contents = new LinkedHashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                contents.put(entry.getName(), zip.readAllBytes());
            }
        }
        return contents;
    }

    private static byte[] archive(Map<String, byte[]> contents) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var entry : contents.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
