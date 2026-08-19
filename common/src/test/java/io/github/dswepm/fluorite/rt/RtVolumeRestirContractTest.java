package io.github.dswepm.fluorite.rt;

import io.github.dswepm.fluorite.FluoriteConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M25 step (a): the two volumetric-ReSTIR source switches exist, and their OFF positions are the shipped
 * renderer bit for bit.
 *
 * <p>Iron law 8 is the whole point of this file. An A/B switch whose off position is merely "close to" the
 * published behaviour measures nothing, because every difference it then reports is ambiguous between the
 * change under test and the scaffolding around it. Here the guarantee is structural rather than careful:
 * both bits are RAISED on the ReSTIR side, so a zero environment-flags word is the shipped picture and no
 * code path has to remember to clear anything.
 *
 * <p>D196 chose two independent switches over one scope knob precisely so the published behaviour stays a
 * REACHABLE POSITION rather than a branch deleted along the way.
 */
final class RtVolumeRestirContractTest {

    /**
     * The defaults are the published renderer.
     *
     * <p>Not a style preference. Every picture and performance comparison this milestone makes is a ratio
     * against this position, so a default that quietly enabled either half would make the whole measurement
     * programme report differences against a baseline that never shipped.
     */
    @Test
    void bothSwitchesDefaultToTheBehaviourThatAlreadyShipped() {
        assertEquals("grid", FluoriteConfig.Rt.Volumetrics.VOLUME_RESTIR_NEAR_SOURCE.defaultValue());
        assertEquals("clamp", FluoriteConfig.Rt.Volumetrics.VOLUME_RESTIR_FAR_SOURCE.defaultValue());
        assertFalse(FluoriteConfig.Rt.Volumetrics.volumeRestirNear());
        assertFalse(FluoriteConfig.Rt.Volumetrics.volumeRestirFar());
    }

    /**
     * The bits are raised on the ReSTIR side, never on the shipped side.
     *
     * <p>The inverse spelling would pass every functional test and still be wrong: it makes "no flags" mean
     * "the new thing", so any future site that forgets to populate the word silently opts into ReSTIR.
     * worldPush.flags bit 1 and bit 15 already follow this convention.
     */
    @Test
    void aZeroEnvironmentWordIsTheShippedPicture() throws IOException {
        String composite = code(source("common/src/main/java/io/github/dswepm/fluorite/rt/RtComposite.java"));
        String body = between(composite, "private static int environmentFlags(", "\n    }");

        assertTrue(body.contains("if (FluoriteConfig.Rt.Volumetrics.volumeRestirNear()) {"));
        assertTrue(body.contains("if (FluoriteConfig.Rt.Volumetrics.volumeRestirFar()) {"));
        assertTrue(body.contains("flags |= 1 << 2;"));
        assertTrue(body.contains("flags |= 1 << 3;"));
        // The negated spelling is the failure this test exists for.
        assertFalse(body.contains("!FluoriteConfig.Rt.Volumetrics.volumeRestirNear()"));
        assertFalse(body.contains("!FluoriteConfig.Rt.Volumetrics.volumeRestirFar()"));
    }

    /**
     * The shader constants name the same two bits the Java raises.
     *
     * <p>Two literals that agree today are exactly how they stop agreeing later, and this pair fails as a
     * picture -- fog reading the wrong switch -- rather than as an error.
     */
    @Test
    void theShaderConstantsAndTheJavaBitsAreTheSameTwoBits() throws IOException {
        String common = code(source("shaders/world/world_common.slang"));

        assertTrue(common.contains("ENVIRONMENT_VOLUME_RESTIR_NEAR = 1u << 2u;"));
        assertTrue(common.contains("ENVIRONMENT_VOLUME_RESTIR_FAR = 1u << 3u;"));
        // Bits 0 and 1, and the 8-15 layer byte, were already spoken for.
        assertTrue(common.contains("ENVIRONMENT_AMBIENT_UNOCCLUDED = 1u << 0u;"));
        assertTrue(common.contains("ENVIRONMENT_FROXEL_LOCAL_LIGHTS = 1u << 1u;"));
        assertTrue(common.contains("ENVIRONMENT_LAYER_SHIFT = 8u;"));
    }

    /**
     * D197's floor is enforced, not merely documented.
     *
     * <p>At p=16 the measurement was 2.1-4.3 samples per cell per frame: the spatiotemporal reuse this
     * milestone exists to demonstrate cannot amortise anything at that density, and the table costs 685 MB
     * while failing to. A config below the floor does not produce a worse picture -- it produces a
     * configuration in which the milestone's proposition is untestable.
     */
    @Test
    void theLodKnobCannotBeSetFinerThanTheReuseCanFeed() throws IOException {
        assertEquals(32, FluoriteConfig.Rt.Volumetrics.VOLUME_RESTIR_LOD_PIXELS.defaultValue().intValue());
        assertEquals(45, FluoriteConfig.Rt.Volumetrics.VOLUME_RESTIR_EVICTION_FRAMES.defaultValue().intValue());
        assertEquals(4, FluoriteConfig.Rt.Volumetrics.VOLUME_RESTIR_CANDIDATES.defaultValue().intValue());
        assertTrue(code(source("common/src/main/java/io/github/dswepm/fluorite/FluoriteConfig.java"))
                .contains("Math.clamp(VOLUME_RESTIR_LOD_PIXELS.value(), 16, 256)"));
    }

    /**
     * Every key the option screen asks for exists in all three maintained locales.
     *
     * <p>A missing key renders as the raw translation id in the settings screen, which is the kind of defect
     * that ships because nobody opens that tab in the language it broke in.
     */
    @Test
    void allThreeMaintainedLocalesCarryEveryKeyTheScreenAsksFor() throws IOException {
        String[] keys = {
            "fluorite.options.rt.volumeRestirNearSource",
            "fluorite.options.rt.volumeRestirNearSource.grid",
            "fluorite.options.rt.volumeRestirNearSource.restir",
            "fluorite.options.rt.volumeRestirNearSource.tooltip",
            "fluorite.options.rt.volumeRestirFarSource",
            "fluorite.options.rt.volumeRestirFarSource.clamp",
            "fluorite.options.rt.volumeRestirFarSource.restir",
            "fluorite.options.rt.volumeRestirFarSource.tooltip",
        };
        for (String locale : new String[] {"en_us", "zh_cn", "zh_tw"}) {
            String lang = source("common/src/main/resources/assets/fluorite/lang/" + locale + ".json");
            for (String key : keys) {
                assertTrue(lang.contains(quote() + key + quote() + ":"), "missing in " + locale + ": " + key);
            }
        }
    }

    private static String quote() {
        return String.valueOf((char) 34);
    }

    private static String between(String haystack, String needle, String end) {
        int start = haystack.indexOf(needle);
        assertTrue(start >= 0, "not found: " + needle);
        int stop = haystack.indexOf(end, start);
        assertTrue(stop > start, "unterminated: " + needle);
        return haystack.substring(start, stop);
    }

    /** Strip // comments so an assertion cannot be satisfied by the prose that describes the bug. */
    private static String code(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (String line : text.split("\n", -1)) {
            int slash = line.indexOf("//");
            out.append(slash >= 0 ? line.substring(0, slash) : line).append('\n');
        }
        return out.toString();
    }

    /** CR stripped: the shader tree is CRLF, so multi-line assertions otherwise never match. */
    private static String source(String relativePath) throws IOException {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) {
            root = root.getParent();
        }
        assertTrue(root != null, "repository root not found");
        return Files.readString(root.resolve(relativePath)).replace(String.valueOf((char) 13), "");
    }
}
