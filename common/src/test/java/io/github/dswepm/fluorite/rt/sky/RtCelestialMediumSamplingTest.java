package io.github.dswepm.fluorite.rt.sky;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural regression at the shader call-site seam for the dawn/dusk medium-light discontinuity.
 *
 * <p>The JVM cannot execute Slang RayQuery stages, so this test deliberately verifies the real stage
 * sources rather than a second Java port of their maths. Full Slang compilation remains the executable
 * ABI/type check; docs/devlog/M15-M17-medium-lighting.md records the deterministic horizon probe.
 */
final class RtCelestialMediumSamplingTest {
    @Test
    void everyParticipatingMediumUsesTheSharedFiniteCelestialEmitter() throws IOException {
        String common = shader("world_common.slang");
        String math = shader("math.slang");
        String volume = shader("volume.slang");
        String froxel = shader("sky_froxel.comp.slang");
        String visibility = shader("volume_visibility.comp.slang");

        assertTrue(common.contains("sampleSquareLightDirection("));
        assertTrue(common.contains("sampleSquareLight("));
        assertTrue(math.contains("sampleSquareLight("));
        assertTrue(volume.contains("sampleSquareLight("));
        assertTrue(froxel.contains("sampleSquareLight("));
        assertTrue(visibility.contains("sampleSquareLight("));
        assertTrue(froxel.contains("celestialDirectionVisible(sampledLightDir)"));
        assertTrue(visibility.contains("celestialDirectionVisible(lightDir)"));

        // The old water path enabled the entire orange direct term at one solar elevation.
        assertFalse(volume.contains("sunY > 1.0e-3"));
        // Froxel and the visibility grid must trace the sampled emitter direction, not its centre.
        assertFalse(froxel.contains("sunOccluded(p, sunDir)"));
        assertFalse(visibility.contains("occluded(p, wp.lightDir.xyz)"));
    }

    @Test
    void theVisibilityGridClampsAtItsFacesInsteadOfReportingOpenSky() throws IOException {
        String visibility = shader("volume_visibility.slang");

        // A SPATIAL CACHE MISSES BY CLAMPING, NOT BY RESETTING. The grid reaches about 32 blocks
        // horizontally at the default cell, and every fog or underwater sample past that used to be told
        // the sky was wide open -- which is why distant cave fog glowed while nearby fog was correct.
        // Asking the boundary instead costs nothing and, in a cave, the boundary is roofed.
        assertTrue(visibility.contains("float3 uvw = saturate(g);"));

        // ...EXCEPT UPWARD, and that asymmetry is a property of the quantity, not a special case: sky
        // openness only increases as you rise, so the top cell is a lower BOUND on everything above it
        // and clamping there would assert a bound as a measurement. Concretely, the underwater sky term
        // samples just above the water SURFACE, so past the grid's 16-block vertical half-extent that
        // query lands above the grid -- where the top cell is still underwater and reads roofed, which
        // made underwater scattering vanish as a step at a fixed depth.
        assertTrue(visibility.contains("if (g.y > 1.0)"));

        // The two things that made the miss wrong, and neither may come back on its own:
        //
        // The out-of-bounds early return, which IS the reset.
        assertFalse(visibility.contains("any(uvw > float3(1.0, 1.0, 1.0))"));
        // And the fade toward 1 over the outermost cells, which existed only to hide the seam that reset
        // created. With clamping the function is continuous by construction, so the fade has nothing left
        // to smooth -- and it had become a defect in its own right, brightening the answer as a sample
        // approached the faces, which underground is exactly the wrong direction.
        assertFalse(visibility.contains("VIS_EDGE_FADE"));
        assertFalse(visibility.contains("lerp(1.0, v.g,"));

        // The one remaining path that still answers "lit" is a grid that was never published, and there
        // is genuinely nothing to clamp to there. It must stay 1 rather than 0: outdoors, and before this
        // grid existed at all, lit is the correct answer, and defaulting to 0 would black out the world.
        assertTrue(visibility.contains("if (cell <= 0.0)"));
    }

