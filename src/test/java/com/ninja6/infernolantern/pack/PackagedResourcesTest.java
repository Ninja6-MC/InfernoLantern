package com.ninja6.infernolantern.pack;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

final class PackagedResourcesTest {
    @Test void jarShipsTheCompletePackSetAndItsHashes() throws Exception {
        try (ZipFile jar = new ZipFile(Path.of(System.getProperty("pluginJar")).toFile())) {
            var files = jar.stream().filter(entry -> !entry.isDirectory() && entry.getName().startsWith("resource-packs/"))
                    .toList();
            assertEquals(9, files.size());
            byte[] sums = jar.getInputStream(jar.getEntry("resource-packs/SHA256SUMS")).readAllBytes();
            var digests = new HashMap<String, String>();
            for (String line : new String(sums, StandardCharsets.US_ASCII).lines().toList()) {
                String[] pair = line.split("  ", 2);
                assertNull(digests.put("resource-packs/" + pair[1], pair[0]));
            }
            var sha256 = MessageDigest.getInstance("SHA-256");
            for (var file : files) {
                if (file.getName().endsWith("/SHA256SUMS")) continue;
                byte[] bytes = jar.getInputStream(file).readAllBytes();
                assertEquals(HexFormat.of().formatHex(sha256.digest(bytes)), digests.remove(file.getName()));
            }
            assertTrue(digests.isEmpty());
            byte[] java = jar.getInputStream(jar.getEntry("resource-packs/infernolantern-generic-java.zip")).readAllBytes();
            byte[] bedrock = jar.getInputStream(jar.getEntry("resource-packs/infernolantern-generic-bedrock.mcpack")).readAllBytes();
            checkVisibleTones(java, "assets/infernolantern/textures/generic/");
            checkVisibleTones(bedrock, "textures/infernolantern/generic/");
        }
    }

    private static void checkVisibleTones(byte[] archive, String prefix) throws IOException {
        var textures = new HashMap<String, BufferedImage>();
        try (var entries = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (var entry = entries.getNextEntry(); entry != null; entry = entries.getNextEntry()) {
                if (entry.getName().startsWith(prefix) && entry.getName().endsWith("_glow.png")) {
                    textures.put(entry.getName(), ImageIO.read(new ByteArrayInputStream(entries.readAllBytes())));
                }
            }
        }
        BufferedImage ember = textures.get(prefix + "ember_glow.png"), frost = textures.get(prefix + "frost_glow.png");
        assertNotNull(ember);
        assertNotNull(frost);
        assertEquals(ember.getWidth(), frost.getWidth());
        assertEquals(ember.getHeight(), frost.getHeight());
        int differences = 0;
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < ember.getWidth(); x++) {
                int original = ember.getRGB(x, y), variant = frost.getRGB(x, y);
                assertEquals(original >>> 24, variant >>> 24);
                if ((original >>> 24) > 0 && original != variant) differences++;
            }
        }
        assertTrue(differences > 64, "Visible first-frame accents must differ");
    }
}
