package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contracts for D176's light-space cloud shadow map.
 *
 * <p>Every assertion here guards a failure that is INVISIBLE AT THE POINT IT HAPPENS. A shadow map that
 * disagrees with the sky about where a cloud is, how big the map is, or which way the light points does
 * not crash, does not warn and does not look obviously broken — it produces shadows that are somewhere
 * else, which reads as an art problem and gets chased in the wrong file for a long time.
 */
final class RtCloudShadowContractTest {
    @Test
    void theBakeIntegratesTheSameFieldTheSkyIsDrawnFrom() throws IOException {
        String bake = source("shaders/world/cloud_shadow.comp.slang");

        // The whole reason cloud_density was split out of cloud.slang. If this pass ever grows its own
        // copy of the density -- even a "simplified" one for speed -- the shadow and the cloud casting it
        // become two independent opinions that agree only until one of them is edited.
        assertTrue(bake.contains("import cloud_density;"));
        assertTrue(bake.contains("cloudDensity(tex, low, q, false, footprint)"));
        assertTrue(bake.contains("lowCloudLayer(wp)"));
        assertTrue(bake.contains("highCloudLayer(wp)"));
        // FULL density, erosion included. Erosion only ever removes material, so the cheap path would
        // darken every cloud shadow by a bias with nothing anywhere to correct it.
        assertFalse(bake.contains("cloudDensity(tex, low, q, true"));
        // It must not reach for the raygen's descriptor set, which is the constraint that forced the
        // split in the first place: this pass has its own set and reads WorldPush by address.
        assertFalse(bake.contains("worldPush."));
        assertTrue(bake.contains("ConstPtr<WorldPush>(pc.worldPushAddr)[0]"));

        // The high sheet is part of the answer. Cirrus is thin, but a map that silently omits it reports
        // full sun under an overcast cirrus deck, and 8.10 records covering both layers as the interface.
        assertTrue(bake.contains("highSheetOpticalDepth"));
        assertTrue(bake.contains("highCloudVerticalTau"));
    }

    @Test
    void theProjectionIsDerivedOnceSoTheBakeAndItsReadersCannotDisagree() throws IOException {
        String density = source("shaders/world/cloud_density.slang");
        String bake = source("shaders/world/cloud_shadow.comp.slang");

        // ONE function builds the light-space basis, and both ends call it. A bake and a lookup that each
        // derived their own would be a shadow map sampled at a different place from where it was written
        // -- and it would look like a plausible offset rather than like a bug.
        assertTrue(density.contains("public CloudShadowFrame cloudShadowFrame(float3 lightDir, float3 camera)"));
        assertTrue(density.contains("public float2 cloudShadowUv(CloudShadowFrame f, float3 p)"));
        assertTrue(density.contains("public float3 cloudShadowTexelPoint(CloudShadowFrame f, uint2 texel)"));
        assertTrue(bake.contains("cloudShadowFrame(wp.lightDir.xyz, wp.camOffset)"));
        assertTrue(bake.contains("cloudShadowTexelPoint(frame, id.xy)"));

        // Snapped to whole texels (R22). A grid that slides continuously resamples a noisy field at a
        // different phase every frame, which shows as the shadows crawling rather than moving.
        assertTrue(density.contains("floor(centre / CLOUD_SHADOW_BLOCKS_PER_TEXEL)"));
    }

    @Test
    void theMapsSizeIsAgreedBetweenTheShaderAndTheDispatch() throws IOException {
        String density = source("shaders/world/cloud_density.slang");
        String sky = source("common/src/main/java/io/github/dswepm/fluorite/rt/sky/RtSky.java");

        // Baked at one size and read at another puts every shadow somewhere other than under its cloud,
        // and nothing in Vulkan objects: the dispatch simply covers part of the image or runs off it.
        assertTrue(density.contains("public static const int CLOUD_SHADOW_DIM = 512;"));
        assertTrue(sky.contains("private static final int CLOUD_SHADOW_DIM = 512;"));
        assertTrue(density.contains("public static const float CLOUD_SHADOW_BLOCKS_PER_TEXEL = 16.0;"));
    }

