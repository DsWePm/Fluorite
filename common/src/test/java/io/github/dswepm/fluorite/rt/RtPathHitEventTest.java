package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

final class RtPathHitEventTest {
    private static double power(double a,double b) {return a*a/(a*a+b*b+1e-20);}
    @Test void anAreaMeasureNumericalGuardCannotBeMovedToSolidAngleByDividingOnlyTheCompetingPdf() throws Exception {
        double pdf=1e-5,geometry=1e-10,competing=1e-18;
        double original=power(pdf*geometry,competing), converted=power(pdf,competing/geometry);
        assertTrue(original<1e-9);assertTrue(converted>.99);
        String e=source("shaders/world/path_reuse_event.slang");
        assertTrue(e.contains("powerHeuristic(evaluated.pdf * record.sourcePdf, record.neePdf)"));
        assertTrue(e.contains("record.sourcePdf = geometry;"));
        assertTrue(e.contains("reuseRecordSampling(record, previousLobe, 2u"));
    }
    @Test void immediateHitsKeepUnweightedEndpointRadianceAndSeparateTheSkyMisTerms() throws Exception {
        String s=source("shaders/world/path_reuse_trace.slang");
        assertTrue(s.contains("if (dTailActive && !dTailIndirect)"));
        assertTrue(s.contains("float3 plainSky = sky;"));
        assertTrue(s.contains("incident = dTailWeight * analyticEndpoint;"));
        assertTrue(s.contains("reuseHitAreaRecord(dRecord, competingPdf, endpointGeometry)"));
        assertTrue(s.contains("dSourceWeight * emitterShare, incident, rrSurvival"));
        assertTrue(s.contains("dSourceScatterWeight * emitterShare"));
        assertFalse(s.contains("emitterAreaDensity(albedo * emission * worldPush.emitterTint.xyz) /"));
        String e=source("shaders/world/path_reuse_event.slang");
        assertTrue(e.contains("reuseLocalHitMis(evaluated.pdf, record.neePdf)"));
    }
    @Test void localEmissionRebuildsTheActualMaterialAndIncomingHitPartition() throws Exception {
        String s=source("shaders/world/path_reuse_trace.slang"),e=source("shaders/world/path_reuse_event.slang");
        assertTrue(s.contains("float emitterShare = reuseLocalEmitterShare(risOn, showCelestial, bsdfPdf,"));
        assertTrue(s.contains("state.previousCelestial = showCelestial;"));
        assertTrue(s.contains("replay.previousPdf, replay.previousCelestial"));
        assertTrue(s.contains("REUSE_LOCAL_EMISSION, float3(0.0, 0.0, 0.0), 1.0"));
        assertTrue(e.contains("reuseLocalEmitterShare(risOn, previousCelestial, previousPdf, vertex.n,"));
        assertTrue(e.contains("vertex.bc.hitPos, rayOrigin, incomingDirection, vertex.albedo, emission"));
        assertTrue(e.contains("vertex.albedo * emission * share * rainBaseExitTransmission(vertex.bc)"));
    }
    private static String source(String relative) throws Exception {
        Path p=Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while(p!=null && !Files.isRegularFile(p.resolve("settings.gradle")))p=p.getParent();
        assertNotNull(p);return Files.readString(p.resolve(relative)).replace("\r\n","\n");
    }
}
