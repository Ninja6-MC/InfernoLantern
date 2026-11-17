package com.ninja6.infernolantern.item;

import java.util.Objects;
import java.util.UUID;

/** Representation identity only: health, timers and control belong to the repository. */
public record ItemToken(int schema, String variant, UUID tower, long generation) {
    public static final int SCHEMA = 1;
    public ItemToken {
        if (schema != SCHEMA || variant == null || !variant.matches("[a-z0-9][a-z0-9_.:-]{0,127}") || generation < 0)
            throw new IllegalArgumentException("Invalid item token");
        Objects.requireNonNull(tower);
    }
    public ItemToken next() { return new ItemToken(schema, variant, tower, Math.incrementExact(generation)); }
}
