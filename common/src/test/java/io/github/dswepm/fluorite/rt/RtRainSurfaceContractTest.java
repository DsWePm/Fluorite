package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Source-level contracts for M21's cross-pass rain and wet-surface architecture. */
final class RtRainSurfaceContractTest {
    @Test
    void exposureIsWorldAnchoredSharedAndSlidesItsCpuCache() throws IOException {
        String bake = source("shaders/world/rain_exposure.comp.slang");
        String surface = source("shaders/world/rain_surface.slang");
        String sky = source("common/src/main/java/io/github/dswepm/fluorite/rt/sky/RtSky.java");

        assertTrue(bake.contains("static const uint RAIN_SHADOW_MASK = 0x05u"));
        assertTrue(bake.contains("wp.rainExposureOrigin.xyz"));
        assertTrue(bake.contains("wp.rainDirection.xyz"));
        assertTrue(surface.contains("[[vk::binding(24, 0)]] Sampler2D<float> rainExposureDepth"));
        assertTrue(surface.contains("rainExposureSample"));
        assertTrue(surface.contains("float4 bilinear"));
        assertTrue(surface.contains("RAIN_SIDE_SHELL = 1.05"));
        assertTrue(surface.contains("RAIN_SIDE_SHELL / max(abs(worldPush.rainDirection.y)"));
        assertTrue(surface.contains("rainTopExposureAt"));
        assertTrue(surface.contains("bool filmReceiver"));
        assertTrue(surface.contains("[[vk::binding(25, 0)]] Sampler2D<uint2> rainWetHistory"));
        assertTrue(surface.contains("max(worldPush.rainState.x, worldPush.rainState.y)"));
        assertTrue(surface.contains("float4 rainHistorySample"));
        assertTrue(surface.contains("return lerp(lerp(h00, h10, f.x)"));

        // A one-cell camera move must reuse the overlap rather than rebuilding the 128^2 high map.
        assertTrue(sky.contains("rainPrecipitationScratch"));
        assertTrue(sky.contains("int oldX = x + shiftX"));
        assertTrue(sky.contains("int oldZ = z + shiftZ"));
        assertTrue(sky.contains("queries++"));
        assertTrue(sky.contains("System.arraycopy(rainPrecipitationScratch"));
    }

    @Test
    void wetFilmIsEnergyLayeredInNeeContinuationAndRrGuides() throws IOException {
        String surface = source("shaders/world/rain_surface.slang");
        String bsdf = source("shaders/world/bsdf.slang");
        String indirect = source("shaders/world/world.rgen.slang");
        String lighting = source("shaders/world/lighting.slang");
        String primary = source("shaders/world/world_primary.rgen.slang");

        assertTrue(surface.contains("WATER_FILM_F0 = 0.020373"));
        assertTrue(bsdf.contains("rainBaseTransmission"));
        assertTrue(bsdf.contains("rainBaseExitTransmission"));
        assertTrue(bsdf.contains("rainFilmBrdf"));
        assertTrue(indirect.contains("baseBrdf * rainBaseTransmission"));
        assertTrue(indirect.contains("+ rainFilmBrdf"));
        assertTrue(indirect.contains("* rainBaseExitTransmission(bc)"));
        assertTrue(lighting.contains("* rainBaseExitTransmission(c)"));
        assertTrue(indirect.contains("if (lobeChoice < pf)"));
        assertTrue(primary.contains("float3 filmGuide"));
        assertTrue(primary.contains("guide.reflectance += filmGuide"));
    }

    @Test
    void puddlesArePeriodicWorldFieldsWithExactCoverageEndpoints() throws IOException {
        String surface = source("shaders/world/rain_surface.slang");

        assertTrue(surface.contains("RAIN_WORLD_PERIOD = 4096.0"));
        assertTrue(surface.contains("rainHashPeriodic"));
        assertTrue(surface.contains("coverage <= 0.0 ? 0.0"));
        assertTrue(surface.contains("coverage >= 1.0 ? 1.0"));
        assertTrue(surface.contains("worldPush.waterAnchor.xy"));
        assertTrue(surface.contains("rainSmoothFbmPeriodic"));
        assertFalse(surface.contains("r.film = max(r.film, r.puddle)"));
        assertTrue(surface.contains("applyRainImpactRipples"));
        assertTrue(surface.contains("openWaterRainImpact"));
    }

