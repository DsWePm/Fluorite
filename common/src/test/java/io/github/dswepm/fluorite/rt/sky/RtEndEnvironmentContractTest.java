package io.github.dswepm.fluorite.rt.sky;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Structural guardrails for the approved D91 direct-view and D93A-R dynamic environment contracts. */
final class RtEndEnvironmentContractTest {
    @Test
    void directViewSharpensOnlyTheCameraEnvironmentMiss() throws IOException {
        String common = shader("world_common.slang");
        String trace = shader("trace.slang");
        String traceSer = shader("trace_ser.slang");
        String primary = shader("world_primary.rgen.slang");
        String indirect = shader("world.rgen.slang");
        String miss = shader("world.rmiss.slang");

        assertTrue(common.contains("PAYLOAD_DIRECT_VIEW = 8u"));
        assertTrue(trace.contains("directView ? PAYLOAD_DIRECT_VIEW : 0u"));
        assertTrue(traceSer.contains("directView ? PAYLOAD_DIRECT_VIEW : 0u"));
        assertTrue(primary.contains("showCelestial, bounce == 0, rayConeWidth"));
        assertTrue(indirect.contains("showCelestial, bounce == 0,"));
        assertTrue(indirect.contains("seg.showCelestial, false,"));
        assertTrue(miss.contains("(payload.flags & PAYLOAD_DIRECT_VIEW) != 0u"));
        assertTrue(miss.contains("lod = max(lod - 1.0, 0.0)"));
    }

    @Test
    void environmentBranchReturnsBeforeAtmosphereProceduralStars() throws IOException {
        String miss = shader("world.rmiss.slang");
        int environment = miss.indexOf("skyProvider == SKY_PROVIDER_ENVIRONMENT");
        int environmentReturn = miss.indexOf("return;", environment);
        int proceduralStars = miss.indexOf("col += stars(");
        assertTrue(environment >= 0 && environmentReturn > environment);
        assertTrue(proceduralStars > environmentReturn);
    }

    @Test
    void dynamicDiskAndRotatingHdriShareTheEnvironmentProvider() throws IOException {
        String environment = shader("environment.slang");
        assertTrue(environment.contains("ENV_DISK_STEPS = 12u"));
        assertTrue(environment.contains("environmentDiskEntry"));
        assertTrue(environment.contains("environmentDiskExit"));
        assertTrue(environment.contains("result.transmittance *= segmentT"));
        assertTrue(environment.contains("environmentDiskRadiance(push, sampledDirection"));
        assertTrue(environment.contains("ENV_SPIN_AXIS, push.celestial.w"));
        assertTrue(environment.contains("push.lightRadiance.w"));
        assertTrue(environment.contains("push.moonDir.w"));
    }

    private static String shader(String name) throws IOException {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("settings.gradle"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IOException("Could not locate repository root from " + System.getProperty("user.dir"));
        }
        return Files.readString(root.resolve("shaders/world/" + name));
    }
}
