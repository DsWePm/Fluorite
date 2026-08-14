package io.github.dswepm.fluorite.rt;

import io.github.dswepm.fluorite.rt.gen.WorldPushData;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contracts for D161A's independently authored cloud shape evolution. */
final class RtCloudShapeContractTest {
    @Test
    void cloudEvolutionHasItsOwnAbiLaneAndDoesNotBorrowWaterTime() throws IOException {
        var fields = Arrays.stream(WorldPushData.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();
        String common = source("shaders/world/world_common.slang");
        String cloud = source("shaders/world/cloud.slang");
        String composite = source(
                "common/src/main/java/io/github/dswepm/fluorite/rt/RtComposite.java");

        assertTrue(fields.contains("cloudEvolution"), fields.toString());
        assertTrue(common.contains("public float4   cloudEvolution;"));
        assertTrue(cloud.contains("l.detailOffset = worldPush.cloudEvolution.xy;"));
        assertTrue(cloud.contains("l.detailOffset = worldPush.cloudEvolution.zw;"));
        assertTrue(composite.contains("cloudEvolution(environment)"));
    }

    @Test
    void convectiveCloudsKeepTheirTypeProfileWhileAddingDifferentialPuffs() throws IOException {
        String cloud = source("shaders/world/cloud.slang");

        assertTrue(cloud.contains("float lowCloudPuffWeight(float type)"));
        assertTrue(cloud.contains("float evolvingCloudShape(CloudLayer layer, float3 pw, float type)"));
        assertTrue(cloud.contains("float profile = cloudHeightProfile(layer, pw.y, type);"));
        assertTrue(cloud.contains("float3 detailPw = pw + float3(layer.detailOffset.x, 0.0, layer.detailOffset.y);"));
        assertTrue(cloud.contains("float erosion = cloudNoise.SampleLevel(detailPw / layer.detailScale, 0.0).g;"));
    }

    @Test
    void highCloudMorphologyIsAnAuthoredUnitControlWithTheApprovedDefault() throws IOException {
        String config = source(
                "common/src/main/java/io/github/dswepm/fluorite/FluoriteConfig.java");
        String options = source(
                "common/src/main/java/io/github/dswepm/fluorite/client/RtVideoOptions.java");
        String composite = source(
                "common/src/main/java/io/github/dswepm/fluorite/rt/RtComposite.java");
        String english = source("common/src/main/resources/assets/fluorite/lang/en_us.json");
        String chinese = source("common/src/main/resources/assets/fluorite/lang/zh_cn.json");

        assertTrue(config.contains("CLOUD_CIRRUS_MORPHOLOGY"));
        assertTrue(config.contains("\"volumetrics.cloud-cirrus-morphology\", 0.35f, 0f, 1f"));
        assertTrue(options.contains("unitSlider(\"fluorite.options.rt.cloudCirrusMorphology\""));
        assertTrue(composite.contains("CLOUD_CIRRUS_MORPHOLOGY.value()"));
        assertTrue(english.contains("\"fluorite.options.rt.cloudCirrusMorphology\""));
        assertTrue(chinese.contains("\"fluorite.options.rt.cloudCirrusMorphology\""));
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
