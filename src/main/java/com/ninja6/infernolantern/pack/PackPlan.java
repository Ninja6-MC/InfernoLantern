package com.ninja6.infernolantern.pack;

import com.ninja6.infernolantern.catalog.CatalogConfig.Definition;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Exact artifact identities from the operator's validated asset set. */
public record PackPlan(Identity identity, Set<Models> models, List<Download> downloads, boolean required) {
    public enum Mode {
        DEFAULT(List.of(Slot.BASE)),
        STACKED(List.of(Slot.BASE, Slot.OVERRIDE)),
        COMBINED(List.of(Slot.COMBINED));

        private final List<Slot> slots;

        Mode(List<Slot> slots) {
            this.slots = slots;
        }
    }

    public enum Edition { JAVA, BEDROCK }
    public enum Slot { BASE, OVERRIDE, COMBINED }

    public record Archive(Slot slot, String sha256) {
        public Archive {
            Objects.requireNonNull(slot);
            digest(sha256, 64);
        }
    }

    public record Identity(Edition edition, Mode mode, List<Archive> packs, String mappingsSha256) {
        public Identity {
            Objects.requireNonNull(edition);
            Objects.requireNonNull(mode);
            packs = List.copyOf(packs);
            if (!packs.stream().map(Archive::slot).toList().equals(mode.slots)
                    || packs.stream().map(Archive::sha256).distinct().count() != packs.size()) {
                throw new IllegalArgumentException("Invalid pack set or load order");
            }
            if (edition == Edition.BEDROCK) {
                digest(mappingsSha256, 64);
            } else if (mappingsSha256 != null) {
                throw new IllegalArgumentException("Java does not use bridge mappings");
            }
        }

        public boolean pluginDelivered() {
            return edition == Edition.JAVA && mode != Mode.COMBINED;
        }
    }

    public record Download(Archive archive, URI uri, String sha1) {
        public Download {
            Objects.requireNonNull(archive);
            Objects.requireNonNull(uri);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("Pack URL must be an HTTP(S) download");
            }
            digest(sha1, 40);
        }
    }

    /** The compact/deployed model pair validated from the archives and applicable bridge mapping. */
    public record Models(String compact, String expanded) {
        public Models {
            if (!modelId(compact) || !modelId(expanded) || compact.equals(expanded)) {
                throw new IllegalArgumentException("Distinct resource IDs are required for the two model stages");
            }
        }

        public static Models of(Definition definition) {
            return new Models(definition.geometry().compactModel(), definition.geometry().expandedModel());
        }

        private static boolean modelId(String id) {
            return id != null && id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+");
        }
    }

    public PackPlan {
        Objects.requireNonNull(identity);
        models = Set.copyOf(models);
        downloads = List.copyOf(downloads);
        if (models.isEmpty()) throw new IllegalArgumentException("No verified lantern models in pack set");
        if (identity.pluginDelivered()) {
            if (!downloads.stream().map(Download::archive).toList().equals(identity.packs())) {
                throw new IllegalArgumentException("Each verified Java archive needs its ordered download details");
            }
        } else if (!downloads.isEmpty()) {
            throw new IllegalArgumentException("Server-managed packs may not be sent by the plugin");
        }
    }

    /** Catalog validator after the deployment binding validates actual archive/mapping coverage. */
    public void requireModels(Definition definition) {
        if (!models.contains(Models.of(definition))) {
            throw new IllegalArgumentException("The verified pack set does not supply this model pair");
        }
    }

    private static void digest(String hash, int length) {
        if (hash == null || hash.length() != length || !hash.matches("[a-f0-9]+")) {
            throw new IllegalArgumentException("Expected a canonical hexadecimal archive digest");
        }
    }
}
