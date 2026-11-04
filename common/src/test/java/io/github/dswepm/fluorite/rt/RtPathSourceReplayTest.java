package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** Fresh material/scattering replay is distinct from transport replay and a complete hybrid shift. */
final class RtPathSourceReplayTest {
    private static double[] mul(double[] a,double[] b) { return new double[]{a[0]*b[0],a[1]*b[1],a[2]*b[2]}; }
    @Test void scatteringLedgerStaysDefinedWhenTransportOrAColourChannelIsZero() {
        double[] initial={.4,0,.8}, first={.3,.7,.1}, second={.8,.2,.4}, transmission={0,.4,.9};
        double[] scatter=mul(mul(initial,first),second), actual=mul(scatter,transmission);
        assertTrue(scatter[0]>0); assertEquals(0,actual[0]); assertEquals(0,scatter[1]);
        assertEquals(.4*.3*.8,scatter[0],1e-15);
        // No RGB division can recover this scattering ledger from the actual transported value.
    }
    @Test void sourceDenominatorUsesTheReplayedPredecessorAndDestinationGeometricNormal() {
        double previousPdf=.07, currentPdf=.4, cosine=.3, distance=2;
        double jac=(previousPdf*(cosine/(distance*distance)))*currentPdf;
        assertEquals(.0021,jac,1e-15);
        assertNotEquals(jac,(previousPdf*(.8/(distance*distance)))*currentPdf);
    }
    @Test void freshSourceEvaluationConsumesRecordSeedLobeDirectionAndFreshPayload() throws Exception {
        String s=source("shaders/world/path_reuse_trace.slang");
        int start=s.indexOf("void reuseDiagnoseSourceScatter("), end=s.indexOf("\n}\n",start);
        assertTrue(start>=0 && end>start);
        String function=s.substring(start,end);
        for(String term:new String[]{"record.pathSeed, depth", "reuseOpaqueVertex(",
                "payload.normal, replay.incomingDirection, replay.rayConeWidth", "reuseLoadDirection(record)",
                "replay.scatterPrefix * evaluated.weight", "replay.previousPdf * reuseRayGeometry(",
                "replay.hitDistance, geometry.normal)", "record.jacDenom"}) assertTrue(function.contains(term),term);
        assertFalse(function.contains("BsdfContext original") || function.contains("scatter = referenceScatter"));
    }
    @Test void theReplayStateExportsScatteringButDoesNotInventUnapprovedTransportSeeds() throws Exception {
        String s=source("shaders/world/path_reuse_trace.slang");
        int start=s.indexOf("int reuseReplayPrefix("), end=s.indexOf("\n}\n",start);
        String function=s.substring(start,end);
        assertTrue(function.contains("state.scatterPrefix = throughput;"));
        assertTrue(function.contains("state.previousPdf = bsdfPdf;"));
        assertTrue(function.contains("state.opaquePrefix = false;"));
        assertFalse(function.contains("integrateSegment(") || function.contains("traceClouds(") || function.contains("rrSurvival"));
    }
    @Test void originalScatteringGroundTruthIsAccumulatedWithoutDividingActualThroughput() throws Exception {
        String s=source("shaders/world/path_reuse_trace.slang");
        for(String term:new String[]{"scatteringPrefix = identityCheck ? seg.throughput", "scatteringPrefix *= continuation.weight;",
                "dSourceScatterWeight = scatteringPrefix;", "dSourceSamplePdf = continuation.pdf;",
                "dSourceScatterSupported = scatteringPrefixSupported;", "sampleInfo.weight = fresnelSchlick(clamp(dot(n, v)"})
            assertTrue(s.contains(term),term);
        assertFalse(s.contains("scatteringPrefix /=") || s.contains("scatteringPrefix *= segIntegral") || s.contains("scatteringPrefix *= clouds"));
    }
    @Test void diagnosticsPartitionEveryAttemptAndKeepDepthBinsAndOriginalTolerance() throws Exception {
        assertEquals(64,RtPathReservoirStats.LANES); assertEquals(256,RtPathReservoirStats.BYTE_SIZE);
        String s=source("shaders/world/path_reuse_trace.slang");
        assertTrue(s.contains("depth == 2 ? 48 : 49")); assertTrue(s.contains("depth == 2 ? 54 : 55"));
        for(int i:new int[]{50,51,52,53,59}) assertTrue(s.contains("pathReservoirStatsAddr)["+i+"], 1u"));
        for(int i:new int[]{56,57,58}) assertTrue(s.contains("pathReservoirStatsAddr)["+i+"], asuint("));
        assertTrue(s.contains("scatterError <= 0.001 && pdfError <= 0.001 && jacError <= 0.001"));
        String stats=source("common/src/main/java/io/github/dswepm/fluorite/rt/RtPathReservoirStats.java");
        for(int i=48;i<60;i++) assertTrue(stats.contains("memGetInt(src.mapped + "+4*i+"L)"));
    }
    @Test void theSelectedIncidentCanExposeAnOtherwiseInvisibleWeakPrefixChannel() throws Exception {
        double[] a={1,1e-10,0}, b={1,2e-10,0}, incident={0,3,0};
        assertTrue(Math.abs(a[1]-b[1])/a[0]<.001);
        double[] x=mul(a,incident),y=mul(b,incident);
        assertEquals(.5,Math.abs(x[1]-y[1])/y[1],1e-15);
        String s=source("shaders/world/path_reuse_trace.slang");
        assertTrue(s.contains("referenceEvent = referenceScatter * incident;"));
        assertTrue(s.contains("replayedEvent = scatter * incident;"));
        assertTrue(s.contains("reuseRelativeRgbError(referenceEvent, replayedEvent)"));
    }
    private static String source(String relative) throws Exception {
        Path p=Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while(p!=null && !Files.isRegularFile(p.resolve("settings.gradle"))) p=p.getParent();
        assertNotNull(p); return Files.readString(p.resolve(relative)).replace("\r\n","\n");
    }
}
