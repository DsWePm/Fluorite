package io.github.dswepm.fluorite.rt.light;

/** Deterministic low-30-bit identities reserved by D104A for future temporal ReSTIR validation. */
public final class RtDynamicLightKey {
    public static final int HELD_LEFT = 1;
    public static final int HELD_RIGHT = 2;
    public static final int ENTITY_FLAME = 3;
    public static final int ENTITY_BODY = 4;

    private RtDynamicLightKey() {
    }

    public static int entity(int entityId, int sourceKind) {
        return mix(((long) entityId << 32) ^ (sourceKind & 0xFFFF_FFFFL));
    }

    public static int particleCell(int x, int y, int z) {
        long value = 0x9E3779B97F4A7C15L;
        value ^= Integer.toUnsignedLong(x) * 0xBF58476D1CE4E5B9L;
        value = Long.rotateLeft(value, 21);
        value ^= Integer.toUnsignedLong(y) * 0x94D049BB133111EBL;
        value = Long.rotateLeft(value, 21);
        value ^= Integer.toUnsignedLong(z) * 0xD6E8FEB86659FD93L;
        return mix(value);
    }

    private static int mix(long value) {
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return (int) value & RtLightEncoding.SOURCE_KEY_MASK;
    }
}
