package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Energy-contract regressions at the participating-medium source seam (D61).
 *
 * <p>The JVM cannot execute the Slang estimators, so the first test guards the real shader sources while
 * shader compilation remains their executable type check. The second test executes the CPU calculation
 * that is uploaded with the cloud lighting push data and checks it against the independently rearranged
 * thick-slab reflectance identity.
 */
final class RtVolumeEnergyContractTest {
    private static final double PI = Math.PI;

    @Test
    void normalizedPhaseConsumesIrradianceWithoutAnExtraFourPiGain() throws IOException {
        String ambient = source("shaders/world/volume_source.slang");
        String enclosed = source("shaders/world/volume.slang");
        String cloud = source("shaders/world/cloud.slang");
        String lighting = source("shaders/world/lighting.slang");

        assertFalse(ambient.contains("4.0 * VOLUME_PI * volumeHg"));
        assertFalse(enclosed.contains("4.0 * PI * enclosedPhase"));
        assertFalse(cloud.contains("4.0 * PI * cloudPhase"));
        assertFalse(lighting.contains("4.0 * PI * hg(dot(rd, wi)"));

        assertTrue(ambient.contains("volumeHg(dot(viewDir, sunDir), phaseG)"));
        assertTrue(enclosed.contains("enclosedPhase(dot(rd, refractedLight), phaseG, albedo)"));
        assertTrue(cloud.contains("cloudPhase(dot(rd, lightDir), cloudPhaseG())"));
        assertTrue(lighting.contains("hg(dot(rd, wi), phaseG)"));
    }

    @Test
    void cloudDiffuseSourceScaleReproducesTheApprovedThickSlabReflectance() throws IOException {
        String common = source("shaders/world/world_common.slang");
        String cloud = source("shaders/world/cloud.slang");
        String composite = source(
                "common/src/main/java/io/github/dswepm/fluorite/rt/RtComposite.java");
        assertTrue(common.contains("w diffuse source A/(4*pi)"));
        assertTrue(cloud.contains(
                "cloudDiffuseSourceScale() * exp(-diffusionRate * tauSun)"));
        assertTrue(composite.contains("RtCloudLighting.diffuseSourceScale(albedo, phaseG)"));

        assertReflectance(0.9804f, 0.8f);
        assertReflectance(0.999f, 0.8f);
        assertReflectance(0.75f, -0.2f);

        float conservativeScale = RtCloudLighting.diffuseSourceScale(1.0f, 0.8f);
        assertEquals(1.0, PI * conservativeScale, 1.0e-6,
                "A conservative thick cloud must approach unit reflectance, not one quarter");
    }

    private static void assertReflectance(float albedo, float phaseG) {
        double scale = RtCloudLighting.diffuseSourceScale(albedo, phaseG);
        double diffusionRate = Math.sqrt(3.0 * (1.0 - albedo));
        double recoveredReflectance = PI * scale / (1.0 + diffusionRate);

        double similarity = Math.sqrt((1.0 - albedo) / (1.0 - albedo * phaseG));
        double expectedReflectance = (1.0 - similarity) / (1.0 + similarity);
        assertEquals(expectedReflectance, recoveredReflectance, 2.0e-6,
                "CPU source scale must reproduce the two-stream half-space limit");
    }

    private static String source(String relativePath) throws IOException {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("settings.gradle"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IOException("Could not locate repository root from " + System.getProperty("user.dir"));
        }
        return Files.readString(root.resolve(relativePath));
    }
}