    /**
     * The visibility grid answers to its own knob, never to the fog toggle (#41).
     *
     * <p>The grid is a visibility structure and fog is not its only reader — the water medium's two
     * enclosed sky terms sample it half a block above the water surface. While publication was gated on
     * {@code Volumetrics.ENABLED}, turning fog off left every sampler answering "fully lit" and cave
     * water glowed: a fog switch changing how water looks, which iron law 8 exists to forbid.
     *
     * <p>This is the second time this exact coupling has bitten. The water ripple solver was once nested
     * inside {@code VISIBILITY_CELL_SIZE > 0 && Volumetrics.ENABLED} too, so turning the fog off silently
     * stopped the ripples — the comment recording that is still a few hundred lines below the bake. Two
     * incidents of one shape is what makes this worth a test rather than a careful reading.
     *
     * <p>{@code VISIBILITY_CELL_SIZE} remains the grid's own off switch and is deliberately still here:
     * zeroing it is an informed opt out, and the (1,1) fallback is its documented meaning.
     */
    @Test
    void theVisibilityGridIsPublishedAndBakedWithoutAskingTheFogToggle() throws IOException {
        String composite = source("common/src/main/java/io/github/dswepm/fluorite/rt/RtComposite.java");

        // Comments stripped first. Both sites now carry a note explaining what they no longer gate on,
        // and those notes name the toggle -- so a raw substring search would find the explanation and
        // report the very bug it describes.
        String origin = code(between(composite, "private static Float4 visibilityGridOrigin(", "\n    }"));
        String bake = code(before(composite, "skyLuts.recordVisibilityBake(", 8));

        // Neither the publication of the grid's origin nor the bake dispatch may consult the fog switch.
        assertFalse(origin.contains("Volumetrics.ENABLED"),
                "visibilityGridOrigin must not gate on the fog toggle; see #41");
        assertFalse(bake.contains("Volumetrics.ENABLED"),
                "the visibility bake must not gate on the fog toggle; see #41");

        // But both must still honour the grid's own knob, which is the supported way to switch it off.
        assertTrue(origin.contains("VISIBILITY_CELL_SIZE"));
        assertTrue(bake.contains("VISIBILITY_CELL_SIZE"));

        // And the shader keeps the no-grid fallback, which now means only "no grid was asked for".
        assertTrue(shader("volume_visibility.slang").contains("if (cell <= 0.0)"));
    }

    /** {@code text} with line comments removed, so an assertion tests the code and not the prose about it. */
    private static String code(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (String line : text.split("\n", -1)) {
            int slash = line.indexOf("//");
            out.append(slash < 0 ? line : line.substring(0, slash)).append('\n');
        }
        return out.toString();
    }

    /** The text of the {@code needle} declaration up to {@code end}, so a check cannot drift into its neighbours. */
    private static String between(String haystack, String needle, String end) {
        int from = haystack.indexOf(needle);
        if (from < 0) {
            throw new AssertionError("could not find " + needle);
        }
        int to = haystack.indexOf(end, from);
        return to < 0 ? haystack.substring(from) : haystack.substring(from, to);
    }

    /** The {@code lines} source lines immediately preceding {@code needle} — a call site's guard condition. */
    private static String before(String haystack, String needle, int lines) {
        int at = haystack.indexOf(needle);
        if (at < 0) {
            throw new AssertionError("could not find " + needle);
        }
        int start = at;
        for (int i = 0; i < lines && start > 0; i++) {
            start = haystack.lastIndexOf('\n', start - 1);
            if (start < 0) {
                return haystack.substring(0, at);
            }
        }
        return haystack.substring(start, at);
    }

    private static String source(String relativePath) throws IOException {
        return Files.readString(repositoryRoot().resolve(relativePath));
    }

    private static Path repositoryRoot() throws IOException {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("settings.gradle"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IOException("Could not locate repository root from " + System.getProperty("user.dir"));
        }
        return root;
    }

    private static String shader(String name) throws IOException {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("settings.gradle"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IOException("Could not locate repository root from " + System.getProperty("user.dir"));
        }
        return Files.readString(root.resolve("shaders/world").resolve(name));
    }
}
