package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural regressions for the camera-medium prefix and its temporal diagnostics.
 *
 * <p>The JVM cannot execute the Slang ray-generation stages, so these assertions guard the ownership
 * seam in the real shader sources. Shader compilation remains the executable type/ABI check, and the
 * docs/devlog/M15-M17-medium-lighting.md records the underwater visual/probe protocol.
 */
final class RtMediumLifecycleRegressionTest {
    @Test
    void mediumClassificationUsesOneScalarFlagWordAcrossPathSegmentAbi() throws IOException {
        String medium = source("shaders/world/medium.slang");
        String segment = source("shaders/world/segment.slang");
        String indirect = source("shaders/world/world.rgen.slang");
        String volume = source("shaders/world/volume.slang");
        String lighting = source("shaders/world/lighting.slang");

        assertTrue(medium.contains("public uint flags;"));
        assertTrue(medium.contains("MEDIUM_FLAG_WATER"));
        assertTrue(medium.contains("MEDIUM_FLAG_AMBIENT"));
        assertTrue(medium.contains("public bool mediumIsWater(Medium m)"));
        assertTrue(medium.contains("public bool mediumIsAmbient(Medium m)"));
        assertFalse(medium.contains("public bool water;"));
        assertFalse(medium.contains("public bool ambient;"));
        assertTrue(segment.contains("mediumIsWater(seg.medium.current)"));
        assertTrue(segment.contains("mediumIsAmbient(seg.medium.current)"));
        assertTrue(segment.contains("current.flags ="));
        assertTrue(segment.contains("outer.flags ="));
        assertTrue(medium.contains("packActiveMediumFlags(uint currentFlags, uint outerFlags)"));
        assertTrue(medium.contains("activeCurrentMediumFlags(uint activeMediumFlags)"));
        assertTrue(medium.contains("activeOuterMediumFlags(uint activeMediumFlags)"));
        assertTrue(segment.contains("unpackActiveMediumFlags(uint pathFlags)"));
        assertTrue(indirect.contains("tracePath(__ref PathSegment seg, uint activeMediumFlags"));
        assertFalse(indirect.contains("MediumStack medium = seg.medium;"));
        assertFalse(indirect.contains("MediumStack medium;"));
        assertFalse(indirect.contains("Medium currentMedium;"));
        assertFalse(indirect.contains("Medium outerMedium;"));
        assertFalse(indirect.contains("Medium entered ="));
        assertTrue(indirect.contains("float currentIor = seg.medium.current.ior;"));
        assertTrue(indirect.contains("float3 currentExtinction = seg.medium.current.extinction;"));
        assertTrue(indirect.contains("float outerIor = seg.medium.outer.ior;"));
        assertTrue(indirect.contains("float3 outerExtinction = seg.medium.outer.extinction;"));
        assertFalse(indirect.contains("uint currentFlags ="));
        assertFalse(indirect.contains("uint outerFlags ="));
        assertFalse(indirect.contains("seg.medium.current.flags"));
        assertFalse(indirect.contains("seg.medium.outer.flags"));
        assertFalse(indirect.contains("seg.medium.current.flags ="));
        assertFalse(indirect.contains("seg.medium.outer.flags ="));
        assertTrue(indirect.contains(
                "currentExtinction, activeCurrentMediumFlags(activeMediumFlags),"
                        + " ro, rd, segmentEnd, seed"));
        assertTrue(indirect.contains(
                "tracePath(segment, unpackActiveMediumFlags(packed.pathFlags)"));
        assertTrue(volume.contains("integrateSegment(float3 currentExtinction, uint currentFlags"));
        assertFalse(volume.contains("integrateSegment(Medium"));
        assertFalse(volume.contains("integrateSegment(MediumStack"));
        assertTrue(volume.contains(
                "visibilityThroughAmbient(float3 mediumExtinction, uint mediumFlags"));
        assertTrue(lighting.contains(
                "shadeReservoir(float3 mediumExtinction, uint mediumFlags"));
        assertFalse(indirect.contains("seg.ro ="));
        assertFalse(indirect.contains("seg.rd ="));
        assertFalse(indirect.contains("seg.throughput ="));
        assertFalse(indirect.contains("seg.medium ="));
        assertFalse(indirect.contains("seg.rayConeWidth ="));
        assertFalse(indirect.contains("seg.rayConeSpread ="));
        assertFalse(indirect.contains("seg.seed ="));
        assertFalse(indirect.matches("(?s).*seg\\.bounce\\s*=(?!=).*"));
        assertFalse(indirect.contains("seg.showCelestial ="));
    }

    @Test
    void passBOwnsTheCompletePreInterfaceMediumIntegral() throws IOException {
        String primary = source("shaders/world/world_primary.rgen.slang");
        String indirect = source("shaders/world/world.rgen.slang");

        assertFalse(primary.contains("throughput *= exp(-medium.current.extinction * payload.hitT)"));
        assertFalse(indirect.contains("Medium prefixStart"));
        assertTrue(indirect.contains(
                "prefix = integrateSegment(waterExtinction(worldPush.waterParams.xyz), MEDIUM_FLAG_WATER"));
        assertTrue(indirect.contains("prefix.transmittance * (frameRadiance"));
        assertTrue(indirect.contains("DEBUG_VIEW_CAMERA_PREFIX = 20u"));
        assertTrue(indirect.contains("cameraPrefixIntegral("));
        assertTrue(indirect.contains("DEBUG_VIEW_COMPOSITE_PREFIX_AB = 21u"));
        assertTrue(indirect.contains("prefix.inScatter + leafRadiance"));
    }

    @Test
    void renderStateInvalidationRequestsAnRrHistoryReset() throws IOException {
        String lifecycle = source(
                "common/src/main/java/io/github/dswepm/fluorite/FluoriteLifecycle.java");
        String rr = source(
                "common/src/main/java/io/github/dswepm/fluorite/rt/pipeline/RtDlssRr.java");

        assertTrue(lifecycle.contains("RtDlssRr.INSTANCE.requestHistoryReset();"));
        assertTrue(rr.contains("public void requestHistoryReset()"));
    }

    @Test
    void volumeDebugUsesAStablePerPixelSeed() throws IOException {
        String indirect = source("shaders/world/world.rgen.slang");

        assertTrue(indirect.contains("volumeDebug(queue[pixelIndex], pixelIndex)"));
        assertTrue(indirect.contains(
                "uint debugSeed = pixelIndex ^ (uint(pc.debugView) * 2654435761u)"));
        assertFalse(indirect.contains(
                "uint debugSeed = uint(pc.debugView) * 2654435761u + worldPush.frameIndex"));
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
