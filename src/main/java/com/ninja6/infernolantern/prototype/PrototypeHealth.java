package com.ninja6.infernolantern.prototype;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Disposable experiment HP. A closed deployment can never accept later callbacks. */
final class PrototypeHealth {
    private final Set<Object> events = Collections.newSetFromMap(new WeakHashMap<>());
    private double hp = 40; // Experiment value, not gameplay balance.
    private boolean active = true;
    private long debits;

    boolean debit(Object event, double damage, boolean cancelled) {
        if (!active || cancelled || !Double.isFinite(damage) || damage <= 0 || !events.add(event)) return false;
        hp = Math.max(0, hp - damage);
        debits++;
        return true;
    }
    double hp() { return hp; }
    long debits() { return debits; }
    void close() { active = false; events.clear(); }
}
