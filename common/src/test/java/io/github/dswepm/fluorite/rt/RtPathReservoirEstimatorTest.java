package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The path reservoir's survivor bookkeeping, checked as NUMBERS rather than as spellings (D230).
 *
 * <p>A wrong merge weight does not look like an error: it looks like a slightly brighter or darker
 * picture, which is the D217 risk this estimator was designed around. The 2026-09-26 audit found one
 * (B03): a winner's weight was divided by its own receiver-domain luminance, which already carried
 * the record's stored W, so every carried history came out divided by its previous W. It never showed
 * in game only because A01 kept the merged regime nearly unreachable.
 *
 * <p>So the survivor write is mirrored here and run as a static single-pixel chain in the regime B02
 * drives every still pixel into -- the temporal record saturates the candidate count and the spatial
 * loop reads nothing -- with a Lambertian receiver, J = 1, and incident radiance drawn from a known
 * two-level distribution. The chain's long-run mean has to land on the integral. This historical numerical regression remains after R2 retires the in-place shader;
 * the replacement estimator is mirrored in RtPathReuseMathTest.
 */
final class RtPathReservoirEstimatorTest {

    private static final double ALBEDO = 0.7;
    private static final int FRAMES = 600_000;
    private static final int BURN_IN = 2_000;

    @Test
    void theInPlaceSurvivorWriteIsRetired() throws IOException {
        String rgen = code(source("shaders/world/path_reuse_trace.slang"));
        assertTrue(!rgen.contains("asfloat(prev.W)"));
        assertTrue(!rgen.contains("writtenM * chosenTarget"));
    }

    @Test
    void aPixelWithoutAReconnectionVertexWritesAnEmptyRecord() throws IOException {
        String rgen = code(source("shaders/world/path_reuse_trace.slang"));
        assertTrue(rgen.contains("if (recordPath) {"));
        assertTrue(rgen.contains("= emptyPathReservoir();"),
                "every owned slot must be overwritten this frame (B05)");
        String common = code(source("shaders/world/world_common.slang"));
        String body = common.substring(common.indexOf("public PackedPathReservoir emptyPathReservoir()"));
        body = body.substring(0, body.indexOf("\n}"));
        assertTrue(body.contains("r.bits = 0u;"), "empty has no validity bit");
        assertTrue(body.contains("r.W = 0.0;"));
    }

    @Test
    void theMergedChainIsUnbiasedWithTheCorrectedWeight() {
        // Three lighting ranges: a small bright opening, a tiny very bright one, a mild two-level field.
        double[][] environments = {{0.10, 10.0, 0.5}, {0.01, 100.0, 0.5}, {0.50, 2.0, 1.0}};
        // The tiny-opening case carries the most variance and the longest correlation, so it gets the
        // widest band; every band stays two orders of magnitude inside the defect it exists to catch.
        double[] tolerance = {0.01, 0.03, 0.01};
        for (int e = 0; e < environments.length; e++) {
            double[] env = environments[e];
            double truth = ALBEDO * (env[0] * env[1] + (1.0 - env[0]) * env[2]);
            double mean = runChain(true, env[0], env[1], env[2], 7L + e);
            assertEquals(truth, mean, truth * tolerance[e],
                    "corrected survivor weight must converge to the integral (env " + e + ")");
        }
    }

    @Test
    void theUncorrectedWeightIsWhatTheAuditMeasured() {
        // The mirror has to be able to fail: the pre-D230 spelling over the same chain is grossly bright.
        // If this ever passes as unbiased, the mirror has stopped modelling the shader and the test above
        // proves nothing.
        double truthBright = ALBEDO * (0.10 * 10.0 + 0.90 * 0.5);
        double brightMean = runChain(false, 0.10, 10.0, 0.5, 7L);
        assertTrue(brightMean > truthBright * 3.0,
                "dividing each history by its previous W should overshoot several-fold, got "
                        + brightMean / truthBright);
        double truthMild = ALBEDO * (0.50 * 2.0 + 0.50 * 1.0);
        double mildMean = runChain(false, 0.50, 2.0, 1.0, 9L);
        assertTrue(mildMean > truthMild * 1.10,
                "even a mild two-level field overshoots by more than 10%, got " + mildMean / truthMild);
    }

    /**
     * One pixel, many frames, the merged regime only. Mirrors world.rgen's post-loop write: the pixel
     * shows (own + count * v_T) / (1 + count), the survivor is drawn over own and the temporal
     * representative in proportion to their receiver-domain weights, and the written W is
     * total / (M * chosenTarget), times the winner's old W when {@code corrected}.
     */
    private static double runChain(boolean corrected, double pBright, double hi, double lo, long seed) {
        SplittableRandom rnd = new SplittableRandom(seed);
        double first = pBright > rnd.nextDouble() ? hi : lo;
        // Start where a couple of spatial merges leave a still pixel: m = 17, fresh W.
        double lT = first;
        double w = 1.0;
        double m = 17.0;
        double sum = 0.0;
        long n = 0;
        for (int frame = 0; frame < FRAMES; frame++) {
            double lOwn = pBright > rnd.nextDouble() ? hi : lo;
            double own = ALBEDO * lOwn;              // ownSuffix; ownTarget is its luminance
            double count = Math.min(m, 16.0);        // temporalCount = min(stored.m, PATH_RESERVOIR_M_CAP)
            double vT = w * ALBEDO * lT;             // candValue: storedW * f*cos/pdf * J * li
            double temporalSum = count * vT;         // candCount * luminance(candValue)
            if (frame >= BURN_IN) {
                sum += (own + count * vT) / (1.0 + count);
                n++;
            }
            double total = own + temporalSum;
            double writtenM = 1.0 + count;
            if (temporalSum > 0.0 && rnd.nextDouble() * total >= own) {
                double chosenTarget = temporalSum / count;
                double written = total / (writtenM * chosenTarget);
                w = corrected ? w * written : written;
            } else {
                lT = lOwn;
                w = own > 0.0 ? total / (writtenM * own) : 0.0;
            }
            m = writtenM;
        }
        return sum / n;
    }

    /** Source with line comments removed, so an assertion cannot be satisfied by prose describing it. */
    private static String code(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (String line : text.split("\n", -1)) {
            int slash = line.indexOf("//");
            out.append(slash < 0 ? line : line.substring(0, slash)).append('\n');
        }
        return out.toString();
    }

    private static String source(String relativePath) throws IOException {
        java.nio.file.Path root = java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !java.nio.file.Files.isRegularFile(root.resolve("settings.gradle"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IOException("Could not locate repository root from " + System.getProperty("user.dir"));
        }
        return String.join("\n", java.nio.file.Files.readAllLines(root.resolve(relativePath)));
    }
}