    @Test
    void rippleFieldUsesIndependentWorldAnchoredEventsInsteadOfPermanentCellRings()
            throws IOException {
        String surface = source("shaders/world/rain_surface.slang");
        String events = source(
                "common/src/main/java/io/github/dswepm/fluorite/rt/entity/RtRainImpactEvents.java");

        assertTrue(surface.contains("RAIN_IMPACT_CELL_SIZE = 0.50"));
        assertTrue(surface.contains("rainImpactMix"));
        assertTrue(surface.contains("0x7FEB352Du"));
        assertTrue(surface.contains("0x846CA68Bu"));
        assertTrue(surface.contains("uint2 rainWrappedImpactCell(int2 cell)"));
        assertTrue(events.contains("CELL_PERIOD = 8192"));
        assertTrue(events.contains("0x7FEB352D"));
        assertTrue(events.contains("0x846CA68B"));
        assertTrue(events.contains("record EventKey"));
        assertTrue(events.contains("static Event sample"));
        assertTrue(surface.contains("for (int oy = -1; oy <= 1; ++oy)"));
        assertTrue(surface.contains("uint eventIndex = uint(floor(eventClock)) % cycleCount"));
        assertTrue(surface.contains("float activeFraction = lerp("));
        assertTrue(surface.contains("float2 eventCentre"));
        assertTrue(surface.contains("float eventRadius"));
        assertTrue(surface.contains("float eventStrength"));
        assertTrue(surface.contains("float footprint"));
        assertTrue(surface.contains("worldPush.rainCalibration1.z"));
        assertTrue(surface.contains("antiAliasWidth"));
        assertTrue(surface.contains("float wakeDistance"));
        assertTrue(surface.contains("float waveDamping"));
        assertTrue(surface.contains("cos(PI * wakeDistance)"));
        assertTrue(surface.contains("worldPush.rainPuddle.x"));
        assertFalse(surface.contains("float signedSlope = radial >= 0.0 ? band * band : -band * band"));
        assertTrue(surface.contains("rainPeriodicDelta"));
        assertFalse(surface.contains("worldPush.rainPuddle.w * 2.4 + rainHash(cell + 47.1)"));
    }

    @Test
    void waterNeverReceivesFilmAndRainDiagnosisExposesAllThreeSignals() throws IOException {
        String primary = source("shaders/world/world_primary.rgen.slang");
        String indirect = source("shaders/world/world.rgen.slang");
        String options = source(
                "common/src/main/java/io/github/dswepm/fluorite/client/RtVideoOptions.java");

        assertTrue(primary.contains("RainSurface rain = material == MATERIAL_OPAQUE"));
        assertTrue(primary.contains("? evaluateRainSurface(hitPos, ext"));
        assertTrue(indirect.contains("n = applyRainImpactRipples(hitPos, n, openWaterRainImpact"));
        assertTrue(primary.contains("pc.debugView == 26u"));
        assertTrue(primary.contains("rainSurfaceDebug"));
        assertTrue(primary.contains("pc.debugView == 27u"));
        assertTrue(primary.contains("rainPuddleDebug"));
        assertTrue(options.contains("24, 25, 26, 27"));
        assertTrue(options.contains("Math.clamp(setting.value(), 0, 27)"));
    }

    @Test
    void wetBoundaryUsesWorldAnchoredMultibandJitter() throws IOException {
        String surface = source("shaders/world/rain_surface.slang");

        assertTrue(surface.contains("rainBoundaryJitter"));
        assertTrue(surface.contains("rainValueNoisePeriodic(worldXZ * 0.65"));
        assertTrue(surface.contains("rainValueNoisePeriodic(worldXZ * 2.35"));
        assertTrue(surface.contains("RAIN_BOUNDARY_JITTER_BLOCKS"));
        assertTrue(surface.contains("rainBoundaryWarp"));
        assertTrue(surface.contains("rainTopExposureAt"));
        assertTrue(surface.contains("rainTopHistoryAt"));
    }

    @Test
    void wetFilmUsesTopFacesExceptSkyExposedSmallPlants() throws IOException {
        String common = source("shaders/world/world_common.slang");
        String closestHit = source("shaders/world/world.rchit.slang");
        String core = source("shaders/world/world_core.slang");
        String surface = source("shaders/world/rain_surface.slang");

        assertTrue(common.contains("PAYLOAD_RAIN_TOP_FACE"));
        assertTrue(common.contains("PAYLOAD_RAIN_PLANT"));
        assertTrue(closestHit.contains("pr.normal.y > 0.5"));
        assertTrue(closestHit.contains("TERRAIN_PRIM_RAIN_PASS"));
        assertTrue(core.contains("payloadRainFilmReceiver"));
        assertTrue(core.contains("payloadRainTopFace"));
        assertTrue(surface.contains("bool plantReceiver"));
        assertTrue(surface.contains("plantReceiver ? rainExposureAt(p) : rainTopExposureAt(p)"));
        assertFalse(surface.contains("sideFilm"));
    }

