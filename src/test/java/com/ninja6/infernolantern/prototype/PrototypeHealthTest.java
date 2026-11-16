package com.ninja6.infernolantern.prototype;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PrototypeHealthTest {
    @Test void finalProtectionCancellationDoesNotDebit() {
        var health = new PrototypeHealth();
        assertFalse(health.debit(new Object(), 3, true));
        assertEquals(40, health.hp());
        assertEquals(0, health.debits());
    }
    @Test void oneEventDebitsOnceButDistinctNativeEventsRemainDistinct() {
        var health = new PrototypeHealth();
        Object first = new Object(), second = new Object();
        assertTrue(health.debit(first, 3, false));
        assertFalse(health.debit(first, 3, false));
        assertTrue(health.debit(second, 3, false));
        assertEquals(34, health.hp());
        assertEquals(2, health.debits());
    }
    @Test void closedDeploymentCannotDebitTheReplacement() {
        var old = new PrototypeHealth();
        Object delayed = new Object();
        old.close();
        var replacement = new PrototypeHealth();
        assertFalse(old.debit(delayed, 6, false));
        assertEquals(40, replacement.hp());
        assertEquals(0, replacement.debits());
    }
    @Test void zeroNegativeAndNonFiniteDamageDoNotBecomeHits() {
        var health = new PrototypeHealth();
        for (double damage : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY})
            assertFalse(health.debit(new Object(), damage, false));
        assertEquals(40, health.hp());
    }
    @Test void lethalDamageClampsAndClosedHealthRejectsMoreCallbacks() {
        var health = new PrototypeHealth();
        assertTrue(health.debit(new Object(), 60, false));
        assertEquals(0, health.hp());
        health.close();
        assertFalse(health.debit(new Object(), 3, false));
        assertEquals(1, health.debits());
    }
}
