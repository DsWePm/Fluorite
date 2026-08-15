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
        String density = source("shaders/world/cloud_density.slang");
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
        String density = source("shaders/world/cloud_density.slang");

        assertTrue(density.contains("public float lowCloudPuffWeight(float type)"));
        // The footprint rides along because the mip is chosen from the march step -- see cloudNoiseLod.
        assertTrue(density.contains(
                "float evolvingCloudShape(CloudTextures tex, CloudLayer layer, float3 pw, float type,"
                        + " float footprint)"));
        // The authored thickness is a CEILING now: each cloud gets its own depth, floored at the
        // stratus sheet, so a deck is no longer everything issued with one height.
        assertTrue(density.contains(
                "float profile = cloudHeightProfile(layer, pw.y, type, cloudDepthFraction(coverage, type));"));
        assertTrue(density.contains("public float cloudDepthFraction(float coverage, float type)"));
        // The floor and the profile's own stratus band must move together or the floor stops
        // meaning "the thinnest cloud this system can represent".
        assertTrue(density.contains("public static const float CLOUD_STRATUS_TOP = 0.18;"));
        assertTrue(density.contains("(1.0 - smoothstep(0.07, 0.18, t))"));
        assertTrue(density.contains("float3 detailPw = pw + float3(layer.detailOffset.x, 0.0, layer.detailOffset.y);"));
        // Same fetch, now prefiltered to the march step rather than always reading level 0.
        assertTrue(density.contains("float erosion = tex.noise.SampleLevel(detailPw / layer.detailScale,"));
        assertTrue(density.contains("cloudNoiseLod(footprint, layer.detailScale)).g;"));
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
        String density = source("shaders/world/cloud_density.slang");
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
        String density = source("shaders/world/cloud_density.slang");
        String config = source(
                "common/src/main/java/io/github/dswepm/fluorite/FluoriteConfig.java");

        // The warp displaces the SHAPE lookup only. Coverage and the height profile decide where a cloud
        // system stands and how tall it is; displacing those would move the weather rather than the cloud.
        assertTrue(density.contains(
                "float shape = evolvingCloudShape(tex, layer, cloudWarpPosition(tex, layer, pw),"
                        + " type, footprint);"));
        assertTrue(density.contains("float3 cloudWarpPosition(CloudTextures tex, CloudLayer layer, float3 pw)"));
        assertTrue(cloud.contains("[[vk::binding(28, 0)]] Sampler3D cloudWarp;"));
        // Its own advection is what makes cloud grow instead of pass by unchanged. Sharing the shape
        // field's drift would deform each cloud once and then translate it rigidly.
        // Along the warp volume's own axis, not horizontally: a sideways drift makes the deformation
        // SWEEP across the cloud, and a sweep reads as translation -- the one motion the warp exists to
        // stop being the only thing in the sky.
        assertTrue(density.contains("float3(0.0, layer.warpClock, 0.0)"));
        // Zero amplitude must skip the fetch and leave the field bit-for-bit as it was.
        assertTrue(density.contains("if (amplitude <= 0.0)"));
        // THE SPLIT ITSELF. A shadow is only right if it is cast by the same density the cloud is drawn
        // from, so the field lives in a module with no bindings that both the shading and the shadow bake
        // import. Two copies would agree the day they were written and diverge the first time one was
        // edited alone, and "the shadow is not under its cloud" is not attributable from a screenshot.
        assertFalse(density.contains("[[vk::binding"));
        // Its banner says the words, so this has to look for USE: a member access or a
        // parameter, not the string.
        assertFalse(density.contains("worldPush."));
        assertTrue(cloud.contains("import cloud_density;"));
        assertTrue(config.contains("\"volumetrics.cloud-warp-amount\""));
        assertTrue(config.contains("\"volumetrics.cloud-warp-scale\""));
        // The rate is its OWN setting, not a fraction of the wind. As a fraction it was proportional to
        // wind speed and still took eight minutes per feature -- invisible -- and it froze the whole sky
        // whenever the wind was set to zero, which is the opposite of what a calm day looks like.
        String composite = source(
                "common/src/main/java/io/github/dswepm/fluorite/rt/RtComposite.java");
        assertTrue(composite.contains(
                "FluoriteConfig.Rt.Volumetrics.CLOUD_EVOLUTION_SPEED.value()"));
        assertFalse(composite.contains("CLOUD_WIND_SPEED.value() * 0.45f"));
        // Wrapped on the CPU: the offset grows without bound over a session and the sampler repeats
        // anyway, so the coordinate handed to the GPU must never be what limits the evolution.
        assertTrue(composite.contains("(float) (travelled % scale)"));
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
