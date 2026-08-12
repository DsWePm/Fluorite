package io.github.dswepm.fluorite.rt.light;

/**
 * Shared CPU contract for the shader's 32-byte {@code Light} record.
 *
 * <p>Rectangle lights use the three packed half-axis lanes and bits 0..30 of the final word. Dynamic
 * sphere lights set bit 31 and store their radius in the low half of {@code halfUxy}; the other axis
 * halves and the grid payload remain zero until the ReSTIR integration builds dynamic selection data.
 */
public final class RtLightEncoding {
    public static final int RECORD_FLOATS = 8;
    public static final int RECORD_BYTES = RECORD_FLOATS * Float.BYTES;
    public static final int NORMAL_FLIP_BIT = 1 << 30;
    public static final int TYPE_SPHERE_BIT = 1 << 31;

    private RtLightEncoding() {
    }

    /** Encode one finite sphere emitter into the shared 32-byte light ABI. */
    public static void encodeSphere(float[] destination, int offset,
                                    float x, float y, float z, float radius,
                                    float radianceR, float radianceG, float radianceB) {
        if (destination == null || offset < 0 || offset + RECORD_FLOATS > destination.length) {
            throw new IllegalArgumentException("Sphere light destination is too small");
        }
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                || !Float.isFinite(radius) || radius <= 0.0f
                || !finiteNonNegative(radianceR) || !finiteNonNegative(radianceG)
                || !finiteNonNegative(radianceB)) {
            throw new IllegalArgumentException("Sphere light fields must be finite and physical");
        }
        destination[offset] = x;
        destination[offset + 1] = y;
        destination[offset + 2] = z;
        destination[offset + 3] = Float.intBitsToFloat(packR11G11B10(radianceR, radianceG, radianceB));
        destination[offset + 4] = packHalf2(Math.min(radius, 65504.0f), 0.0f);
        destination[offset + 5] = 0.0f;
        destination[offset + 6] = 0.0f;
        destination[offset + 7] = Float.intBitsToFloat(TYPE_SPHERE_BIT);
    }

    /** Two binary16 values in one float lane; x occupies the low half, matching Slang unpackHalf2. */
    public static float packHalf2(float x, float y) {
        int bits = (Float.floatToFloat16(y) << 16) | (Float.floatToFloat16(x) & 0xFFFF);
        return Float.intBitsToFloat(bits);
    }

    public static int packR11G11B10(float r, float g, float b) {
        return packUnsignedFloat(r, 6) | (packUnsignedFloat(g, 6) << 11)
                | (packUnsignedFloat(b, 5) << 22);
    }

    private static int packUnsignedFloat(float value, int mantissaBits) {
        if (!(value > 0.0f)) return 0;
        if (!Float.isFinite(value)) return (30 << mantissaBits) | ((1 << mantissaBits) - 1);
        int exponent = Math.getExponent(value);
        int encodedExponent = exponent + 15;
        int mantissaScale = 1 << mantissaBits;
        if (encodedExponent <= 0) {
            int mantissa = Math.round(Math.scalb(value, 14 + mantissaBits));
            return Math.min(mantissa, mantissaScale - 1);
        }
        if (encodedExponent >= 31) return (30 << mantissaBits) | (mantissaScale - 1);
        int mantissa = Math.round((Math.scalb(value, -exponent) - 1.0f) * mantissaScale);
        if (mantissa == mantissaScale) {
            mantissa = 0;
            if (++encodedExponent >= 31) return (30 << mantissaBits) | (mantissaScale - 1);
        }
        return (encodedExponent << mantissaBits) | mantissa;
    }

    public static float unpackUnsignedFloat(int bits, int mantissaBits) {
        int mantissaMask = (1 << mantissaBits) - 1;
        int mantissa = bits & mantissaMask;
        int exponent = (bits >>> mantissaBits) & 31;
        if (exponent == 0) return Math.scalb((float) mantissa, 1 - 15 - mantissaBits);
        return Math.scalb(1.0f + (float) mantissa / (1 << mantissaBits), exponent - 15);
    }

    private static boolean finiteNonNegative(float value) {
        return Float.isFinite(value) && value >= 0.0f;
    }
}
