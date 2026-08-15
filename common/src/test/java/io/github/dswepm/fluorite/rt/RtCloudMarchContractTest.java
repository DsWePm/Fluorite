package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Structural contract for the D160 experiment's rollback and its retained last-step correction. */
final class RtCloudMarchContractTest {
    @Test
    void escapedSkySegmentsKeepTheEstablishedBudgetAndClipTheirLastStep() throws IOException {
        String raygen = source("shaders/world/world.rgen.slang");
        String cloud = source("shaders/world/cloud.slang");
        // The density field moved to a bindings-free module so the shadow bake can share it.
        String density = source("shaders/world/cloud_density.slang");

        assertTrue(raygen.contains(
                "float cloudMaxT = payload.hitT < 0.0 ? RAY_FAR : payload.hitT;"));
        // The step now comes from THIS RAY'S crossing rather than from how far away the deck is. The
        // clipping half of this test's name is the part that must not regress: whatever the schedule, the
        // last step may not run past tExit, because the closed-form source integration is exact only for
        // the interval it is handed.
        assertTrue(cloud.contains("float step = min(stepBase, tExit - t);"));
        assertTrue(cloud.contains(
                "float stepBase = clamp(crossing / float(max(budget.maxSteps, 1)),"
                        + " CLOUD_STEP_MIN, CLOUD_STEP_MAX);"));
        assertFalse(cloud.contains("max(24.0, t * 0.125)"));
        // Filtering and stepping are two questions; the mip is selected by the same step.
        assertTrue(density.contains("public float cloudNoiseLod(float footprint, float scale)"));
        assertTrue(cloud.contains(
                "float sigmaT = density * layer.extinction;"));
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
