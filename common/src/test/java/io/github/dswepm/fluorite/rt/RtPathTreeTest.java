package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/** Initial D-event resampling: variable external RR, RGB factorisation and the real shader consumer. */
final class RtPathTreeTest {
    private static double lum(double[] v) {
        return .2126 * v[0] + .7152 * v[1] + .0722 * v[2];
    }

    private static double[] mul(double[] a, double[] b) {
        return new double[]{a[0] * b[0], a[1] * b[1], a[2] * b[2]};
    }

    private static double[] scale(double[] a, double s) {
        return new double[]{a[0] * s, a[1] * s, a[2] * s};
    }

    private static void same(double[] expected, double[] actual) {
        for (int c = 0; c < 3; c++)
            assertEquals(expected[c], actual[c], 2e-12 * Math.max(1, Math.abs(expected[c])));
    }

    /** Exact marginalisation over the WRS index for the observed tree, without Monte Carlo tolerance. */
    private static double[] marginal(double[][] f, double[] survival, int count) {
        double total = 0;
        for (int i = 0; i < count; i++) total += lum(f[i]) / survival[i];
        double[] result = new double[3];
        if (total == 0) return result;
        for (int i = 0; i < count; i++) {
            double target = lum(f[i]);
            if (target == 0) continue;
            double probability = (target / survival[i]) / total;
            double writtenW = total / target;
            for (int c = 0; c < 3; c++) result[c] += probability * f[i][c] * writtenW;
        }
        return result;
    }

    @Test
    void unequalSurvivalProbabilitiesMarginaliseToTheObservedTracedSum() {
        SplittableRandom random = new SplittableRandom(871);
        for (int trial = 0; trial < 1000; trial++) {
            double[][] f = new double[5][3];
            double[] survival = new double[5];
            double[] expected = new double[3];
            double product = 1;
            for (int i = 0; i < f.length; i++) {
                product *= .1 + .9 * random.nextDouble();
                survival[i] = product;
                for (int c = 0; c < 3; c++) {
                    f[i][c] = random.nextDouble() < .2 ? 0 : random.nextDouble(10);
                    expected[c] += f[i][c] / survival[i];
                }
            }
            same(expected, marginal(f, survival, f.length));
        }
    }

    @Test
    void enumeratingEveryRouletteStopAndWrsPickRecoversTheNoRouletteIntegral() {
        // The first q is a prefix survival; later q's are suffix survivals. A kill must retain all
        // earlier terminal events, rather than discard the whole record or use the final q for all.
        double[][] f = {{0, 3, 1}, {5, 0, 4}, {.2, 1, 7}};
        double[] q = {.3, .7, .2};
        double[] survival = new double[q.length];
        double product = 1;
        for (int i = 0; i < q.length; i++) survival[i] = product *= q[i];
        double[] expectation = new double[3];
        for (int reached = 1; reached <= f.length; reached++) {
            double outcomeProbability = survival[reached - 1]
                    * (reached < f.length ? 1 - q[reached] : 1);
            double[] conditional = marginal(f, survival, reached);
            for (int c = 0; c < 3; c++) expectation[c] += outcomeProbability * conditional[c];
        }
        same(new double[]{5.2, 4, 12}, expectation);
    }

    @Test
    void aSingleEventHasExactlyOneExternalSurvivalCompensation() {
        same(new double[]{4, 8, 12}, marginal(new double[][]{{1, 2, 3}}, new double[]{.25}, 1));
    }

    @Test
    void zeroTargetEventsDoNotChangeTheTreeAndAnEmptyTreeHasNoEstimate() {
        same(new double[]{0, 0, 0}, marginal(new double[][]{{0, 0, 0}}, new double[]{.2}, 1));
        same(new double[]{2, 4, 6}, marginal(new double[][]{{0, 0, 0}, {1, 2, 3}}, new double[]{.8, .5}, 2));
    }

    @Test
    void unitPrefixTailPreservesChannelsThatTheSourcePrefixCannotSee() {
        double[] source = {0, .6, 0};
        double[] reconScatter = {.2, .4, .9};
        double[] edgeTransmission = {.8, .3, .5};
        double[] nextScatter = {.6, .2, .7};
        double[] endpoint = {2, 4, 6};
        double[] incident = mul(mul(edgeTransmission, nextScatter), endpoint);
        double[] own = mul(mul(source, reconScatter), incident);
        assertTrue(lum(own) > 0);
        assertEquals(0, own[0]);
        assertEquals(0, own[2]);
        assertTrue(incident[0] > 0 && incident[2] > 0);
        // Recovering the suffix by dividing own by source would lose both of these channels.
        same(new double[]{.96, .24, 2.1}, incident);
    }

    @Test
    void sourceFactorIdentityIncludesBothPrefixAndSuffixRoulette() {
        double[] prefix = {.2, .5, .8}, scatter = {.3, .6, .4}, tail = {.7, .2, .9};
        double[] endpoint = {4, 2, 8};
        double prefixQ = .4, suffixQ = .3;
        double[] sourceWeight = mul(prefix, scatter);
        double[] incident = mul(tail, endpoint);
        double[] traced = scale(mul(sourceWeight, incident), 1 / (prefixQ * suffixQ));
        same(mul(sourceWeight, incident), scale(traced, prefixQ * suffixQ));
    }

