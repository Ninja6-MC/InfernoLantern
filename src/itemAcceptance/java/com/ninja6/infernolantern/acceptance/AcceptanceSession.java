package com.ninja6.infernolantern.acceptance;

import java.util.UUID;

/** Bounded one-fixture cancellation plan; never owns or repairs lifecycle state. */
public final class AcceptanceSession {
    public enum Boundary { ORIGINAL, PRE_INTENT, EFFECT }
    public enum Scenario { CONTROL, ORIGINAL, PRE_INTENT, EFFECT }
    private final Scenario scenario;
    private final long deadline;
    private final int maxEvents;
    private UUID fixture;
    private boolean denied, closed;
    private int events, cancellations, retryDenials;
    private final int[] boundaries = new int[Boundary.values().length];

    public AcceptanceSession(Scenario scenario, long now, int ttlSeconds, int maxEvents) {
        if (ttlSeconds < 10 || ttlSeconds > 300 || maxEvents < 1 || maxEvents > 4096)
            throw new IllegalArgumentException("Acceptance bounds");
        this.scenario = java.util.Objects.requireNonNull(scenario);
        this.deadline = now + ttlSeconds * 1_000_000_000L;
        this.maxEvents = maxEvents;
    }
    public boolean selected(UUID candidate) { return candidate != null && candidate.equals(fixture); }
    public boolean active(long now) { return !closed && now - deadline < 0 && events < maxEvents; }
    public boolean observe(UUID candidate, Boundary boundary, long now) {
        if (!active(now)) { closed = true; return false; }
        if (fixture == null) fixture = java.util.Objects.requireNonNull(candidate);
        if (!selected(candidate)) return false;
        events++; boundaries[boundary.ordinal()]++;
        boolean target = scenario != Scenario.CONTROL && boundary.name().equals(scenario.name());
        boolean retry = denied && boundary == Boundary.ORIGINAL;
        if (target || retry) {
            cancellations++; if (retry) retryDenials++;
            denied = true;
            return true;
        }
        return false;
    }
    public void close() { closed = true; }
    public String counters() {
        return "events=" + events + "; original=" + boundaries[0] + "; pre-intent=" + boundaries[1]
                + "; effect=" + boundaries[2] + "; cancellations=" + cancellations + "; retry-denials=" + retryDenials;
    }
}
