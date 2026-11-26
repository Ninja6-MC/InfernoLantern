package com.ninja6.infernolantern.runtime;

import com.ninja6.infernolantern.catalog.CatalogConfig.Definition;
import com.ninja6.infernolantern.catalog.CatalogService.DefinitionValidator;
import com.ninja6.infernolantern.catalog.CatalogYaml;
import com.ninja6.infernolantern.pack.PackPlan;
import com.ninja6.infernolantern.pack.PackPlan.Models;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** The bundled generic archives and mapping share one build-generated model inventory. */
record BundledModels(Set<Models> coverage, PackPlan.Archive javaArchive, String javaSha1)
        implements DefinitionValidator {
    private static final String PREFIX = "/resource-packs/";
    private static final int MAX_ASSET_BYTES = 32 * 1024 * 1024;

    static BundledModels load() {
        return load(BundledModels.class::getResourceAsStream);
    }

    static BundledModels load(Function<String, InputStream> resources) {
        byte[] inventory = read(resources, "pack-set.json", 1_048_576);
        String checksums = new String(read(resources, "SHA256SUMS", 4_096), StandardCharsets.US_ASCII);
        String expectedInventory = digest("SHA-256", inventory) + "  pack-set.json";
        if (checksums.lines().filter(expectedInventory::equals).count() != 1) {
            throw new IllegalArgumentException("Bundled pack inventory digest mismatch");
        }
        var manifest = CatalogYaml.read(new String(inventory, StandardCharsets.UTF_8));
        if (!Integer.valueOf(1).equals(manifest.get("schema"))) {
            throw new IllegalArgumentException("Unsupported bundled pack manifest");
        }
        var packs = object(manifest.get("packs"));
        var javaPack = object(object(packs.get("java")).get("generic"));
        byte[] javaArchive = check(resources, javaPack, "infernolantern-generic-java.zip");
        String javaSha1 = digest("SHA-1", javaArchive);
        if (!javaSha1.equals(javaPack.get("sha1"))) {
            throw new IllegalArgumentException("Bundled Java pack download digest mismatch");
        }
        check(resources, object(object(packs.get("bedrock")).get("generic")), "infernolantern-generic-bedrock.mcpack");
        check(resources, object(manifest.get("mappings")), "infernolantern-generic-mappings.json");

        var pairs = new HashSet<Models>();
        for (Object entry : object(manifest.get("variants")).values()) {
            Map<?, ?> variant = object(entry);
            if (!(variant.get("compact") instanceof String compact) || !(variant.get("expanded") instanceof String expanded)
                    || !pairs.add(new Models(compact, expanded))) {
                throw new IllegalArgumentException("Invalid bundled model inventory");
            }
        }
        if (pairs.isEmpty()) throw new IllegalArgumentException("Missing bundled model pairs");
        return new BundledModels(Set.copyOf(pairs),
                new PackPlan.Archive(PackPlan.Slot.BASE, (String) javaPack.get("sha256")), javaSha1);
    }

    @Override
    public void validate(Definition definition) {
        if (!coverage.contains(Models.of(definition))) {
            throw new IllegalArgumentException("Model pair is absent from the bundled pack set");
        }
    }

    PackPlan javaDefault(URI url) {
        if (!"https".equalsIgnoreCase(url.getScheme())) {
            throw new IllegalArgumentException("Bundled Java pack requires HTTPS");
        }
        var identity = new PackPlan.Identity(PackPlan.Edition.JAVA, PackPlan.Mode.DEFAULT, List.of(javaArchive), null);
        return new PackPlan(identity, coverage, List.of(new PackPlan.Download(javaArchive, url, javaSha1)), false);
    }

    private static Map<?, ?> object(Object value) {
        if (value instanceof Map<?, ?> map) return map;
        throw new IllegalArgumentException("Invalid bundled pack manifest");
    }

    private static byte[] check(Function<String, InputStream> resources, Map<?, ?> artifact, String name) {
        if (!name.equals(artifact.get("file"))) throw new IllegalArgumentException("Invalid bundled pack identity");
        byte[] bytes = read(resources, name, MAX_ASSET_BYTES);
        if (!digest("SHA-256", bytes).equals(artifact.get("sha256"))) {
            throw new IllegalArgumentException("Bundled pack digest mismatch");
        }
        return bytes;
    }

    private static String digest(String algorithm, byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(data));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static byte[] read(Function<String, InputStream> resources, String name, int limit) {
        try (InputStream input = resources.apply(PREFIX + name)) {
            if (input == null) throw new IOException("Missing bundled pack resource");
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length == 0 || bytes.length > limit) throw new IOException("Invalid bundled pack resource size");
            return bytes;
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot read bundled pack resource", failure);
        }
    }
}
