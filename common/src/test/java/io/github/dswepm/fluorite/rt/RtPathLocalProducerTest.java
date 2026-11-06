package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

final class RtPathLocalProducerTest {
    @Test void localEventsUseTheirOwnDensityWithoutInventingASampledContinuationLobe() throws Exception {
        String s=source("shaders/world/path_reuse_sampling.slang");
        assertTrue(s.contains("reuseEventFootprintEligible(ReuseContinuationSample previous, float currentPdf, bool viewIndependent"));
        assertTrue(s.contains("!reuseAnalyticContinuation(previous)"));
        assertTrue(s.contains("previous.alpha < alphaMin"));
        assertTrue(s.contains("float sourceDenominator = forwardDensity * currentPdf;"));
        assertTrue(s.contains("if (!viewIndependent)"));
        String event=source("shaders/world/path_reuse_event.slang");
        assertTrue(event.contains("reuseEventFootprintEligible(previousSample, 1.0, true"));
        assertTrue(event.contains("if (lobe != REUSE_LOBE_NONE) return event;"));
        assertTrue(event.contains("record.jacDenom *= pdf;"));
    }
    @Test void theLocalTreeExcludesPrimaryDiAndSurvivesTheTerminalBounceCap() throws Exception {
        String s=source("shaders/world/path_reuse_trace.slang");
        int start=s.indexOf("PackedPathReservoir localRecord =");
        int cap=s.indexOf("if (bounce >= maxBounces)",start);
        assertTrue(start>0 && cap>start);
        String local=s.substring(start,cap);
        assertTrue(local.contains("recordPath && !dReconChosen && hitDepth >= 1 && footprintPrimaryValid"));
        assertTrue(local.contains("footprintPreviousValid && !footprintPrefixUnsupported"));
        assertTrue(local.contains("REUSE_TECHNIQUE_S"));assertTrue(local.contains("REUSE_TECHNIQUE_T"));
        assertTrue(local.contains("sampledLightIrradiance * vis, rrSurvival"));
        assertTrue(local.contains("sampledLightIrradiance * visB, rrSurvival"));
        assertTrue(local.contains("worldPush.mediumSkyRadiance.xyz, rrSurvival"));
        assertFalse(local.contains("throughput /= response"));
    }
    @Test void freshReplayUsesTheSelectedEventsReferenceAndActualDecodedMaterial() throws Exception {
        String s=source("shaders/world/path_reuse_trace.slang");
        assertTrue(s.contains("selectedReference.scatterWeight, selectedReference.sourcePdf, selectedReference.supported"));
        assertTrue(s.contains("reuseSelectReference(dTree, selectedReference, scatteringPrefix * response"));
        assertTrue(s.contains("reuseEvaluateEvent(fresh, record, replay.rayOrigin"));
        String e=source("shaders/world/path_reuse_event.slang");
        for(String term:new String[]{"reuseLocalSunBrdf(vertex.bc", "reuseLocalAmbientWeight(vertex.bc)",
                "reuseLocalThinWeight(vertex.bc", "reference.scatterWeight = scatterWeight;", "if (tree.lastSelected)"})
            assertTrue(e.contains(term),term);
    }
    private static String source(String relative) throws Exception {
        Path p=Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while(p!=null && !Files.isRegularFile(p.resolve("settings.gradle")))p=p.getParent();
        assertNotNull(p);return Files.readString(p.resolve(relative)).replace("\r\n","\n");
    }
}
