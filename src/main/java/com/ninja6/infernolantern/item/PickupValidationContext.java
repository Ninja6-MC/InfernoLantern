package com.ninja6.infernolantern.item;

import org.bukkit.event.entity.EntityPickupItemEvent;
import java.util.Optional;
import java.util.function.Supplier;

/** Read-only identity-scoped context for the existing synchronous provider checks. */
public final class PickupValidationContext {
    public enum Stage { PRE_INTENT, EFFECT }
    private record Frame(EntityPickupItemEvent event, Stage stage) {}
    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();
    private PickupValidationContext() {}

    /** Empty for native events, different event instances, and other threads. */
    public static Optional<Stage> current(EntityPickupItemEvent event) {
        Frame frame = CURRENT.get();
        return frame != null && frame.event() == event ? Optional.of(frame.stage()) : Optional.empty();
    }

    // Called only by the adapter on its owning thread. Never changes cancellation/readiness.
    static <T> T during(EntityPickupItemEvent event, Stage stage, Supplier<T> dispatch) {
        Frame previous = CURRENT.get();
        CURRENT.set(new Frame(java.util.Objects.requireNonNull(event), java.util.Objects.requireNonNull(stage)));
        try { return dispatch.get(); }
        finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }
}
