package com.ninja6.infernolantern.repository;

import java.util.*;

/** Immutable, server-independent snapshots. No Bukkit objects cross the database boundary. */
public final class RepositoryRecords {
    private RepositoryRecords() {}
    public static final int MAX_PAGE = 256;
    public static final int MAX_PAYLOAD = 1_048_576;
    public static final int MAX_COMPONENTS = 4096;
    public enum Form { PORTABLE, GROUND, COMPACT, EXPANDED, LOST }
    public enum VariantStatus { ACTIVE, RETIRED }
    public enum Phase { INTENT, EFFECTS_APPLIED, RECONCILING, COMPLETE, ROLLED_BACK }
    public enum ComponentKind { BLOCK, ENTITY }
    public enum AwardStatus { PENDING, DELIVERED, PAUSED }
    public enum PaymentPhase { RESERVED, COMMITTED, REFUND_PENDING, REFUNDED, RECONCILING }
    public enum HpPhase { NOT_APPLIED, APPLIED, RECONCILING }

    public record Document(int version, String content) {
        public Document { if (version < 1) throw new IllegalArgumentException("Document version"); text(content, MAX_PAYLOAD); }
    }
    public record Variant(String id, long definitionRevision, VariantStatus status, Document lastValidDefinition, long rowVersion) {
        public Variant { identifier(id); nonnegative(definitionRevision); Objects.requireNonNull(status); Objects.requireNonNull(lastValidDefinition); nonnegative(rowVersion); }
    }
    /** One durable catalog publication; unmanaged legacy/prototype definitions are not included. */
    public record CatalogSnapshot(long revision, Document configuration, List<Variant> variants) {
        public CatalogSnapshot { nonnegative(revision); Objects.requireNonNull(configuration); variants=List.copyOf(variants); }
    }
    public record Position(UUID world, int x, int y, int z, String orientation) {
        public Position { Objects.requireNonNull(world); text(orientation, 64); }
    }
    public record Tower(UUID id, String variantId, double hp, double maximumHp, Form form,
                        long generation, long deploymentRevision, UUID controller, Position position,
                        boolean light, boolean defence, long expandAfter, long repairAfter) {
        public Tower {
            Objects.requireNonNull(id); identifier(variantId); Objects.requireNonNull(form);
            if (!Double.isFinite(hp) || !Double.isFinite(maximumHp) || maximumHp <= 0 || hp < 0 || hp > maximumHp) throw new IllegalArgumentException("HP bounds");
            nonnegative(generation); nonnegative(deploymentRevision); nonnegative(expandAfter); nonnegative(repairAfter);
            if ((form == Form.EXPANDED) != (controller != null)) throw new IllegalArgumentException("Controller only exists while expanded");
            if ((form == Form.COMPACT || form == Form.EXPANDED) != (position != null)) throw new IllegalArgumentException("Position only exists for a structure");
        }
    }
    public record Ground(UUID entity, UUID world, int chunkX, int chunkZ, UUID deliveryOperation, String status) {
        public Ground { Objects.requireNonNull(entity); Objects.requireNonNull(world); Objects.requireNonNull(deliveryOperation); text(status, 64); }
    }
    /** A declared occupied/clearance voxel, not necessarily a physically replaced block. */
    public record Cell(UUID world,int x,int y,int z) { public Cell { Objects.requireNonNull(world); } }
    public record Reservation(Cell cell,UUID tower,UUID operation) {
        public Reservation { Objects.requireNonNull(cell);Objects.requireNonNull(tower); }
    }
    public record Component(String id, ComponentKind kind, UUID world, int x, int y, int z,
                            String originalState, String expectedState, UUID entity, String entityType) {
        public Component {
            identifier(id); Objects.requireNonNull(kind); Objects.requireNonNull(world);
            if (kind == ComponentKind.BLOCK) {
                text(originalState, 8192); text(expectedState, 8192);
                if (entity != null || entityType != null) throw new IllegalArgumentException("Block component entity");
            } else {
                Objects.requireNonNull(entity); text(entityType, 256);
                if (originalState != null || expectedState != null) throw new IllegalArgumentException("Entity component block state");
            }
        }
    }
    public record State(Tower tower, Ground ground, List<Component> components) {
        public State {
            Objects.requireNonNull(tower); components = components.stream().sorted(Comparator.comparing(Component::id)).toList();
            if ((tower.form() == Form.GROUND) != (ground != null)) throw new IllegalArgumentException("Ground representation required only for GROUND");
            boolean placed = tower.form() == Form.COMPACT || tower.form() == Form.EXPANDED;
            if (placed == components.isEmpty() || components.size() > MAX_COMPONENTS) throw new IllegalArgumentException("Structure manifest bounds");
            var ids = new HashSet<String>();
            for (Component component : components) {
                if (!ids.add(component.id()) || !component.world().equals(tower.position().world())) throw new IllegalArgumentException("Component ownership/world");
            }
        }
    }
    public record TowerSnapshot(State state, long rowVersion, UUID activeOperation) {
        public TowerSnapshot { Objects.requireNonNull(state); nonnegative(rowVersion); }
        public boolean mutationReady() { return activeOperation == null; }
    }
    public record Intent(UUID id, UUID towerId, long expectedRowVersion, long expectedGeneration,
                         long expectedDeploymentRevision, String kind, State after, Document effects) {
        public Intent {
            Objects.requireNonNull(id); Objects.requireNonNull(towerId); nonnegative(expectedRowVersion);
            if (expectedRowVersion > Long.MAX_VALUE - 2) throw new IllegalArgumentException("Row version exhausted");
            nonnegative(expectedGeneration); nonnegative(expectedDeploymentRevision); text(kind, 128); Objects.requireNonNull(after); Objects.requireNonNull(effects);
            if (!towerId.equals(after.tower().id())) throw new IllegalArgumentException("Intent tower mismatch");
        }
    }
    public record Operation(Intent intent, State before, Phase phase, long rowVersion, String evidence) {
        public Operation { Objects.requireNonNull(intent); Objects.requireNonNull(before); Objects.requireNonNull(phase); nonnegative(rowVersion); text(evidence, MAX_PAYLOAD); }
        public boolean pending() { return phase != Phase.COMPLETE && phase != Phase.ROLLED_BACK; }
    }
    public record Award(UUID id, String sourceKey, UUID recipient, String variantId, long quantity, AwardStatus status, long rowVersion) {
        public Award {
            Objects.requireNonNull(id); text(sourceKey, 1024); Objects.requireNonNull(recipient); identifier(variantId); Objects.requireNonNull(status); nonnegative(rowVersion);
            if (quantity < 1) throw new IllegalArgumentException("Award quantity");
        }
    }
    public static final class ResourceBytes {
        private final byte[] bytes;
        public ResourceBytes(byte[] bytes) {
            if (bytes == null || bytes.length > MAX_PAYLOAD) throw new IllegalArgumentException("Resource snapshot size");
            this.bytes = bytes.clone();
        }
        public byte[] copy() { return bytes.clone(); }
        @Override public boolean equals(Object other) { return other instanceof ResourceBytes that && Arrays.equals(bytes, that.bytes); }
        @Override public int hashCode() { return Arrays.hashCode(bytes); }
    }
    public record Escrow(UUID operation, UUID towerId, long deploymentRevision, UUID contributor,
                         int resourceFormat, ResourceBytes resources, PaymentPhase payment, HpPhase hpPhase, long rowVersion) {
        public Escrow {
            Objects.requireNonNull(operation); Objects.requireNonNull(towerId); nonnegative(deploymentRevision); Objects.requireNonNull(contributor);
            if (resourceFormat < 1) throw new IllegalArgumentException("Resource format");
            Objects.requireNonNull(resources); Objects.requireNonNull(payment); Objects.requireNonNull(hpPhase); nonnegative(rowVersion);
        }
    }
    static void identifier(String value) { if (value == null || !value.matches("[a-z0-9][a-z0-9_.:-]{0,127}")) throw new IllegalArgumentException("Identifier"); }
    static void nonnegative(long value) { if (value < 0) throw new IllegalArgumentException("Negative counter/time"); }
    static void text(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max
                || !java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(value)
                || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > max)
            throw new IllegalArgumentException("UTF-8 text bounds");
    }
}
