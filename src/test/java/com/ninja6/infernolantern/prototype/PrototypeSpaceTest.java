package com.ninja6.infernolantern.prototype;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PrototypeSpaceTest {
    @Test void expandedChecksUppermostCellBeforeAnyMutation() {
        assertThrows(IllegalStateException.class, () -> PrototypeSpace.validate(70, -64, 320, 3, true, offset -> offset != 2));
    }
    @Test void compactNeedsOnlyItsBaseCell() {
        assertDoesNotThrow(() -> PrototypeSpace.validate(70, -64, 320, 1, true, offset -> offset == 0));
    }
    @Test void outOfBoundsAndUnloadedFailBeforeBlockReads() {
        assertThrows(IllegalStateException.class, () -> PrototypeSpace.validate(319, -64, 320, 3, true, offset -> { fail("Block read outside bounds"); return true; }));
        assertThrows(IllegalStateException.class, () -> PrototypeSpace.validate(70, -64, 320, 3, false, offset -> { fail("Read unloaded chunk"); return true; }));
    }
    @Test void fluidReservedAndSolidCellsAreAllUnavailable() {
        for (int obstruction = 0; obstruction < 3; obstruction++) {
            final int blocked = obstruction;
            assertThrows(IllegalStateException.class, () -> PrototypeSpace.validate(70, -64, 320, 3, true, offset -> offset != blocked));
        }
    }
}
