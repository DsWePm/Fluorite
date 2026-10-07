package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Eq. 5 in area measure, with the selected-lobe density used by the reuse-only shader. */
class RtPathFootprintTest {
    private static final int DIFFUSE = 1, SPECULAR = 2, FILM = 3, DELTA = 4, WALK = 5;
    private record Sample(int lobe, double alpha, double pdf) {}
    private record Result(boolean accepted, double denominator) {}
    private static final Sample PREVIOUS = new Sample(SPECULAR, .4, 1);
    private static boolean analytic(Sample s) {
        return s.lobe >= DIFFUSE && s.lobe <= FILM && s.pdf > 0 && Double.isFinite(s.pdf);
    }
    private static double geometry(double distance, double destinationCosine) {
        return distance > 0 && Double.isFinite(distance) && Double.isFinite(destinationCosine)
                ? Math.max(0, destinationCosine) / (distance * distance) : 0;
    }
    private static Result gate(Sample prev, Sample cur, double distance, double forwardCos, double reverseCos,
                               double primaryDistanceSq, double primaryCos, double alphaMin, double c) {
        if (!analytic(prev) || !analytic(cur) || !Double.isFinite(prev.alpha) || prev.alpha < alphaMin
                || !(primaryDistanceSq > 0) || !Double.isFinite(primaryDistanceSq)
                || !(primaryCos > 0) || !Double.isFinite(primaryCos)
                || !(alphaMin >= 0) || !Double.isFinite(alphaMin) || !(c > 0) || !Double.isFinite(c))
            return new Result(false, 0);
        double forward = prev.pdf * geometry(distance, forwardCos);
        double threshold = (c / 100) * 4 * Math.PI * primaryDistanceSq / primaryCos;
        double denominator = forward * cur.pdf;
        if (!(forward > 0) || !Double.isFinite(forward) || !(threshold > 0) || !Double.isFinite(threshold)
                || !(denominator > 0) || !Double.isFinite(denominator) || forward * threshold > 1)
            return new Result(false, 0);
        if (cur.lobe != DIFFUSE) {
            double reverse = cur.pdf * geometry(distance, reverseCos);
            if (!(reverse > 0) || !Double.isFinite(reverse) || reverse * threshold > 1)
                return new Result(false, 0);
        }
        return new Result(true, denominator);
    }
    private static Result gate(Sample cur, double distance, double reverseCos) {
        return gate(PREVIOUS, cur, distance, 1, reverseCos, 100, 1, .2, .02);
    }

