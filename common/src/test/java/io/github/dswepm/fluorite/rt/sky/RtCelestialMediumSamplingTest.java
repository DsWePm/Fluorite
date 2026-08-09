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
