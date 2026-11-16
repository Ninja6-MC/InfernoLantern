package com.ninja6.infernolantern.prototype;

import java.util.function.IntPredicate;

/** Shared clearance policy for the deliberately dry, single-column prototype. */
public final class PrototypeSpace {
    private PrototypeSpace() {}

    public static void validate(int y, int minHeight, int maxHeight, int height,
                                boolean loaded, IntPredicate available) {
        if (height != 1 && height != 3) throw new IllegalArgumentException("Unsupported prototype height");
        if (y < minHeight + 2 || y > maxHeight - height) throw new IllegalStateException("Outside prototype height bounds.");
        if (!loaded) throw new IllegalStateException("Required chunk is not loaded.");
        for (int offset = 0; offset < height; offset++) {
            if (!available.test(offset)) throw new IllegalStateException("Space is obstructed, fluid-filled or awaiting cleanup.");
        }
    }
}
