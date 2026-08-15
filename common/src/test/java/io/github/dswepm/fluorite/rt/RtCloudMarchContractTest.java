package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Structural contract for the D160 experiment's rollback and its retained last-step correction. */
final class RtCloudMarchContractTest {
    @Test
    void escapedSkySegmentsKeepTheEstablishedBudgetAndClipTheirLastStep() throws IOException {
        String raygen = source("shaders/world/world.rgen.slang");
        String cloud = source("shaders/world/cloud.slang");

        assertTrue(raygen.contains(
                "float cloudMaxT = payload.hitT < 0.0 ? RAY_FAR : payload.hitT;"));
        assertTrue(cloud.contains(
                "float step = min(max(24.0, t * 0.125), tExit - t);"));
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