    @Test
    void theMapIsAlwaysAValidTransmittance() throws IOException {
        String bake = source("shaders/world/cloud_shadow.comp.slang");
        String sky = source("common/src/main/java/io/github/dswepm/fluorite/rt/sky/RtSky.java");
        String composite = source(
                "common/src/main/java/io/github/dswepm/fluorite/rt/RtComposite.java");

        // Clouds off, or a sun on the horizon, must WRITE clear sky rather than return early. A texel
        // left holding the previous frame's value is a shadow that stays on the ground after the cloud
        // has gone, and the alternative -- making every consumer ask whether the map may be trusted --
        // is a condition each of them can get wrong independently.
        assertTrue(bake.contains("cloudShadowOut[int2(id.xy)] = 1.0;"));
        assertTrue(bake.contains(
                "!frame.valid || !cloudsOn(wp) || !cloudShadowsOn(wp) || low.extinction <= 0.0"));
        // Which is only true if the dispatch is unconditional. Gating it on the CPU would reintroduce
        // exactly the stale-texture case the shader-side write exists to remove.
        assertTrue(composite.contains("skyLuts.recordCloudShadowBake(cmd, pushBuf.deviceAddress);"));
        // And the image's initial contents must be clear sky too. Zero in a transmittance map is FULL
        // SHADOW, so the default every other storage image here gets would black the world out if the
        // bake never ran -- a spectacular symptom for a texture that merely failed to load.
        assertTrue(sky.contains("clearImageToWhite(ctx, sky.cloudShadow)"));

        // Beer, and only Beer: this is the direct beam's survival, which is what a receiving surface
        // attenuates its sun term by. Light the cloud scattered rather than absorbed is not missing from
        // the world, it arrives as skylight, and mediumSkyRadiance already carries it.
        assertTrue(bake.contains("exp(-tau)"));
    }

    @Test
    void theShadowLandsOnTheSunsIrradianceAndNowhereElse() throws IOException {
        String rgen = source("shaders/world/world.rgen.slang");
        String cloud = source("shaders/world/cloud.slang");
        String volume = source("shaders/world/volume.slang");

        // ON THE IRRADIANCE, not on the shadow ray's transmittance. "Less sun arrives here" is a
        // different statement from "something is in the way", and putting it on the irradiance is what
        // makes one line cover the diffuse term, the specular term and the back-face transmission that
        // reuses the same quantity two hundred lines further down.
        assertTrue(rgen.contains("sampledLightIrradiance *= cloudSunTransmittance(p);"));
        assertTrue(rgen.contains("sampledLightIrradiance *= cloudSunTransmittance(hitPos);"));

        // NOT the fog. Its segments routinely run above the deck, where a map that integrates the whole
        // light ray would shadow a point with cloud that is underneath it.
        assertFalse(volume.contains("cloudSunTransmittance"));
        // NOT the cloud's own shading either -- sunOpticalDepth already marches the deck from inside it,
        // at the sample's own altitude, and doing both would shadow every cloud twice. Counted rather
        // than searched: the map may appear in cloud.slang exactly where it is declared and exactly
        // where the wrapper reads it, and a third mention would be the march having helped itself.
        assertEquals(2, cloud.split("cloudShadowMap", -1).length - 1,
                "cloud.slang may name cloudShadowMap only at its binding and in the wrapper");
    }

    @Test
    void offRestoresWhatShippedIncludingTheProxyItReplaces() throws IOException {
        String config = source(
                "common/src/main/java/io/github/dswepm/fluorite/FluoriteConfig.java");
        String composite = source(
                "common/src/main/java/io/github/dswepm/fluorite/rt/RtComposite.java");
        String forcing = source(
                "common/src/main/java/io/github/dswepm/fluorite/rt/RtEnvironmentForcing.java");

        assertTrue(config.contains("\"volumetrics.cloud-shadows\""));
        // Nested under the clouds, like the diffusion term: without a cloud there is nothing to cast a
        // shadow, so the pair's off state should be one state and not two that differ in dead work.
        assertTrue(composite.contains("flags |= 1 << 31;"));

        // THE PROXY AND THE MAP MUST NEVER BOTH BE LIVE. D73's global weather scalar fades caustic
        // CONTRAST to stand in for clouds the renderer could not locate; the map removes the beam's
        // ENERGY where the cloud actually is. Both at once darkens and flattens underwater caustics
        // twice over. Off has to bring the proxy back, or the switch's off state would not be the
        // behaviour that shipped.
        assertTrue(forcing.contains(
                "&& !FluoriteConfig.Rt.Volumetrics.CLOUD_SHADOWS.value()"));
    }

    @Test
    void theBakeIsPricedOnItsOwn() throws IOException {
        String composite = source(
                "common/src/main/java/io/github/dswepm/fluorite/rt/RtComposite.java");
        String frameStats = source(
                "common/src/main/java/io/github/dswepm/fluorite/rt/RtFrameStats.java");

        // 8.10 budgeted this at about 1% of the primary trace by arithmetic and nobody has weighed it.
        // Folding it into gpu.skyBake would leave that permanently unanswerable, which is the same
        // reason the froxel was split out of that zone.
        assertTrue(composite.contains("GPU_ZONE_CLOUD_SHADOW"));
        assertTrue(composite.contains("\"gpu.cloudShadow\""));
        assertTrue(frameStats.contains("\"gpu.cloudShadow\""));
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
