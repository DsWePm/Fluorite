package io.github.dswepm.fluorite.build;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Small uncompressed KTX2 writer for Fluorite's Vulkan-native generated textures. */
final class Ktx2Writer {
    private static final byte[] IDENTIFIER = new byte[]{
            (byte) 0xab, 0x4b, 0x54, 0x58, 0x20, 0x32, 0x30, (byte) 0xbb, 0x0d, 0x0a, 0x1a, 0x0a};

    private Ktx2Writer() {}

    static void write(Path path, int vkFormat, int typeSize, int width, int height,
                      List<byte[]> levels, byte[] dfd, Map<String, String> metadata) throws IOException {
        write(path, vkFormat, typeSize, width, height, 0, levels, dfd, metadata);
    }

    /** Writes an ordinary 2D image when {@code layers == 0}, or a 2D array otherwise. */
    static void write(Path path, int vkFormat, int typeSize, int width, int height, int layers,
                      List<byte[]> levels, byte[] dfd, Map<String, String> metadata) throws IOException {
        byte[] kvd = keyValues(metadata);
        int headerBytes = 80;
        int levelIndexBytes = levels.size() * 24;
        long dfdOffset = headerBytes + levelIndexBytes;
        long kvdOffset = dfdOffset + dfd.length;
        long dataOffset = align(kvdOffset + kvd.length, 8);
        long[] offsets = new long[levels.size()];
        long cursor = dataOffset;
        for (int i = 0; i < levels.size(); i++) {
            cursor = align(cursor, 8);
            offsets[i] = cursor;
            cursor += levels.get(i).length;
        }
        if (cursor > Integer.MAX_VALUE) throw new IOException("KTX2 exceeds Java array size");
        ByteBuffer out = ByteBuffer.allocate((int) cursor).order(ByteOrder.LITTLE_ENDIAN);
        out.put(IDENTIFIER);
        out.putInt(vkFormat).putInt(typeSize).putInt(width).putInt(height);
        out.putInt(0).putInt(layers).putInt(1).putInt(levels.size()).putInt(0);
        out.putInt((int) dfdOffset).putInt(dfd.length);
        out.putInt(kvd.length == 0 ? 0 : (int) kvdOffset).putInt(kvd.length);
        out.putLong(0L).putLong(0L);
        for (int i = 0; i < levels.size(); i++) {
            out.putLong(offsets[i]).putLong(levels.get(i).length).putLong(levels.get(i).length);
        }
        out.position((int) dfdOffset).put(dfd);
        if (kvd.length > 0) out.position((int) kvdOffset).put(kvd);
        for (int i = 0; i < levels.size(); i++) out.position((int) offsets[i]).put(levels.get(i));
        Files.write(path, out.array());
    }

    static byte[] packR11G11B10(float[] rgb) {
        ByteBuffer out = ByteBuffer.allocate(rgb.length / 3 * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < rgb.length; i += 3) {
            int packed = packUnsignedFloat(rgb[i], 6)
                    | (packUnsignedFloat(rgb[i + 1], 6) << 11)
                    | (packUnsignedFloat(rgb[i + 2], 5) << 22);
            out.putInt(packed);
        }
        return out.array();
    }

    /**
     * IEEE 754 binary16, round-to-nearest-even, written out rather than delegated.
     *
     * <p><b>This is Float.floatToFloat16, and it is copied here because buildSrc may not call it.</b>
     * That method arrived in Java 20, and buildSrc is the one module that cannot choose its compiler:
     * its classes are loaded by the Gradle daemon itself, so they must target whatever JVM the daemon
     * happens to be running -- which is JAVA_HOME, not a toolchain. Giving buildSrc a toolchain does not
     * fix that; it only moves the failure from javac to UnsupportedClassVersionError, and buildSrc is the
     * gate every other task waits behind, so when it fails nothing else in the build can even report.
     *
     * <p>Bit-exact against the JDK, and that was VERIFIED rather than argued: all 2^32 float bit
     * patterns compared against Float.floatToFloat16 on JDK 25, zero mismatches. Exactness matters
     * because this writes asset bytes -- a one-ulp difference would make the generated textures depend
     * on which JDK ran the build, which is the property this change exists to remove.
     *
     * <p>NaN is canonicalised the way the JDK canonicalises it: the quiet bit is forced on and only the
     * top ten significand bits survive. Reverse-engineered from the JDK's own answers rather than
     * assumed, after a first attempt that preserved a payload the real method discards.
     */
    static short floatToHalf(float f) {
        int doppel = Float.floatToRawIntBits(f);
        short signBit = (short) ((doppel & 0x8000_0000) >> 16);

        if (Float.isNaN(f)) {
            return (short) (signBit | 0x7c00 | 0x0200 | (doppel & 0x007f_e000) >> 13);
        }

        float absF = Math.abs(f);
        // binary16 MAX_VALUE plus half an ulp: anything at or above rounds to infinity.
        if (absF >= (0x1.ffcp15f + 0x0.002p15f)) {
            return (short) (signBit | 0x7c00);
        }
        // Half of the smallest nonzero binary16 magnitude. Covers float zeros and every value that
        // rounds to one, signed.
        if (absF <= 0x1.0p-24f * 0.5f) {
            return signBit;
        }

        // Below binary16's minimum normal the result is subnormal: pin the exponent, keep the excess
        // shift, and bring the float's hidden mantissa bit into play since it stops being hidden.
        int exp = Math.getExponent(f);
        int expdelta = 0;
        int msb = 0x0000_0000;
        if (exp < -14) {
            expdelta = -14 - exp;
            exp = -15;
            msb = 0x0080_0000;
        }
        int signifBits = doppel & 0x007f_ffff | msb;

        // Truncated first, then rounded up in the three cases round-to-nearest-even calls for:
        // (lsb, round, sticky) of (0,1,1), (1,1,0) and (1,1,1).
        short half = (short) (signifBits >> (13 + expdelta));
        int lsb = signifBits & (1 << 13 + expdelta);
        int round = signifBits & (1 << 12 + expdelta);
        int sticky = signifBits & ((1 << 12 + expdelta) - 1);
        if (round != 0 && ((lsb | sticky) != 0)) {
            half++;
        }
        // Added rather than OR-ed: a significand that rounded up out of its ten bits must carry into
        // the exponent, which is exactly what makes the largest finite value round to infinity.
        return (short) (signBit | (((exp + 15) << 10) + half));
    }

