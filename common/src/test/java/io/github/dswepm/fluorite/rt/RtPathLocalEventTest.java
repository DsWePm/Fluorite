package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** A local NEE event has a different view-dependent response from a fixed D incident. */
final class RtPathLocalEventTest {
    private static String source(String file) throws Exception {
        Path p=Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while(p!=null && !Files.isRegularFile(p.resolve("settings.gradle")))p=p.getParent();
        assertNotNull(p);assertTrue(Files.isRegularFile(p.resolve(file)),file);
        return Files.readString(p.resolve(file)).replace("\r\n","\n");
    }
    @Test void sourceSunAndReevaluationUseOneShippedBrdfBody() throws Exception {
        String trace=source("shaders/world/path_reuse_trace.slang");
        assertTrue(trace.contains("#include \"path_reuse_local.slang\""));
        assertTrue(trace.contains("reuseLocalSunBrdf(bc, ext, pf, ps, walkSssShare,"));
        String local=source("shaders/world/path_reuse_local.slang");
        for(String term:new String[]{"float3 baseBrdf = diffAlb * INV_PI + (D * G) * F / (4.0 * ndv * ndl);",
                "float pdfB = bsdfContinuationPdf(bc, lightDir, pf, ps, walkSssShare);",
                "float wLight = misActive ? powerHeuristic(pdfLight, pdfB) : 1.0;",
                "sampled * wLight", "disneyLobes(bc, ext, lightDir)"}) assertTrue(local.contains(term),term);
        assertFalse(local.contains("TraceRay") || local.contains("rndf(") || local.contains("worldPush."));
        assertTrue(trace.contains("L += throughput * brdf * sampledLightIrradiance * ndl * vis;"));
    }
    @Test void onlyTheSampledPartReceivesTheCompetingTechniqueMisWeight() {
        double sampled=.8,unsampledDisney=.3,qLight=12,qBsdf=3;
        double a=qLight*qLight,b=qBsdf*qBsdf;
        double nee=sampled*a/(a+b)+unsampledDisney;
        double hit=sampled*b/(a+b);
        assertEquals(sampled+unsampledDisney,nee+hit,1e-15);
        assertTrue(Math.abs((sampled+unsampledDisney)*a/(a+b)+hit-(sampled+unsampledDisney))>.01);
    }
    @Test void localAmbientAndThinWeightsRemainSeparateFromStoredIncidentRadiance() throws Exception {
        String s=source("shaders/world/path_reuse_local.slang");
        assertTrue(s.contains("float3 reuseLocalAmbientWeight(BsdfContext bc)"));
        assertTrue(s.contains("bc.diffAlb * rainBaseTransmission(bc, bc.n)"));
        assertTrue(s.contains("float3 reuseLocalThinWeight(BsdfContext bc, float3 lightDir, float strength, float phase)"));
        assertTrue(s.contains("bc.diffAlb * bc.sss * strength * phase * backNdl * rainBaseExitTransmission(bc)"));
    }
}
