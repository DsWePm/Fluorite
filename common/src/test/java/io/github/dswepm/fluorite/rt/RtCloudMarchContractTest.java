package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Structural and numerical regressions for the cloud march's finite-distance boundary (D160A). */
final class RtCloudMarchContractTest {
    @Test
    void escapedSkySegmentsUseTheCloudDistanceBudgetAndClipTheirLastStep() throws IOException {
        String raygen = source("shaders/world/world.rgen.slang");
        String cloud = source("shaders/world/cloud.slang");

        assertTrue(raygen.contains(
                "float cloudMaxT = payload.hitT < 0.0 ? CLOUD_MAX_DIST : payload.hitT;"));
        assertFalse(raygen.contains(
                "float cloudMaxT = payload.hitT < 0.0 ? RAY_FAR : payload.hitT;"));
        assertTrue(cloud.contains(
                "float step = min(max(24.0, t * 0.125), tExit - t);"));
        assertTrue(cloud.contains("public static const float CLOUD_FADE_START = 32000.0;"));
        assertTrue(cloud.contains(
                "return 1.0 - smoothstep(CLOUD_FADE_START, CLOUD_MAX_DIST, distance);"));
        assertTrue(cloud.contains(
                "float sigmaT = density * layer.extinction * distanceWeight;"));
    }

    @Test
    void cloudBudgetMovesTheHighCirrusCutoffOutOfTheVisibleSky() {
        double cameraAltitude = 64.0;
        double cirrusAltitude = 1800.0;

        double oldElevation = cutoffElevationDegrees(cirrusAltitude - cameraAltitude, 10_000.0);
        double newElevation = cutoffElevationDegrees(cirrusAltitude - cameraAltitude, 40_000.0);

        assertTrue(oldElevation > 9.9 && oldElevation < 10.1,
                "the reproduced D160 ring must remain the one observed in game");
        assertTrue(newElevation > 2.4 && newElevation < 2.6,
                "the cloud-only budget must move the cutoff into the horizon band");
    }

    private static double cutoffElevationDegrees(double heightAboveCamera, double distance) {
        return Math.toDegrees(Math.asin(heightAboveCamera / distance));
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
