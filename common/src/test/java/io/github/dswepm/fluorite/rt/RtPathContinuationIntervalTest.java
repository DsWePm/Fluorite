package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

final class RtPathContinuationIntervalTest {
    @Test void theCapturedChoiceStraddlesAdjacentProbabilityThresholds() {
        int state=1241193306;
        state=state*747796405+ (int)2891336453L;
        int word=((state>>>((state>>>28)+4))^state)*(int)277803737L;
        int result=(word>>>22)^word;
        assertEquals(964557449,result);
        float choice=(result>>>8)*(1.0f/16777216.0f);
        float probability=0.22457851469516754f;
        assertTrue(choice<probability);
        float adjacentThreshold=Math.nextDown(probability);
        assertEquals(Float.floatToRawIntBits(choice),Float.floatToRawIntBits(adjacentThreshold));
        assertFalse(choice<adjacentThreshold,
                "The source threshold equalled this draw; the replay threshold was one ulp higher.");
    }

    @Test void oneSharedCdfOwnsTheDecisionWithoutDrawingAnotherRandomNumber() throws Exception {
        String direction=source("shaders/world/path_reuse_direction.slang");
        int start=direction.indexOf("public uint reuseContinuationInterval("),end=direction.indexOf("public float3 reuseReflect(",start);
        String function=direction.substring(start,end);
        assertTrue(function.contains("replayAdd(pf, replayMul(baseWidth, ps))"));
        assertTrue(function.contains("sampleBits < (asuint(specularEnd) & 0x7fffffffu)"));
        assertFalse(function.contains("rndf(") || function.contains("pcg("));
        String trace=source("shaders/world/path_reuse_trace.slang");
        assertTrue(trace.contains("float lobeChoice = rndf(stream);\n    uint lobeInterval = reuseContinuationInterval(lobeChoice, pf, ps);"));
        assertEquals(1,trace.split("reuseContinuationInterval\\(",-1).length-1);
    }

    private static String source(String relative) throws Exception {
        Path root=Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while(root!=null && !Files.isRegularFile(root.resolve("settings.gradle")))root=root.getParent();
        assertNotNull(root);
        return Files.readString(root.resolve(relative)).replace("\r\n","\n");
    }
}
