package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SplittableRandom;
import static org.junit.jupiter.api.Assertions.*;

final class RtPathConnectionTest {
    @Test void connectionJacobianCancelsBothDirectionalPdfsInTheTransportedWeight() {
        var random = new SplittableRandom(25);
        for (int i = 0; i < 10_000; i++) {
            double px = .01 + random.nextDouble(), qx = .01 + random.nextDouble();
            double py = .01 + random.nextDouble(), qy = .01 + random.nextDouble();
            double gx = .01 + random.nextDouble(), gy = .01 + random.nextDouble();
            double f1 = random.nextDouble(), f2 = random.nextDouble(), incident = random.nextDouble();
            double forward = py * gy * qy / (px * gx * qx);
            double reverse = px * gx * qx / (py * gy * qy);
            assertEquals(1, forward * reverse, 1e-12);
            double value = f1 / py * f2 / qy * incident;
            assertEquals(f1 * f2 * incident * gy / (px * gx * qx), value * forward, 1e-11);
        }
    }

    @Test void theConnectionUsesFreshNearestHitMaterialAndTransportRatherThanTheSourceReferences() throws Exception {
        String s = source("shaders/world/path_reuse_connection.slang");
        String evaluate = s.substring(0, s.indexOf("void reuseDiagnoseK2Connection("));
        assertEquals(1, evaluate.split("tracePathEndpointRadiance\\(", -1).length - 1);
        for (String term : new String[]{"reuseConnectionDirection(source.vertex.p, reuseLoadIncomingDirection(record), receiver.p", "connection.pdf, showCelestial",
                "payloadMaterial() != MATERIAL_OPAQUE", "reuseReadHitGeometry(hitPosition, pathHitGeometry, recon)",
                "reuseEvaluateEvent(fresh, record", "reuseEventFootprintEligible(",
                "result.denominator / record.jacDenom", "reuseAccumulateTransport(transport, seg.medium.current.extinction",
                "record.pathSeed, 1u, true", "(transport * scatter) * reuseLoadIncident(record)"})
            assertTrue(evaluate.contains(term), term);
        assertFalse(evaluate.contains("reference") || evaluate.contains("RayQuery") || evaluate.contains("traceShadow"));
        assertFalse(evaluate.contains("integrateSegment(") || evaluate.contains("traceClouds("));
        String trace = source("shaders/world/path_reuse_trace.slang");
        assertTrue(trace.contains("reuseDiagnoseSourceScatter(seg, entryMediumFlags, produced,"));
        assertTrue(trace.contains("reuseDiagnoseK2Connection(seg, entryMediumFlags, produced, dTree.selectedValue);"));
        assertTrue(trace.contains("return L;"));
    }

    @Test void everyAttemptIsCountedAndNumericalThresholdsStayIndependentOfEligibility() throws Exception {
        String s = source("shaders/world/path_reuse_connection.slang");
        assertTrue(s.contains("[77u + evaluated.status], 1u"));
        assertTrue(s.contains("valueError <= 0.001 && jacError <= 0.001"));
        int evaluatorEnd = s.indexOf("void reuseDiagnoseK2Connection(");
        assertFalse(s.substring(0, evaluatorEnd).contains("0.001"));
        String stats = source("common/src/main/java/io/github/dswepm/fluorite/rt/RtPathReservoirStats.java");
        assertTrue(stats.contains("|| connectionReadbackFailed(connectionAttempt, connectionPass, connectionScope, connectionHit,"));
        assertTrue(stats.contains("allFrames.maximum(82)"));
        assertTrue(stats.contains("allFrames.maximum(83)"));
    }

    @Test void rejectedMappingsAreCountedButNumericFailuresAndLostOutcomesStillFail() {
        assertFalse(RtPathReservoirStats.connectionReadbackFailed(100,90,0,10,0,0));
        assertFalse(RtPathReservoirStats.connectionReadbackFailed(100,85,3,10,2,0));
        assertFalse(RtPathReservoirStats.connectionReadbackFailed(0,0,0,0,0,0));
        assertTrue(RtPathReservoirStats.connectionReadbackFailed(100,89,0,10,0,1));
        assertTrue(RtPathReservoirStats.connectionReadbackFailed(100,90,0,9,0,0));
        assertTrue(RtPathReservoirStats.connectionReadbackFailed(100,91,0,10,0,0));
    }

    private static String source(String relative) throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("settings.gradle"))) root = root.getParent();
        assertNotNull(root);
        return Files.readString(root.resolve(relative)).replace("\r\n", "\n");
    }
}