    @Test
    void geometryUsesDestinationCosineAndInverseSquareDistance() {
        assertEquals(.25 / 16, geometry(4, .25));
        assertEquals(0, geometry(4, -.25));
        assertEquals(0, geometry(0, 1));
        assertEquals(0, geometry(Double.NaN, 1));
    }
    @Test
    void inverseFootprintRejectsDenseGlossyButDiffuseSkipsIt() {
        assertFalse(gate(new Sample(SPECULAR, .001, 8), 1, 1).accepted);
        Result diffuse = gate(new Sample(DIFFUSE, 1, 1 / Math.PI), 1, 0);
        assertTrue(diffuse.accepted, "a sideways predecessor normal does not trigger the skipped inverse test");
        assertEquals(1 / Math.PI, diffuse.denominator, 1e-12);
        assertTrue(gate(new Sample(SPECULAR, .001, 8), 10, 1).accepted,
                "distance can permit a glossy x_k; no second roughness gate");
    }
    @Test
    void thePrimaryProjectionAndTheHundredFactorArePartOfTheThreshold() {
        Sample cur = new Sample(DIFFUSE, 1, 1 / Math.PI);
        assertTrue(gate(cur, 1, 0).accepted); // 1 >= .02/100 * 4pi * 100
        assertFalse(gate(PREVIOUS, cur, 1, 1, 0, 100, .1, .2, .02).accepted);
        assertFalse(gate(PREVIOUS, cur, 1, 1, 0, 100, 1, .2, 2).accepted);
    }
    @Test
    void thresholdEqualityIsAcceptedWithoutAnEpsilonOrDensityClamp() {
        double c = 100 / (4 * Math.PI); // unit primary footprint
        Sample cur = new Sample(SPECULAR, .3, 1);
        assertTrue(gate(PREVIOUS, cur, 1, 1, 1, 1, 1, .2, c).accepted);
        assertFalse(gate(new Sample(SPECULAR, .4, 1.000001), cur, 1, 1, 1, 1, 1, .2, c).accepted);
    }
    @Test
    void changingSceneUnitsDoesNotChangeEligibility() {
        Sample cur = new Sample(FILM, .08, .7);
        Result metres = gate(PREVIOUS, cur, 5, .5, .8, 100, .6, .2, .02);
        Result scaled = gate(PREVIOUS, cur, 35, .5, .8, 4900, .6, .2, .02);
        assertTrue(metres.accepted);
        assertEquals(metres.accepted, scaled.accepted);
        assertEquals(metres.denominator / 49, scaled.denominator, 1e-14);
    }
    @Test
    void thePreviousSampledLobeUsesLinearAlpha() {
        Sample cur = new Sample(DIFFUSE, 1, .1);
        assertFalse(gate(new Sample(SPECULAR, .1, .1), cur, 10, 1, 1, 100, 1, .2, .02).accepted);
        assertTrue(gate(new Sample(SPECULAR, .2, .1), cur, 10, 1, 1, 100, 1, .2, .02).accepted);
        assertTrue(gate(new Sample(DIFFUSE, 1, .1), cur, 10, 1, 1, 100, 1, .2, .02).accepted);
    }
    @Test
    void unsupportedAndDegenerateDensitiesRemainEmpty() {
        for (Sample bad : new Sample[] {new Sample(DELTA, 0, -1), new Sample(WALK, 0, -1),
                new Sample(SPECULAR, .5, 0), new Sample(FILM, .08, Double.NaN)}) {
            assertFalse(gate(bad, 10, 1).accepted);
            assertFalse(gate(bad, new Sample(DIFFUSE, 1, .1), 10, 1, 1, 100, 1, .2, .02).accepted);
        }
        for (double cos : new double[] {0, -1, Double.NaN})
            assertFalse(gate(PREVIOUS, new Sample(DIFFUSE, 1, .1), 10, 1, 1, 100, cos, .2, .02).accepted);
    }
    @Test
    void theJointSourceDenominatorIncludesBothSelectedLobeDensities() {
        Result r = gate(new Sample(SPECULAR, .3, 2), new Sample(FILM, .08, .7), 4, .5, .25,
                100, 1, .2, .02);
        assertTrue(r.accepted);
        assertEquals(2 * (.5 / 16) * .7, r.denominator, 1e-12);
    }
    @Test
    void shaderFormulaAndItsConsumerUseTheSameParameters() throws Exception {
        String s = source("shaders/world/path_reuse_sampling.slang");
        for (String term : new String[] {"previous.alpha < alphaMin", "previous.pdf * forwardG",
                "forwardDensity * current.pdf", "(footprintScale / 100.0) * (4.0 * PI)",
                "primaryDistanceSq / primaryCosine", "current.lobe != REUSE_LOBE_DIFFUSE",
                "reuseRayGeometry(-edgeDirection, edgeDistance, previousNormal)",
                "forwardDensity * primaryFootprint > 1.0", "reverseDensity * primaryFootprint > 1.0",
                "jacDenom = sourceDenominator", "REUSE_MIN_ALPHA = 0.2", "REUSE_FOOTPRINT_SCALE = 0.02"})
            assertTrue(s.contains(term), term);
        assertFalse(s.contains("alpha * alpha") || s.contains("clamp("));
        String loop = source("shaders/world/path_reuse_trace.slang");
        assertTrue(loop.contains("sampleInfo.pdf = bsdfPdf;"));
        assertTrue(loop.contains("sampleInfo.alpha = vtx.rough;"));
        assertTrue(loop.contains("sampleInfo.alpha = 1.0;"));
        assertTrue(loop.contains("sampleInfo.alpha = alpha;"));
        assertTrue(loop.contains("float footprintEdgeDistance = payload.hitT;"));
        assertTrue(loop.contains("float3 footprintEdgeDirection = rd;"));
        assertTrue(loop.contains("indirectDepth == 0 && seg.bounce == 0 && payloadMaterial() == MATERIAL_OPAQUE"));
        assertTrue(loop.contains("!footprintFound && hitDepth >= 1"));
        assertTrue(loop.contains("!footprintPrefixWalk"));
        assertTrue(loop.contains("footprintPrefixWalk = footprintPrefixWalk || walked;"));
        assertTrue(loop.contains("footprintPreviousValid = false;"));
        assertTrue(loop.contains("REUSE_MIN_ALPHA, REUSE_FOOTPRINT_SCALE, jacDenom"));
        assertFalse(source("shaders/world/world.rgen.slang").contains("path_reuse_sampling.slang"));
    }
    @Test
    void diagnosticLanesAreConsumedAndDepthBinsPartitionAcceptedPairs() throws Exception {
        String loop = source("shaders/world/path_reuse_trace.slang");
        assertTrue(loop.contains("pathReservoirStatsAddr)[9], 1u"));
        assertTrue(loop.contains("pathReservoirStatsAddr)[10], 1u"));
        assertTrue(loop.contains("pathReservoirStatsAddr)[hitDepth == 1 ? 11 : 12], 1u"));
        String stats = source("common/src/main/java/io/github/dswepm/fluorite/rt/RtPathReservoirStats.java");
        assertEquals(13, RtPathReservoirStats.LANES);
        for (int i = 9; i < 13; i++) assertTrue(stats.contains("memGetInt(src.mapped + " + i * 4 + "L)"));
        assertTrue(stats.contains("footprintAccepted, footprintAttempt"));
        assertTrue(stats.contains("footprintK2, footprintK3"));
    }
    private static String source(String relative) throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("settings.gradle"))) root = root.getParent();
        assertNotNull(root);
        return Files.readString(root.resolve(relative));
    }
}
