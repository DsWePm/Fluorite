package io.github.dswepm.fluorite.rt.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class RtRainImpactEventsTest {
    @Test
    void integerMixerHasStableUnsignedOverflowSemantics() {
        assertEquals(0, RtRainImpactEvents.impactMix(0));
        assertEquals(0x688990C0, RtRainImpactEvents.impactMix(1));
    }

    @Test
    void repeatedWorldPeriodKeepsIdentityPhaseAndLocalOffset() {
        int cellX = -137;
        int cellZ = 941;
        TimedEvent timed = firstActive(cellX, cellZ);
        RtRainImpactEvents.Event event = timed.event();
        RtRainImpactEvents.Event repeated = RtRainImpactEvents.sample(
                cellX + RtRainImpactEvents.CELL_PERIOD, cellZ, timed.time(), 1f);

        assertNotNull(repeated);
        assertEquals(event.key().eventIndex(), repeated.key().eventIndex());
        assertEquals(event.phase(), repeated.phase(), 1.0e-6f);
        assertEquals(event.seed(), repeated.seed());
        assertEquals(4096.0, repeated.worldX() - event.worldX(), 1.0e-6);
        assertEquals(event.worldZ(), repeated.worldZ(), 1.0e-6);
    }

    private record TimedEvent(double time, RtRainImpactEvents.Event event) {
    }

    private static TimedEvent firstActive(int cellX, int cellZ) {
        for (int step = 0; step < 7200; step++) {
            double time = step * 0.05;
            RtRainImpactEvents.Event event = RtRainImpactEvents.sample(cellX, cellZ, time, 1f);
            if (event != null) {
                return new TimedEvent(time, event);
            }
        }
        throw new AssertionError("expected one active event within six minutes");
    }
}