    @Test
    void everySupportedDepthLobeAndMediumTagRoundTripsWithoutOverlap() throws Exception {
        String shader = source("shaders/world/path_reuse_tree.slang");
        for (String constant : new String[]{"REUSE_RECORD_DEPTH_SHIFT = 1u", "REUSE_RECORD_TECHNIQUE_SHIFT = 5u",
                "REUSE_RECORD_LOBE_SHIFT = 8u", "REUSE_RECORD_CURRENT_MEDIUM_SHIFT = 12u",
                "REUSE_RECORD_OUTER_MEDIUM_SHIFT = 14u", "REUSE_RECORD_PRIMARY_SHIFT = 16u",
                "REUSE_TECHNIQUE_D = 1u", "REUSE_PRIMARY_OPAQUE = 1u"})
            assertTrue(shader.contains(constant), constant);
        for (int k = 2; k <= 15; k++) for (int lobe = 1; lobe <= 3; lobe++)
            for (int current = 0; current < 4; current++) for (int outer = 0; outer < 4; outer++) {
                int bits = 1 | k << 1 | 1 << 5 | lobe << 8 | current << 12 | outer << 14 | 1 << 16;
                assertEquals(1, bits & 1);
                assertEquals(k, (bits >>> 1) & 15);
                assertEquals(1, (bits >>> 5) & 7);
                assertEquals(lobe, (bits >>> 8) & 7);
                assertEquals(current, (bits >>> 12) & 3);
                assertEquals(outer, (bits >>> 14) & 3);
                assertEquals(1, (bits >>> 16) & 3);
                assertEquals(0, bits & ~0x3f7ff);
            }
    }

    @Test
    void theShaderProducesTaggedRecordsAndKeepsRouletteOutOfIncidentRadiance() throws Exception {
        String tree = source("shaders/world/path_reuse_tree.slang");
        for (String term : new String[]{"float weight = target / survival;", "float total = tree.weightSum + weight;",
                "rndf(stream) * total < weight", "tree.selectedIncident = incident;",
                "record.W = tree.weightSum / tree.selectedTarget;", "record.targetOwn = tree.selectedTarget;",
                "record = reuseStoreIncident(record, tree.selectedIncident);", "!reuseNonnegativeFinite(tree.selectedIncident)",
                "(mediumFlags & ~0x00030003u) != 0u", "!all(isfinite(unpackHalf2(record.primaryUv)))"})
            assertTrue(tree.contains(term), term);
        assertFalse(tree.contains("clamp("));
        String tracer = source("shaders/world/path_reuse_trace.slang");
        for (String term : new String[]{"dSourceWeight = throughput * rrSurvival;",
                "dTailWeight *= segIntegral.transmittance;", "dTailWeight *= clouds.transmittance;",
                "dTailWeight *= continuation.weight;", "float3 incident = dTailWeight * brdf * sampledLightIrradiance * ndl * vis;",
                "reuseTreeUpdate(dTree, dSourceWeight, incident, rrSurvival, reuseSeed)",
                "dRecord.pathSeed = pathSeedBase;", "dRecord.objectId = footprintPrimary.objectId;",
                "dRecord.primaryUv = packHalf2(footprintPrimary.uv);", "dRecord.dirK = packUnorm16x2(octEncode(rd));",
                "dRecord.jacDenom = jacDenom;", "reuseDRecordBits(uint(hitDepth + 1), continuation.lobe, activeMediumFlags)",
                "PackedPathReservoir produced = reuseFinishTree(dRecord, dTree);", "worldPush.frameIndex & 1u)] = produced;"})
            assertTrue(tracer.contains(term), term);
        assertEquals(2, tracer.split("rrSurvival \\*= q;", -1).length - 1);
        assertFalse(tracer.contains("incident / throughput") || tracer.contains("L / throughput"));
        assertFalse(source("shaders/world/world.rgen.slang").contains("path_reuse_tree.slang"));
    }

    @Test
    void eachAnalyticLobeExportsTheActualMultiplicativeWeightWithoutNewDraws() throws Exception {
        String tracer = source("shaders/world/path_reuse_trace.slang");
        int start = tracer.indexOf("bool reuseSampleContinuation(");
        String sampler = tracer.substring(start, tracer.indexOf("\n}\n", start));
        for (String factor : new String[]{"rain.film * F * ggxG1(ndl2, alpha) / pf",
                "F * ggxG1Aniso(dot(l, bc.t), dot(l, bc.b), ndl2, bc.alphaX, bc.alphaY) * rainBaseTransmission(bc, l) / ((1.0 - pf) * ps)",
                "diffAlb * rainBaseTransmission(bc, l) / lobeProb"}) {
            assertTrue(sampler.contains("sampleInfo.weight = " + factor + ";"));
            assertTrue(sampler.replaceAll("\\s+", " ").contains("throughput *= " + factor + ";"));
        }
        assertFalse(sampler.contains("reuseSeed"));
    }

    @Test
    void everyNewDiagnosticLaneIsWrittenAndReadWithRecordDepthsPartitioned() throws Exception {
        assertEquals(40, RtPathReservoirStats.LANES);
        String tracer = source("shaders/world/path_reuse_trace.slang");
        for (int i : new int[]{13, 14, 17, 18, 19})
            assertTrue(tracer.contains("pathReservoirStatsAddr)[" + i + "], 1u"));
        assertTrue(tracer.contains("== 2u ? 15 : 16"));
        String stats = source("common/src/main/java/io/github/dswepm/fluorite/rt/RtPathReservoirStats.java");
        for (int i = 13; i < 20; i++) assertTrue(stats.contains("memGetInt(src.mapped + " + 4 * i + "L)"));
    }

    private static String source(String relative) throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("settings.gradle"))) root = root.getParent();
        assertNotNull(root);
        return Files.readString(root.resolve(relative)).replace("\r\n", "\n");
    }
}