    @Test
    void puddleCalibrationStrengthensARealThinLayerWithoutParallax() throws IOException {
        String surface = source("shaders/world/rain_surface.slang");
        String common = source("shaders/world/world_common.slang");
        String config = source("common/src/main/java/io/github/dswepm/fluorite/FluoriteConfig.java");

        assertTrue(common.contains("rainCalibration0"));
        assertTrue(common.contains("rainCalibration1"));
        assertTrue(surface.contains("worldPush.rainCalibration0.z"));
        assertTrue(surface.contains("worldPush.rainCalibration0.w"));
        assertTrue(surface.contains("worldPush.rainCalibration1.x"));
        assertTrue(surface.contains("worldPush.rainCalibration1.y"));
        assertTrue(surface.contains("float rainPuddleMaskAt(float3 p, bool topReceiver)"));
        assertTrue(surface.contains("if (!topReceiver)"));
        assertFalse(surface.contains("smoothstep(0.86, 0.985, n.y)"));
        assertTrue(surface.contains("bool filmReceiver, bool topReceiver, bool plantReceiver"));
        assertTrue(surface.contains("float puddleExtraDarkening = clamp("));
        assertTrue(surface.contains("r.puddle * worldPush.rainCalibration1.x, 0.0, 0.25"));
        assertTrue(surface.contains("1.0 - (1.0 - r.darkening) * (1.0 - puddleExtraDarkening)"));
        assertFalse(surface.contains("exp(-r.puddle * worldPush.rainCalibration1.x)"));
        assertTrue(surface.contains("applyRainLayerNormal"));
        assertTrue(surface.contains("float filmFlatten = clamp(rain.film * worldPush.rainCalibration1.w"));
        assertTrue(surface.contains("1.0 - (1.0 - filmFlatten) * (1.0 - puddleFlatten)"));
        assertTrue(surface.contains("topReceiver ? float3(0.0, n.y >= 0.0 ? 1.0 : -1.0, 0.0)"));
        assertFalse(surface.contains("applyRainPuddleLayer"));
        assertFalse(surface.toLowerCase().contains("parallax"));
        assertTrue(config.contains("WET_DARKENING_GAIN"));
        assertTrue(config.contains("WET_COAT_GAIN"));
        assertTrue(config.contains("PUDDLE_LAYER_GAIN"));
        assertTrue(config.contains("PUDDLE_ROUGHNESS"));
        assertTrue(config.contains("PUDDLE_EXTRA_DARKENING"));
        assertTrue(config.contains("\"weather.puddle-extra-darkening\", 0.08f, 0f, 0.25f"));
        assertFalse(config.contains("PUDDLE_OPTICAL_DARKENING"));
        assertTrue(config.contains("PUDDLE_NORMAL_FLATTENING"));
        assertTrue(config.contains("WET_FILM_NORMAL_FLATTENING"));
        assertTrue(config.contains("RAIN_RIPPLE_WIDTH"));
        assertTrue(config.contains("\"weather.puddle-ripple-strength\", 0.35f, 0f, 3f"));
        assertTrue(config.contains("\"weather.wet-darkening-gain\", 1f, 0f, 8f"));
    }

    @Test
    void rainStreakIdentityIsWorldCellStableAndNearPlaneSafe() throws IOException {
        String streak = source("shaders/overlay/rain_streak.vert.slang");
        String shared = source("shaders/world/rain_streak_common.slang");

        assertTrue(shared.contains("rainCellSeed"));
        assertTrue(shared.contains("int2 worldCell"));
        assertTrue(streak.contains("rainStreakPlacement"));
        assertTrue(streak.contains("minEndpointDistance"));
        assertFalse(shared.contains("rainRandom(instanceId"));
    }

