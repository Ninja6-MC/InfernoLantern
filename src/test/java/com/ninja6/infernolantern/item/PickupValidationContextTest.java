package com.ninja6.infernolantern.item;

import org.bukkit.event.entity.EntityPickupItemEvent;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class PickupValidationContextTest {
    private static EntityPickupItemEvent event() { return new EntityPickupItemEvent(null, null, 0); }
    @Test void exactIdentityNestedDispatchAndOtherThreadRemainSeparated() throws Exception {
        var outer = event(); var inner = event();
        assertTrue(PickupValidationContext.current(outer).isEmpty());
        PickupValidationContext.during(outer, PickupValidationContext.Stage.PRE_INTENT, () -> {
            assertEquals(PickupValidationContext.Stage.PRE_INTENT, PickupValidationContext.current(outer).orElseThrow());
            assertTrue(PickupValidationContext.current(inner).isEmpty());
            assertTrue(CompletableFuture.supplyAsync(() -> PickupValidationContext.current(outer).isEmpty()).join());
            PickupValidationContext.during(inner, PickupValidationContext.Stage.EFFECT, () -> {
                assertTrue(PickupValidationContext.current(outer).isEmpty());
                assertEquals(PickupValidationContext.Stage.EFFECT, PickupValidationContext.current(inner).orElseThrow());
                return null;
            });
            assertEquals(PickupValidationContext.Stage.PRE_INTENT, PickupValidationContext.current(outer).orElseThrow());
            return null;
        });
        assertTrue(PickupValidationContext.current(outer).isEmpty());
    }
    @Test void exceptionRestoresContextAndDoesNotChangeCancellation() {
        var event = event(); event.setCancelled(true);
        assertThrows(IllegalStateException.class, () -> PickupValidationContext.during(event,
                PickupValidationContext.Stage.EFFECT, () -> { throw new IllegalStateException("fixture"); }));
        assertTrue(PickupValidationContext.current(event).isEmpty()); assertTrue(event.isCancelled());
    }
}
