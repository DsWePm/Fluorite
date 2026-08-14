package io.github.dswepm.fluorite.rt.entity;

/**
 * Integer-defined rain-impact field shared by CPU RT splashes and {@code rain_surface.slang} ripples.
 * Keep the constants, unsigned overflow, high-24-bit float conversion, and salt assignments identical.
 */
final class RtRainImpactEvents {
    static final double CELL_SIZE = 0.50;
    static final int CELL_PERIOD = 8192; // 4096 blocks / 0.5 blocks per cell
    static final double TIME_WRAP_SECONDS = 3600.0;
    static final float EARLY_SPLASH_PHASE = 0.20f;

    record EventKey(int cellX, int cellZ, int eventIndex) {
    }

    record Event(EventKey key, double worldX, double worldZ, float phase, int seed) {
    }

    private RtRainImpactEvents() {
    }

    static double animationTimeSeconds() {
        return System.nanoTime() / 1.0e9 % TIME_WRAP_SECONDS;
    }

    static int cell(double worldCoordinate) {
        return (int) Math.floor(worldCoordinate / CELL_SIZE);
    }

    /** Return the shader event owned by one unwrapped world cell, or null while its slot is inactive. */
    static Event sample(int cellX, int cellZ, double animationTime, float rainAmount) {
        int wrappedX = Math.floorMod(cellX, CELL_PERIOD);
        int wrappedZ = Math.floorMod(cellZ, CELL_PERIOD);
        int cycleOrdinal = impactHash(wrappedX, wrappedZ, 0, 10) & 3;
        double cycleSeconds = 0.75 + cycleOrdinal * 0.25;
        int cycleCount = (int) Math.round(TIME_WRAP_SECONDS / cycleSeconds);
        double phaseOffset = impactHash01(wrappedX, wrappedZ, 0, 11);
        double eventClock = animationTime / cycleSeconds + phaseOffset;
        int eventIndex = Math.floorMod((int) Math.floor(eventClock), cycleCount);
        float slotPhase = (float) (eventClock - Math.floor(eventClock));

        float eventProbability = lerp(0.35f, 0.95f,
                (float) Math.sqrt(Math.clamp(rainAmount, 0f, 1f)));
        float random0 = impactHash01(wrappedX, wrappedZ, eventIndex, 0);
        if (random0 > eventProbability) {
            return null;
        }
        float random1 = impactHash01(wrappedX, wrappedZ, eventIndex, 1);
        float activeFraction = lerp(0.36f, 0.72f, random1);
        if (slotPhase >= activeFraction) {
            return null;
        }
        float phase = slotPhase / activeFraction;
        float offsetX = impactHash01(wrappedX, wrappedZ, eventIndex, 2);
        float offsetZ = impactHash01(wrappedX, wrappedZ, eventIndex, 3);
        int seed = impactHash(wrappedX, wrappedZ, eventIndex, 7);
        return new Event(new EventKey(cellX, cellZ, eventIndex),
                cellX * CELL_SIZE + offsetX * CELL_SIZE,
                cellZ * CELL_SIZE + offsetZ * CELL_SIZE, phase, seed);
    }

    static int impactMix(int value) {
        value ^= value >>> 16;
        value *= 0x7FEB352D;
        value ^= value >>> 15;
        value *= 0x846CA68B;
        value ^= value >>> 16;
        return value;
    }

    private static int impactHash(int wrappedX, int wrappedZ, int eventIndex, int salt) {
        int value = wrappedX * 0x9E3779B9
                ^ wrappedZ * 0x85EBCA6B
                ^ eventIndex * 0xC2B2AE35
                ^ salt * 0x27D4EB2F;
        return impactMix(value);
    }

    private static float impactHash01(int wrappedX, int wrappedZ, int eventIndex, int salt) {
        return (impactHash(wrappedX, wrappedZ, eventIndex, salt) >>> 8) * (1f / 16777216f);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
