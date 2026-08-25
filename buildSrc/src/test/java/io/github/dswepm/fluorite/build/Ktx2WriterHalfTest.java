package io.github.dswepm.fluorite.build;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The hand-rolled binary16 conversion, pinned against the JDK's own answers.
 *
 * <p>Ktx2Writer.floatToHalf is a copy of Float.floatToFloat16, written out because buildSrc cannot call
 * it: that method arrived in Java 20, and buildSrc's classes are loaded by the Gradle daemon, so they
 * must target whatever JVM the daemon is running rather than a toolchain this module chooses. Which
 * means this test cannot call the JDK method either -- it would not compile on the same JDKs the copy
 * exists to support.
 *
 * <p>So the expectations are BAKED, produced by running Float.floatToFloat16 on JDK 25 and printing what
 * it returned. That is what makes them authoritative rather than derived from the same reasoning as the
 * code they check. The port was separately verified exhaustively -- all 2^32 float bit patterns, zero
 * mismatches -- and this file is the regression net that keeps a later edit from quietly losing that.
 *
 * <p>Every input is spelled as raw bits. A decimal literal would be a second conversion between the
 * intent and the test, and the cases that matter most here are exactly the ones a decimal literal cannot
 * name: the tie that rounds to even, the tie that rounds away, and the value half an ulp below overflow.
 */
final class Ktx2WriterHalfTest {

    /**
     * The named edge cases, which are where a bit-twiddling conversion actually goes wrong.
     *
     * <p>Overflow, both subnormal boundaries, both rounding ties, and the NaN canonicalisation the first
     * attempt at this port got wrong -- it preserved a payload the JDK discards, which every ordinary
     * value in the world would have failed to reveal.
     */
    @Test
    void everyEdgeCaseMatchesTheJdk() {
        check("zero", Float.intBitsToFloat(0x00000000), (short) 0x0000);
        check("negativeZero", Float.intBitsToFloat(0x80000000), (short) 0x8000);
        check("one", Float.intBitsToFloat(0x3F800000), (short) 0x3C00);
        check("negativeOne", Float.intBitsToFloat(0xBF800000), (short) 0xBC00);
        check("half", Float.intBitsToFloat(0x3F000000), (short) 0x3800);
        check("two", Float.intBitsToFloat(0x40000000), (short) 0x4000);
        check("binary16Max", Float.intBitsToFloat(0x477FE000), (short) 0x7BFF);
        check("negativeBinary16Max", Float.intBitsToFloat(0xC77FE000), (short) 0xFBFF);
        check("overflowThreshold", Float.intBitsToFloat(0x477FF000), (short) 0x7C00);
        check("justBelowOverflow", Float.intBitsToFloat(0x477FEFFF), (short) 0x7BFF);
        check("minNormal", Float.intBitsToFloat(0x38800000), (short) 0x0400);
        check("minSubnormal", Float.intBitsToFloat(0x33800000), (short) 0x0001);
        check("halfMinSubnormalTiesToZero", Float.intBitsToFloat(0x33000000), (short) 0x0000);
        check("justAboveThatTie", Float.intBitsToFloat(0x33000001), (short) 0x0001);
        check("onePlusUlp", Float.intBitsToFloat(0x3F802000), (short) 0x3C01);
        check("onePlusHalfUlpTiesToEven", Float.intBitsToFloat(0x3F801000), (short) 0x3C00);
        check("onePlusThreeHalfUlpTiesUp", Float.intBitsToFloat(0x3F803000), (short) 0x3C02);
        check("tenth", Float.intBitsToFloat(0x3DCCCCCD), (short) 0x2E66);
        check("third", Float.intBitsToFloat(0x3EAAAAAB), (short) 0x3555);
        check("negativeTenth", Float.intBitsToFloat(0xBDCCCCCD), (short) 0xAE66);
        check("floatMax", Float.intBitsToFloat(0x7F7FFFFF), (short) 0x7C00);
        check("floatMinSubnormal", Float.intBitsToFloat(0x00000001), (short) 0x0000);
        check("negativeFloatMinSubnormal", Float.intBitsToFloat(0x80000001), (short) 0x8000);
        check("positiveInfinity", Float.POSITIVE_INFINITY, (short) 0x7C00);
        check("negativeInfinity", Float.NEGATIVE_INFINITY, (short) 0xFC00);
        check("nan", Float.NaN, (short) 0x7E00);
    }

    /**
     * A million deterministic inputs, reduced to one number the JDK also produced.
     *
     * <p>The table above is diagnosable and narrow; this is wide and says nothing about WHERE it broke.
     * Together they are what a hand-rolled numeric routine needs — the table names the failure, the
     * checksum notices the one nobody thought to name. The generator is a plain LCG so the sequence is
     * fixed forever, and it walks raw bit patterns rather than values, so infinities, subnormals and NaN
     * all appear in their natural proportion instead of being excluded by a range.
     */
    @Test
    void aMillionDeterministicInputsReduceToTheJdksChecksum() {
        long sum = 0;
        int state = 0x12345678;
        for (int i = 0; i < 1_000_000; i++) {
            state = state * 1664525 + 1013904223;
            sum = sum * 31 + (Ktx2Writer.floatToHalf(Float.intBitsToFloat(state)) & 0xFFFF);
        }
        assertEquals(-6429977995552036485L, sum,
                "the conversion drifted from Float.floatToFloat16 somewhere the table does not name");
    }

    private static void check(String name, float input, short expected) {
        assertEquals(expected, Ktx2Writer.floatToHalf(input),
                () -> String.format("%s: input bits 0x%08X, expected 0x%04X but got 0x%04X",
                        name, Float.floatToRawIntBits(input), expected & 0xFFFF,
                        Ktx2Writer.floatToHalf(input) & 0xFFFF));
    }
}