    static void putHalf4(ByteBuffer out, int offset, float x, float y, float z, float w) {
        out.putShort(offset, floatToHalf(x));
        out.putShort(offset + 2, floatToHalf(y));
        out.putShort(offset + 4, floatToHalf(z));
        out.putShort(offset + 6, floatToHalf(w));
    }

    static byte[] packHalf1(float[] values) {
        ByteBuffer out = ByteBuffer.allocate(values.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) out.putShort(floatToHalf(value));
        return out.array();
    }

    static byte[] dfdR11G11B10() {
        return dfd(new Sample[]{new Sample(0, 11, 0x80), new Sample(11, 11, 0x81),
                new Sample(22, 10, 0x82)}, 4);
    }

    static byte[] dfdRgba16Float() {
        return dfd(new Sample[]{new Sample(0, 16, 0xc0), new Sample(16, 16, 0xc1),
                new Sample(32, 16, 0xc2), new Sample(48, 16, 0xcf)}, 8);
    }

    static byte[] dfdR16Float() {
        return dfd(new Sample[]{new Sample(0, 16, 0xc0)}, 2);
    }

    static byte[] dfdR8Unorm() {
        return dfd(new Sample[]{new Sample(0, 8, 0x80)}, 1);
    }

    private static int packUnsignedFloat(float value, int mantissaBits) {
        if (!(value > 0.0f)) return 0;
        if (!Float.isFinite(value)) return (30 << mantissaBits) | ((1 << mantissaBits) - 1);
        int exponent = Math.getExponent(value);
        int encodedExponent = exponent + 15;
        int mantissaScale = 1 << mantissaBits;
        if (encodedExponent <= 0) {
            return Math.min(Math.round(Math.scalb(value, 14 + mantissaBits)), mantissaScale - 1);
        }
        if (encodedExponent >= 31) return (30 << mantissaBits) | (mantissaScale - 1);
        int mantissa = Math.round((Math.scalb(value, -exponent) - 1.0f) * mantissaScale);
        if (mantissa == mantissaScale) {
            mantissa = 0;
            if (++encodedExponent >= 31) return (30 << mantissaBits) | (mantissaScale - 1);
        }
        return (encodedExponent << mantissaBits) | mantissa;
    }

    private static byte[] dfd(Sample[] samples, int bytesPlane0) {
        int blockSize = 24 + samples.length * 16;
        ByteBuffer out = ByteBuffer.allocate(4 + blockSize).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(4 + blockSize);
        out.putInt(0);
        out.putShort((short) 2).putShort((short) blockSize);
        out.put((byte) 1).put((byte) 1).put((byte) 1).put((byte) 0);
        out.putInt(0).put((byte) bytesPlane0);
        for (int i = 1; i < 8; i++) out.put((byte) 0);
        for (Sample sample : samples) {
            out.putShort((short) sample.bitOffset).put((byte) (sample.bitLength - 1))
                    .put((byte) sample.channelType).putInt(0);
            out.putInt((sample.channelType & 0x40) != 0 ? 0xbf800000 : 0).putInt(0x3f800000);
        }
        return out.array();
    }

    private static byte[] keyValues(Map<String, String> metadata) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (Map.Entry<String, String> entry : metadata.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            byte[] key = entry.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] value = entry.getValue().getBytes(StandardCharsets.UTF_8);
            int length = key.length + 1 + value.length + 1;
            bytes.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(length).array());
            bytes.write(key);
            bytes.write(0);
            bytes.write(value);
            bytes.write(0);
            while ((bytes.size() & 3) != 0) bytes.write(0);
        }
        return bytes.toByteArray();
    }

    private static long align(long value, long alignment) {
        return (value + alignment - 1) & -alignment;
    }

    private record Sample(int bitOffset, int bitLength, int channelType) {}
}
