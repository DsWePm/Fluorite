package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** D tail terminal events must retain endpoint MIS, coloured transmission and external roulette. */
final class RtPathDTerminalEventTest {
    private static double lum(double[] x) { return .2126*x[0]+.7152*x[1]+.0722*x[2]; }
    private static double[] mul(double[] a,double[] b) { return new double[]{a[0]*b[0],a[1]*b[1],a[2]*b[2]}; }
    private static double[] scale(double[] a,double s) { return new double[]{a[0]*s,a[1]*s,a[2]*s}; }
    private static void same(double[] a,double[] b) { for(int c=0;c<3;c++) assertEquals(a[c],b[c],2e-12*Math.max(1,Math.abs(a[c]))); }
    private static double[] marginal(double[][] f,double[] survival,int count) {
        double sum=0; for(int i=0;i<count;i++) sum+=lum(f[i])/survival[i];
        double[] result=new double[3];
        if(sum==0) return result;
        for(int i=0;i<count;i++) {
            double t=lum(f[i]); if(t==0) continue;
            double probability=(t/survival[i])/sum, weight=sum/t;
            for(int c=0;c<3;c++) result[c]+=probability*weight*f[i][c];
        }
        return result;
    }
    @Test void mixedSolarEmitterAndSkyEndpointsMarginaliseToTheirObservedSum() {
        double[] prefix={.2,0,.8}, transmission={.4,.7,.2}, scatter={.5,.3,.9};
        double[][] incident={mul(transmission,new double[]{2,3,1}),
                mul(mul(transmission,scatter),scale(new double[]{7,2,4},.35)),
                mul(mul(transmission,scatter),new double[]{.2,1,3})};
        double[][] f=new double[3][]; double[] expected=new double[3],q={.7,.21,.063};
        for(int i=0;i<3;i++) { f[i]=mul(prefix,incident[i]); for(int c=0;c<3;c++) expected[c]+=f[i][c]/q[i]; }
        same(expected,marginal(f,q,3));
        assertTrue(incident[1][1]>0 && incident[2][1]>0); // The zero source channel must survive in incident RGB.
    }
    @Test void solarAndEmissionAtOneVertexShareTheSameRouletteSurvival() {
        double[][] f={{.4,.2,.7},{.8,.3,.1}}; double[] survival={.14,.14};
        same(new double[]{1.2/.14,.5/.14,.8/.14},marginal(f,survival,2));
    }
    @Test void everyRouletteStopPreservesEarlierEndpointsAndRecoversTheUnroulettedIntegral() {
        double[][] f={{.5,2,1},{4,.2,3},{.1,5,2}}; double[] survival={.6,.18,.036};
        double[] expected={4.6,7.2,6}, result=new double[3];
        for(int reached=1;reached<=3;reached++) {
            double probability=survival[reached-1]*(reached==3?1:1-survival[reached]/survival[reached-1]);
            double[] conditional=marginal(f,survival,reached);
            for(int c=0;c<3;c++) result[c]+=probability*conditional[c];
        }
        same(expected,result);
    }
    @Test void endpointMisIsAlreadyInsideIncidentAndIsNotAppliedTwice() {
        double[] source={.6,.2,.8}, tail={.4,.7,.3}, radiance={12,4,2};
        double mis=.25,exit=.8,q=.3;
        double[] incident=scale(mul(tail,radiance),mis*exit);
        same(scale(mul(source,incident),1/q),marginal(new double[][]{mul(source,incident)},new double[]{q},1));
    }
    @Test void theActualProducerExcludesTheImmediateHHitAndKeepsThePublishedLightingLines() throws Exception {
        String trace=source("shaders/world/path_reuse_trace.slang");
        assertTrue(trace.contains("bool dTailIndirect = false;"));
        assertEquals(2,trace.split("if \\(dTailActive && dTailIndirect\\)",-1).length-1);
        int update=trace.indexOf("if (dTailActive) {\n            // Unsupported suffixes");
        int choose=trace.indexOf("dReconChosen = true;",update);
        int advance=trace.indexOf("dTailIndirect = true;",update);
        assertTrue(update>=0 && advance>update && advance<choose);
        assertTrue(trace.indexOf("dTailIndirect = false;",choose)>choose);
        for(String term:new String[]{"dTailWeight * sky;","REUSE_D_ENDPOINT_SKY, reuseSeed)",
                "dTailWeight * albedo * emission * worldPush.emitterTint.xyz * emitterShare",
                "REUSE_D_ENDPOINT_EMITTER, reuseSeed)","REUSE_D_ENDPOINT_SOLAR, reuseSeed)",
                "L += throughput * sky; // escaped to sky",
                "L += throughput * albedo * emission * worldPush.emitterTint.xyz * emitterShare"}) assertTrue(trace.contains(term),term);
        assertTrue(trace.contains("throughput * sky * rrSurvival"));
        assertTrue(trace.contains("* rainBaseExitTransmission(bc) * rrSurvival"));
    }
    @Test void endpointProvenanceIsLocalToTheTreeAndCannotGrowThePackedRecord() throws Exception {
        String tree=source("shaders/world/path_reuse_tree.slang");
        assertTrue(tree.contains("uint selectedEndpoint;"));
        assertTrue(tree.contains("tree.selectedEndpoint = endpoint;"));
        assertTrue(tree.contains("endpoint > REUSE_D_ENDPOINT_THIN"));
        String common=source("shaders/world/world_common.slang");
        String record=common.substring(common.indexOf("public struct PackedPathReservoir"),common.indexOf("/** Slot address:"));
        assertFalse(record.contains("selectedEndpoint"));
        assertTrue(common.contains("PATH_RESERVOIR_BYTES = 128u"));
    }
    @Test void newCountersDistinguishCandidatesFromActualWrittenRecords() throws Exception {
        assertEquals(84,RtPathReservoirStats.LANES); assertEquals(336,RtPathReservoirStats.BYTE_SIZE);
        String trace=source("shaders/world/path_reuse_trace.slang");
        for(int i:new int[]{44,45,46,47}) assertTrue(trace.contains("pathReservoirStatsAddr)["+i+"], 1u"));
        assertTrue(trace.contains("dTree.selectedEndpoint == REUSE_D_ENDPOINT_SKY"));
        assertTrue(trace.contains("dTree.selectedEndpoint == REUSE_D_ENDPOINT_EMITTER"));
        String stats=source("common/src/main/java/io/github/dswepm/fluorite/rt/RtPathReservoirStats.java");
        for(int i=44;i<48;i++) assertTrue(stats.contains("memGetInt(src.mapped + "+4*i+"L)"));
    }
    @Test void deeperAmbientAndThinTransmissionReuseTheExistingTracedTerms() throws Exception {
        String trace=source("shaders/world/path_reuse_trace.slang");
        for(String term:new String[]{"dTailWeight * diffAlb * rainBaseTransmission(bc, n)",
                "REUSE_D_ENDPOINT_AMBIENT, reuseSeed)",
                "dTailWeight * diffAlb * sss * sssStrength() * sssPhase(cosT) * backNdl",
                "REUSE_D_ENDPOINT_THIN, reuseSeed)"}) assertTrue(trace.contains(term),term);
        String tree=source("shaders/world/path_reuse_tree.slang");
        assertTrue(tree.contains("REUSE_D_ENDPOINT_AMBIENT = 3u"));
        assertTrue(tree.contains("REUSE_D_ENDPOINT_THIN = 4u"));
        assertTrue(trace.contains("throughput * diffAlb * rainBaseTransmission(bc, n)"));
        assertTrue(trace.contains("throughput * diffAlb * sss * sssStrength() * sssPhase(cosT) * backNdl"));
    }
    @Test void addedSurfaceEndpointsMarginaliseWithTheirOwnRouletteAndKeepDarkPrefixChannels() {
        double[] prefix={.3,0,.7},tail={.8,.4,.2},albedo={.2,.6,.9};
        double[] ambient=mul(mul(tail,albedo),new double[]{.4,.8,1.2});
        double[] thin=scale(mul(mul(tail,albedo),new double[]{4,2,1}),.35*.8*.27);
        double[][] f={mul(prefix,ambient),mul(prefix,thin)};double[] q={.21,.21};
        double[] expected=new double[3];for(int i=0;i<2;i++)for(int c=0;c<3;c++)expected[c]+=f[i][c]/q[i];
        same(expected,marginal(f,q,2));
        assertTrue(ambient[1]>0 && thin[1]>0);
        assertEquals(0,f[0][1]);assertEquals(0,f[1][1]);
    }
    private static String source(String relative) throws Exception {
        Path root=Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while(root!=null && !Files.isRegularFile(root.resolve("settings.gradle"))) root=root.getParent();
        assertNotNull(root); return Files.readString(root.resolve(relative)).replace("\r\n","\n");
    }
}
