package com.ninja6.infernolantern.scheduling;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Database completions supply immutable data; world work must be explicitly marshalled here. */
public interface WorldScheduler {
    record Anchor(UUID world, int chunkX, int chunkZ) {
        public Anchor { java.util.Objects.requireNonNull(world); }
    }
    <T> CompletableFuture<T> supply(Anchor anchor, Supplier<T> action);
}
