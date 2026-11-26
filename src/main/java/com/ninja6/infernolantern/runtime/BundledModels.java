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
record BundledModels(Set<Models> coverage, JavaArchive javaBase, JavaArchive javaOverride)
        implements DefinitionValidator {
    private static final String PREFIX = "/resource-packs/";
    private static final int MAX_ASSET_BYTES = 32 * 1024 * 1024;

    record JavaArchive(PackPlan.Archive archive, String sha1) {
        PackPlan.Download download(URI url) {
            if (!"https".equalsIgnoreCase(url.getScheme())) {
                throw new IllegalArgumentException("Bundled Java pack requires HTTPS");
            }
            return new PackPlan.Download(archive, url, sha1);
        }
    }

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
        var javaPacks = object(packs.get("java"));
        var javaBase = javaArchive(resources, javaPacks.get("generic"), "infernolantern-generic-java.zip", PackPlan.Slot.BASE);
        JavaArchive javaOverride;
        try {
            javaOverride = javaArchive(resources, javaPacks.get("override"), "infernolantern-override-java.zip", PackPlan.Slot.OVERRIDE);
            if (javaOverride.archive().sha256().equals(javaBase.archive().sha256())) {
                throw new IllegalArgumentException("The bundled Java override must differ from the base archive");
            }
        } catch (IllegalArgumentException invalidOverride) {
            // A missing or invalid optional archive does not invalidate the base catalog.
            javaOverride = null;
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
        return new BundledModels(Set.copyOf(pairs), javaBase, javaOverride);
    }

    @Override
    public void validate(Definition definition) {
        if (!coverage.contains(Models.of(definition))) {
            throw new IllegalArgumentException("Model pair is absent from the bundled pack set");
        }
    }

    PackPlan javaPlan(URI url, URI overrideUrl, boolean required) {
        if (overrideUrl != null && javaOverride == null) return null;
        var downloads = overrideUrl == null ? List.of(javaBase.download(url))
                : List.of(javaBase.download(url), javaOverride.download(overrideUrl));
        var mode = overrideUrl == null ? PackPlan.Mode.DEFAULT : PackPlan.Mode.STACKED;
        var identity = new PackPlan.Identity(PackPlan.Edition.JAVA, mode,
                downloads.stream().map(PackPlan.Download::archive).toList(), null);
        return new PackPlan(identity, coverage, downloads, required);
    }

    private static JavaArchive javaArchive(Function<String, InputStream> resources, Object entry,
            String name, PackPlan.Slot slot) {
        var artifact = object(entry);
        byte[] bytes = check(resources, artifact, name);
        String sha1 = digest("SHA-1", bytes);
        if (!sha1.equals(artifact.get("sha1"))) {
            throw new IllegalArgumentException("Bundled Java pack download digest mismatch");
        }
        return new JavaArchive(new PackPlan.Archive(slot, (String) artifact.get("sha256")), sha1);
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