    @Test
    void rainStreakLightingUsesOneShadowedUnifiedLightEvaluationPerInstance() throws IOException {
        String shared = source("shaders/world/rain_streak_common.slang");
        String lighting = source("shaders/overlay/rain_streak_light.comp.slang");
        String vertex = source("shaders/overlay/rain_streak.vert.slang");
        String streaks = source(
                "common/src/main/java/io/github/dswepm/fluorite/rt/overlay/RtRainStreaks.java");
        String composite = source(
                "common/src/main/java/io/github/dswepm/fluorite/rt/RtComposite.java");

        assertTrue(shared.contains("uint64_t radianceAddr"));
        assertTrue(shared.contains("rainStreakPlacement"));
        assertTrue(lighting.contains("import light_sampling"));
        assertTrue(lighting.contains("sampleVolumeEmitter"));
        assertTrue(lighting.contains("RayQuery<RAY_FLAG_FORCE_OPAQUE"));
        assertTrue(lighting.contains("wp.mediumSkyRadiance.xyz"));
        assertTrue(lighting.contains("wp.lightRadiance.xyz"));
        assertTrue(lighting.contains("RAIN_STREAK_SCATTER_RESPONSE"));
        assertTrue(lighting.contains("DevicePtr<float4> radiance = DevicePtr<float4>(push.radianceAddr)"));
        assertTrue(lighting.contains("radiance[instanceId]"));
        assertFalse(lighting.contains("float3(0.015"));
        assertTrue(vertex.contains("ConstPtr<float4>(push.radianceAddr)[instanceId].xyz"));
        assertFalse(vertex.contains("float3(0.015"));
        assertFalse(vertex.contains("wp.mediumSkyRadiance.xyz * 0.45"));
        assertTrue(streaks.contains("vkCmdDispatch"));
        assertTrue(streaks.contains("VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT"));
        assertTrue(streaks.contains("VK_PIPELINE_STAGE_VERTEX_SHADER_BIT"));
        assertTrue(streaks.contains("RtOverlayPipelines.accelStructureSet"));
        assertTrue(streaks.contains("PUSH_BYTES = 96"));
        assertTrue(streaks.contains("MAX_INSTANCES = 16_384"));
        assertTrue(streaks.contains("putLong(48, radiance.deviceAddress)"));
        assertTrue(streaks.contains("putInt(56, instanceCount).putInt(60, frameIndex)"));
        assertTrue(streaks.contains("(long) MAX_INSTANCES * 4L * Float.BYTES"));
        assertTrue(composite.contains("terrain.lightBufferAddress()"));
        assertTrue(composite.contains("frameTlas.accel.handle, displayW, displayH"));
        assertTrue(composite.contains("terrain.lightGridSpanBufferAddress(), graphicsUse"));
    }

    @Test
    void exposurePrioritisesNearTilesAndFoliageIsNotAFullRoof() throws IOException {
        String bake = source("shaders/world/rain_exposure.comp.slang");
        String sky = source("common/src/main/java/io/github/dswepm/fluorite/rt/sky/RtSky.java");
        String mesher = source("common/src/main/java/io/github/dswepm/fluorite/rt/terrain/RtTerrainMesher.java");

        assertTrue(bake.contains("updateTile"));
        assertTrue(bake.contains("TERRAIN_PRIM_RAIN_PASS"));
        assertTrue(bake.contains("TERRAIN_PRIM_RAIN_FOLIAGE"));
        assertTrue(sky.contains("rainExposureTileOrder"));
        assertTrue(sky.contains("RAIN_EXPOSURE_TILES_PER_FRAME"));
        assertTrue(sky.contains("RAIN_EXPOSURE_NEAR_TILES_PER_FRAME"));
        assertTrue(sky.contains("clearRainExposure"));
        assertTrue(sky.contains("directionChanged"));
        assertTrue(sky.contains("clear.float32(0, -2.0f)"));
        assertTrue(sky.contains("rainHistorySampler"));
        assertTrue(sky.contains("RAIN_PRECIP_UNKNOWN"));
        assertTrue(sky.contains("rainPrecipitationResolved"));
        assertTrue(sky.contains("refreshRainPrecipitationTiles"));
        assertTrue(sky.contains("getChunkSource().hasChunk"));
        assertTrue(bake.contains("precipitation == RAIN_PRECIP_UNKNOWN"));
        assertTrue(bake.contains("rainDepth[int2(updateTile)] = -2.0"));
        assertTrue(mesher.contains("PRIM_RAIN_PASS"));
        assertTrue(mesher.contains("PRIM_RAIN_FOLIAGE"));
    }

