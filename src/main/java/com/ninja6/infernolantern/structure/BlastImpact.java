package com.ninja6.infernolantern.structure;

import com.ninja6.infernolantern.repository.CommitPermit;
import com.ninja6.infernolantern.repository.RepositoryRecords.Component;
import com.ninja6.infernolantern.repository.RepositoryRecords.State;
import com.ninja6.infernolantern.repository.RepositoryRecords.Tower;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.ninja6.infernolantern.repository.RepositoryRecords.MAX_COMPONENTS;
import static com.ninja6.infernolantern.repository.RepositoryRecords.repairDeadline;

/** Complete, caller-proven exposure for one tower in one native explosion. */
public record BlastImpact(UUID explosion, UUID world, Set<String> affected, double damage,
                          double multiplier, boolean respectProtection, boolean expanded,
                          long damageAtMillis, long repairCoolingMillis) {
    public enum Decision { ALLOW, DENY, UNKNOWN }

    /**
     * Current same-explosion proof and the independent right to mutate owned components.
     * Revoke the permit if the proof loses authority before the durable completion.
     */
    public record Observation(BlastImpact impact, Map<String, Decision> protection,
                              boolean completeExposure, boolean mayMutateOwnedComponents, CommitPermit permit) {
        public Observation {
            Objects.requireNonNull(impact);
            protection = Map.copyOf(protection);
            Objects.requireNonNull(permit);
        }
    }

    /** Called on the world owner with the full authoritative pre-effect snapshot. */
    @FunctionalInterface
    public interface Authority {
        Observation inspect(BlastImpact impact, State before);
    }

    public BlastImpact {
        Objects.requireNonNull(explosion);
        Objects.requireNonNull(world);
        affected = Collections.unmodifiableSet(new TreeSet<>(affected));
        if (affected.isEmpty() || affected.size() > MAX_COMPONENTS
                || affected.stream().anyMatch(id -> !id.matches("[a-z0-9][a-z0-9._:-]{0,127}"))
                || !Double.isFinite(damage) || damage < 0
                || !Double.isFinite(multiplier) || multiplier < 0
                || damageAtMillis < 0 || repairCoolingMillis < 0) {
            throw new IllegalArgumentException("Blast impact bounds");
        }
        if (damage == 0) damage = 0;
        if (multiplier == 0) multiplier = 0;
    }

    public static BlastImpact respectingProtection(UUID explosion, UUID world, Set<String> affected,
                                                    double damage, double multiplier, boolean expanded,
                                                    long damageAtMillis, long repairCoolingMillis) {
        return new BlastImpact(explosion, world, affected, damage, multiplier, true, expanded,
                damageAtMillis, repairCoolingMillis);
    }

    public UUID operation(UUID tower) {
        return UUID.nameUUIDFromBytes(("infernolantern:blast:v1:" + world + ":" + explosion + ":" + tower)
                .getBytes(StandardCharsets.UTF_8));
    }

    /** Exact delivery only. A subsequent blast never inherits protection from this one. */
    public boolean protectsOriginDrop(State ground, UUID entity) {
        return ground.ground() != null && ground.ground().entity().equals(entity) && world.equals(ground.ground().world())
                && operation(ground.tower().id()).equals(ground.ground().deliveryOperation());
    }

    public boolean matches(State before) {
        return before.tower().position() != null && world.equals(before.tower().position().world())
                && expanded == (before.tower().controller() != null)
                && before.components().stream().map(Component::id).collect(Collectors.toSet()).containsAll(affected);
    }

    boolean permits(Observation observation) {
        if (observation == null || !equals(observation.impact()) || !observation.completeExposure()
                || !observation.mayMutateOwnedComponents()
                || !observation.protection().keySet().equals(affected)) return false;
        return observation.protection().values().stream().allMatch(decision ->
                decision == Decision.ALLOW || decision == Decision.DENY && !respectProtection);
    }

    public double remainingHp(double hp) {
        return BigDecimal.valueOf(hp).subtract(BigDecimal.valueOf(damage).multiply(BigDecimal.valueOf(multiplier)))
                .max(BigDecimal.ZERO).doubleValue();
    }

    public long repairAfter(Tower before) {
        if (repairCoolingMillis == 0 || remainingHp(before.hp()) == before.hp()) return before.repairAfter();
        return Math.max(before.repairAfter(), repairDeadline(damageAtMillis, repairCoolingMillis));
    }

    /** Canonical operation evidence, separate from the adapter's physical snapshots. */
    public String encode() {
        return String.join("|", "blast-v1", explosion.toString(), world.toString(), Double.toHexString(damage),
                Double.toHexString(multiplier), Boolean.toString(respectProtection), Boolean.toString(expanded),
                Long.toString(damageAtMillis), Long.toString(repairCoolingMillis), String.join(",", affected));
    }

    public static BlastImpact fromJournal(String evidence) {
        int start = evidence.indexOf('\n') + 1;
        int end = evidence.indexOf('\n', start);
        if (start == 0 || end < 0) throw new IllegalArgumentException("Missing blast journal evidence");
        return decode(evidence.substring(start, end));
    }

    public static BlastImpact decode(String evidence) {
        String[] fields = evidence.split("\\|", -1);
        if (fields.length != 10 || !fields[0].equals("blast-v1")) throw new IllegalArgumentException("Blast evidence format");
        BlastImpact impact = new BlastImpact(UUID.fromString(fields[1]), UUID.fromString(fields[2]),
                Set.of(fields[9].split(",", -1)), Double.parseDouble(fields[3]), Double.parseDouble(fields[4]),
                Boolean.parseBoolean(fields[5]), Boolean.parseBoolean(fields[6]),
                Long.parseLong(fields[7]), Long.parseLong(fields[8]));
        if (!impact.encode().equals(evidence)) throw new IllegalArgumentException("Noncanonical blast evidence");
        return impact;
    }
}
