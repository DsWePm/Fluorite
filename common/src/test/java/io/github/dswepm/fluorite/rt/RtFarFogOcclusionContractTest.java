package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #41's gate: whether a marched fog step outside the visibility box may ask the grid.
 *
 * <p>The marched ambient splits where the view ray crosses the box, and for as long as this renderer has
 * existed only the middle region consulted the grid -- the two outside it were handed the literal 1.0.
 * So fog past roughly 32 blocks was lit as fully outdoors whatever was overhead, which is the whole of
 * the reported symptom. M25's clamp did not reach it: that changed what volumeSkyOpenness does with an
 * out-of-range coordinate, and this code was not calling volumeSkyOpenness at all.
 */
final class RtFarFogOcclusionContractTest {

    /** Off is the published renderer, or the measurement this switch exists for means nothing. */
    @Test
    void theOffStateIsTheShippedGate() throws IOException {
        String config = source("common/src/main/java/io/github/dswepm/fluorite/FluoriteConfig.java");
        String composite = source("common/src/main/java/io/github/dswepm/fluorite/rt/RtComposite.java");

        assertTrue(config.contains(
                "\"fluorite.rt.fog.farOcclusion\", \"volumetrics.far-fog-occlusion\", false"));
        // Raised on the FIX side, so a zero flag word is what ships today.
        assertTrue(composite.contains("if (FluoriteConfig.Rt.Volumetrics.FAR_FOG_OCCLUSION.value()) {\n"
                + "            flags |= 1 << 3; // ENVIRONMENT_FAR_FOG_OCCLUSION"));
    }

    /**
     * The switch may only widen where the question is asked -- never change what answers it.
     *
     * <p>volumeSkyOpenness is the single answer for every region, on and off. Had the far case grown its
     * own expression -- a fade, a distance falloff, a second constant -- the toggle would be comparing
     * two renderers rather than isolating one decision, and the outdoor look could move with it. The
     * clamp inside volumeSkyOpenness is what makes that safe: outdoors the boundary cell reads open, so
     * asking changes nothing there.
     */
    @Test
    void bothRegionsAskTheSameFunction() throws IOException {
        String volume = shader("volume.slang");

        assertTrue(volume.contains("float skyOpen = askGrid ? volumeSkyOpenness(p) : 1.0;"));
        assertFalse(volume.contains("gridRegion ? volumeSkyOpenness"),
                "the region flag must no longer decide this on its own");
    }

    /**
     * The flag test is hoisted out of the step loop, because it is a frame constant.
     *
     * <p>Not a micro-optimisation: the loop is the fog march, it runs to the step cap on every pixel of
     * every marched segment, and a capability word does not change inside it. Reading it per step would
     * put the cost of the switch on the path whether or not the switch is on.
     */
    @Test
    void theFlagIsReadOncePerRegionRatherThanPerStep() throws IOException {
        String volume = shader("volume.slang");
        int decl = volume.indexOf("bool askGrid = gridRegion");
        assertTrue(decl >= 0, "askGrid must be a hoisted local");

        int loop = volume.indexOf("[loop]", decl);
        assertTrue(loop > decl, "the hoist must come before the march loop it serves");
        // And nothing re-reads the flag inside the region function's body.
        String body = volume.substring(loop);
        int nextFn = body.indexOf("\n}");
        assertFalse(body.substring(0, nextFn).contains("ENVIRONMENT_FAR_FOG_OCCLUSION"),
                "the capability word must not be sampled inside the step loop");
    }

    /**
     * One gate, and this is what proves it: nothing else in the fog march substitutes an open sky.
     *
     * <p>The other three readers -- the structure-strength fade, the closed-form ambient step and the
     * underwater sky term -- call volumeSkyOpenness unconditionally. If a fourth conditional ever grows,
     * this issue comes back somewhere new and the switch above stops describing the behaviour.
     */
    @Test
    void noOtherReaderOfSkyOpennessIsGated() throws IOException {
        String volume = shader("volume.slang");
        int gated = 0;
        for (String line : volume.split("\n", -1)) {
            if (line.contains("volumeSkyOpenness(") && line.contains("? ") && line.contains(" : ")) {
                gated++;
            }
        }
        assertTrue(gated == 1, "expected exactly one conditional reader of sky openness, found " + gated);
    }

    private static String shader(String name) throws IOException {
        return source("shaders/world/" + name);
    }

    private static String source(String relativePath) throws IOException {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("settings.gradle"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IOException("Could not locate repository root from "
                    + System.getProperty("user.dir"));
        }
        // Normalised, because the working tree carries CRLF while every pattern here is written with LF.
        return String.join("\n", Files.readAllLines(root.resolve(relativePath)));
    }
}
