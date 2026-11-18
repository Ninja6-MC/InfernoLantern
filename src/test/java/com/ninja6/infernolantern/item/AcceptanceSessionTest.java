package com.ninja6.infernolantern.item;

import com.ninja6.infernolantern.acceptance.AcceptanceSession;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static com.ninja6.infernolantern.acceptance.AcceptanceSession.*;
import static org.junit.jupiter.api.Assertions.*;

class AcceptanceSessionTest {
    private final UUID first = new UUID(0, 1), other = new UUID(0, 2);
    @Test void distinctScenariosCancelOnlyTheirExactBoundaryAndLaterNativeRetries() {
        for (var scenario : Scenario.values()) {
            var session = new AcceptanceSession(scenario, 0, 10, 512);
            boolean denial = false;
            for (var boundary : Boundary.values()) {
                boolean expected = scenario != Scenario.CONTROL && scenario.name().equals(boundary.name());
                assertEquals(expected, session.observe(first, boundary, 1));
                denial |= expected;
            }
            assertEquals(denial || scenario == Scenario.ORIGINAL, session.observe(first, Boundary.ORIGINAL, 2));
            assertFalse(session.observe(other, Boundary.EFFECT, 3));
        }
    }
    @Test void repeatedNativeEventsCannotBeMistakenForEffectBoundary() {
        var session = new AcceptanceSession(Scenario.EFFECT, 0, 10, 512);
        for (int i = 0; i < 100; i++) assertFalse(session.observe(first, Boundary.ORIGINAL, i));
        assertFalse(session.observe(first, Boundary.PRE_INTENT, 101));
        assertTrue(session.observe(first, Boundary.EFFECT, 102));
        assertTrue(session.observe(first, Boundary.ORIGINAL, 103));
    }
    @Test void expiryAndEventLimitAndExplicitCloseStopAllInterference() {
        var session = new AcceptanceSession(Scenario.ORIGINAL, 0, 10, 1);
        assertTrue(session.observe(first, Boundary.ORIGINAL, 1));
        assertFalse(session.observe(first, Boundary.ORIGINAL, 2));
        session = new AcceptanceSession(Scenario.EFFECT, 0, 10, 512);
        assertFalse(session.observe(first, Boundary.EFFECT, 10_000_000_000L));
        session = new AcceptanceSession(Scenario.ORIGINAL, 0, 10, 512); session.close();
        assertFalse(session.observe(first, Boundary.ORIGINAL, 1));
    }
    @Test void invalidBoundsDoNotArm() {
        assertThrows(IllegalArgumentException.class, () -> new AcceptanceSession(Scenario.CONTROL, 0, 301, 1));
        assertThrows(IllegalArgumentException.class, () -> new AcceptanceSession(Scenario.CONTROL, 0, 9, 1));
        assertThrows(IllegalArgumentException.class, () -> new AcceptanceSession(Scenario.CONTROL, 0, 10, 4097));
        assertThrows(IllegalArgumentException.class, () -> new AcceptanceSession(Scenario.CONTROL, 0, 10, 0));
    }
}
