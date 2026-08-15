package io.github.dswepm.fluorite.rt;

import io.github.dswepm.fluorite.rt.gen.WorldPushData;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contracts for D161's low-cloud evolution and D163's replacement high-cloud representation. */
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
        assertTrue(composite.contains("cloudEvolution(environment)"));
        // z carried the retired upper sheet's gain. The LANE stays -- removing it would move every field
        // after it in a push buffer whose Java record is positional -- but nothing may read it again, or
        // it becomes a lane that is zero on the CPU and meaningful in the shader.
        assertFalse(cloud.contains("worldPush.cloudEvolution.z"));
    }

    @Test
    void convectiveCloudsKeepTheirTypeProfileWhileAddingDifferentialPuffs() throws IOException {
        String cloud = source("shaders/world/cloud.slang");

        assertTrue(cloud.contains("float lowCloudPuffWeight(float type)"));
        // The footprint rides along because the mip is chosen from the march step -- see cloudNoiseLod.
        assertTrue(cloud.contains(
                "float evolvingCloudShape(CloudLayer layer, float3 pw, float type, float footprint)"));
        assertTrue(cloud.contains("float profile = cloudHeightProfile(layer, pw.y, type);"));
        assertTrue(cloud.contains("float3 detailPw = pw + float3(layer.detailOffset.x, 0.0, layer.detailOffset.y);"));
        // Same fetch, now prefiltered to the march step rather than always reading level 0.
        assertTrue(cloud.contains("float erosion = cloudNoise.SampleLevel(detailPw / layer.detailScale,"));
        assertTrue(cloud.contains("cloudNoiseLod(footprint, layer.detailScale)).g;"));
    }

    @Test
    void highCloudsUseExplicitPatchAndUpperFieldControls() throws IOException {
        String config = source(
                "common/src/main/java/io/github/dswepm/fluorite/FluoriteConfig.java");
        String options = source(
                "common/src/main/java/io/github/dswepm/fluorite/client/RtVideoOptions.java");
        String composite = source(
                "common/src/main/java/io/github/dswepm/fluorite/rt/RtComposite.java");
        String pipeline = source(
                "common/src/main/java/io/github/dswepm/fluorite/rt/pipeline/RtPipeline.java");
        String cloud = source("shaders/world/cloud.slang");
        String english = source("common/src/main/resources/assets/fluorite/lang/en_us.json");
        String chinese = source("common/src/main/resources/assets/fluorite/lang/zh_cn.json");

        assertTrue(config.contains("CLOUD_CIRRUS_PATCH_DIAMETER"));
        assertTrue(config.contains("\"volumetrics.cloud-cirrus-patch-spacing\", 16000f"));
        assertTrue(options.contains("cloudCirrusPatchStrength()"));
        assertTrue(composite.contains("CLOUD_CIRRUS_PATCH_STRENGTH.value()"));
        assertTrue(english.contains("\"fluorite.options.rt.cloudCirrusPatchDiameter\""));
        assertTrue(chinese.contains("\"fluorite.options.rt.cloudCirrusPatchDiameter\""));
        assertFalse(config.contains("CLOUD_CIRRUS_MORPHOLOGY"));
        assertFalse(cloud.contains("layer.cirrus"));
        assertTrue(cloud.contains("[[vk::binding(26, 0)]] Sampler2DArray highCloudPatchTau"));
        assertTrue(pipeline.contains(
                "int highCloudPatchBinding = skyAtlas ? rainWetHistoryBinding + rainWetHistorySamplers : -1;"));
        assertTrue(cloud.contains("CloudResult highResult = traceHighClouds(ro, rd, tMax);"));
        assertFalse(cloud.contains("marchLayer(high"));

        // THE UPPER SHEET IS GONE, and this is where that stays true. It sampled one periodic HDRI, so
        // it covered everything everywhere and filled every gap the patches left -- the one property a
        // cirrus sky must not have. Asserted as absence across all five layers it used to touch, because
        // a half-removal (binding still declared, texture no longer bound) is a device fault rather than
        // a visible mistake, and the retired config keys are what a stale user TOML would resurrect.
        assertFalse(cloud.contains("highCloudFurryTau"));
        assertFalse(cloud.contains("highCloudFurryShape"));
        assertFalse(pipeline.contains("highCloudFurryBinding"));
        assertFalse(composite.contains("CLOUD_CIRRUS_FURRY_STRENGTH"));
        assertFalse(options.contains("cloudCirrusFurryStrength"));
        assertFalse(english.contains("cloudCirrusFurry"));
        assertTrue(config.contains("FILE.remove(\"volumetrics.cloud-cirrus-furry-span\")"));
        assertTrue(config.contains("FILE.remove(\"volumetrics.cloud-cirrus-furry-strength\")"));
    }

    @Test
    void lowCloudShapeIsDisplacedRatherThanOnlyEroded() throws IOException {
        String cloud = source("shaders/world/cloud.slang");
        String config = source(
                "common/src/main/java/io/github/dswepm/fluorite/FluoriteConfig.java");

        // The warp displaces the SHAPE lookup only. Coverage and the height profile decide where a cloud
        // system stands and how tall it is; displacing those would move the weather rather than the cloud.
        assertTrue(cloud.contains(
                "float shape = evolvingCloudShape(layer, cloudWarpPosition(layer, pw), type, footprint);"));
        assertTrue(cloud.contains("float3 cloudWarpPosition(CloudLayer layer, float3 pw)"));
        assertTrue(cloud.contains("[[vk::binding(28, 0)]] Sampler3D cloudWarp;"));
        // Its own advection is what makes cloud grow instead of pass by unchanged. Sharing the shape
        // field's drift would deform each cloud once and then translate it rigidly.
        assertTrue(cloud.contains("float3(worldPush.cloudWarp.z, 0.0, worldPush.cloudWarp.w)"));
        // Zero amplitude must skip the fetch and leave the field bit-for-bit as it was.
        assertTrue(cloud.contains("if (amplitude <= 0.0)"));
        assertTrue(config.contains("\"volumetrics.cloud-warp-amount\""));
        assertTrue(config.contains("\"volumetrics.cloud-warp-scale\""));
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