    @Test
    void twoLayerGpuHistoryRetainsCoveredGroundAndDriesItLocally() throws IOException {
        String history = source("shaders/world/rain_history.comp.slang");
        String surface = source("shaders/world/rain_surface.slang");
        String sky = source("common/src/main/java/io/github/dswepm/fluorite/rt/sky/RtSky.java");

        assertTrue(history.contains("Persistent two-layer world-column wetness"));
        assertTrue(history.contains("bool match0"));
        assertTrue(history.contains("bool match1"));
        assertTrue(history.contains("old0.depth < 0.0 && old1.depth < 0.0"));
        assertTrue(history.contains("dryLayer(previous"));
        assertTrue(history.contains("newDepth < -1.5"));
        assertTrue(history.contains("float surfaceRain = max(wp.rainState.x, wp.rainState.y)"));
        assertTrue(surface.contains("abs(t - depth0) <= RAIN_DEPTH_BIAS + shell"));
        assertTrue(surface.contains("abs(t - depth1) <= shell"));
        assertTrue(sky.contains("VK_FORMAT_R32G32_UINT"));
    }

    @Test
    void visibleRainRunsAfterRrAndBeforeExposureWhileImpactsNeverReadBackGpuDepth()
            throws IOException {
        String composite = source(
                "common/src/main/java/io/github/dswepm/fluorite/rt/RtComposite.java");
        String impacts = source(
                "common/src/main/java/io/github/dswepm/fluorite/rt/entity/RtRainImpacts.java");

        int fallback = composite.indexOf("blitUpscale(cmd");
        int rain = composite.indexOf("rainStreaks.record", fallback);
        int exposure = composite.indexOf("exposure.record", rain);
        assertTrue(fallback >= 0 && rain > fallback && exposure > rain);

        assertTrue(impacts.contains("ClipContext.Block.COLLIDER"));
        assertTrue(impacts.contains("ClipContext.Fluid.ANY"));
        assertTrue(impacts.contains("RtRainImpactEvents.sample"));
        assertTrue(impacts.contains("RAIN_SPLASH_TARGET"));
        assertTrue(impacts.contains("(target + 9) / 10"));
        assertTrue(impacts.contains("event.phase() > RtRainImpactEvents.EARLY_SPLASH_PHASE"));
        assertTrue(impacts.contains("level.getHeight(Heightmap.Types.MOTION_BLOCKING"));
        assertTrue(impacts.contains("record Splash"));
        assertTrue(impacts.contains("activeSplashes"));
        assertFalse(impacts.contains("RandomSource"));
        assertFalse(impacts.contains("RAIN_SPLASHES_PER_TICK"));
        assertFalse(impacts.contains("ParticleTypes.RAIN"));
        assertTrue(impacts.contains("level.getMinY() + level.getHeight() + 1"));
        assertFalse(impacts.contains("MemoryUtil"));
        assertFalse(impacts.contains("vkMapMemory"));
    }

    @Test
    void dedicatedRainSplashesUseNeutralProceduralRtGeometry() throws IOException {
        String entities = source("common/src/main/java/io/github/dswepm/fluorite/rt/entity/RtEntities.java");
        String capture = source("common/src/main/java/io/github/dswepm/fluorite/rt/entity/RtEntityCapture.java");
        String common = source("shaders/world/world_common.slang");
        String closestHit = source("shaders/world/world.rchit.slang");
        String anyHit = source("shaders/world/world.rahit.slang");
        String config = source("common/src/main/java/io/github/dswepm/fluorite/FluoriteConfig.java");

        assertTrue(entities.contains("appendRainSplashes"));
        assertTrue(entities.contains("PRIM_RAIN_SPLASH"));
        assertTrue(entities.contains("p instanceof WaterDropParticle"));
        assertTrue(capture.contains("setProceduralAlphaFrom"));
        assertTrue(common.contains("PRIM_RAIN_SPLASH"));
        assertTrue(closestHit.contains("proceduralRainSplash"));
        assertTrue(anyHit.contains("proceduralRainSplash"));
        assertTrue(config.contains("RAIN_SPLASH_SIZE"));
        assertTrue(config.contains("RAIN_SPLASH_OPACITY"));
        assertTrue(config.contains("RAIN_SPLASH_BRIGHTNESS"));
        assertTrue(config.contains("RAIN_SPLASH_TARGET"));
        assertTrue(config.contains("\"weather.rain-splash-target\", 96, 0, 256"));
        assertTrue(entities.contains("rainSplashColor()"));
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
