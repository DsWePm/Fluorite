package io.github.dswepm.fluorite.rt.sky;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import io.github.dswepm.fluorite.rt.RtContext;
import io.github.dswepm.fluorite.rt.RtDebugLabels;
import io.github.dswepm.fluorite.rt.RtGpuExecutor;
import io.github.dswepm.fluorite.rt.RtFrameStats;
import io.github.dswepm.fluorite.rt.overlay.RtOverlayPipelines;
import io.github.dswepm.fluorite.rt.accel.RtBuffer;
import io.github.dswepm.fluorite.rt.accel.RtImage;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;

import static io.github.dswepm.fluorite.rt.RtContext.check;

/**
 * The atmosphere's precomputed tables. Owns the LUT images, bakes them when their inputs change, and
 * hands the views to whoever binds them — the same ownership shape {@code RtComposite} uses for the
 * trace targets, and the same compute-pipeline shape {@code RtExposurePipeline} uses for its two passes.
 *
 * <p>The dependency chain is transmittance → multiple scattering → sky-view → medium-sky reduction →
 * aerial-perspective froxel. The first two tables are baked once because the sun enters where they are
 * sampled; the last three run every frame because they depend on sun or camera state. Barriers between
 * dependent links live here, beside the order they protect, so no caller can observe an intermediate
 * half-written table or stale shared medium radiance.
 */
public final class RtSky {
    private static final String SHADER_DIR = "/fluorite/rt/";
    /** Must match SKY_TRANSMITTANCE_W/H in shaders/world/atmosphere.slang. */
    public static final int TRANSMITTANCE_W = 256;
    public static final int TRANSMITTANCE_H = 64;
    /** Must match SKY_MULTISCATTER_N in shaders/world/atmosphere.slang. */
    public static final int MULTISCATTER_N = 32;
    /** Must match SKY_VIEW_W/H in shaders/world/atmosphere.slang. */
    public static final int SKY_VIEW_W = 192;
    public static final int SKY_VIEW_H = 128;
    private static final int SKY_VIEW_PUSH_BYTES = 32; // float4 sun direction + float4 art tint
    private static final int MEDIUM_SKY_REDUCE_PUSH_BYTES = 32; // WorldPush address + float4 sun direction
    /** Must match FROXEL_W/H/D in shaders/world/sky_froxel.comp.slang. */
    public static final int FROXEL_W = 64;
    public static final int FROXEL_H = 36;
    public static final int FROXEL_D = 64;
    private static final int FROXEL_GROUP = 8; // matches [numthreads(8, 8, 1)]
    // WorldPush plus the five addresses of the shared Light/alias/grid interface.
    private static final int FROXEL_PUSH_BYTES = 48;
    /** Must match VIS_GRID_W/H/D in shaders/world/volume_visibility{,.comp}.slang. */
    public static final int VIS_GRID_W = 64;
    public static final int VIS_GRID_H = 32;
    public static final int VIS_GRID_D = 64;
    private static final int VIS_GROUP = 4; // matches [numthreads(4, 4, 4)]
    private static final int VIS_PUSH_BYTES = 8; // WorldPush address; the grid's placement is a field in it
    /** D110 high tier; low dispatches only the top-left 256² of this fixed allocation. */
    public static final int RAIN_EXPOSURE_MAX = 512;
    private static final int RAIN_EXPOSURE_GROUP = 8;
    private static final int RAIN_EXPOSURE_PUSH_BYTES = 48;
    private static final int RAIN_EXPOSURE_TILE = 32;
    private static final int RAIN_EXPOSURE_TILES_PER_FRAME = 8;
    private static final int RAIN_EXPOSURE_NEAR_TILES = 9;
    private static final int RAIN_EXPOSURE_NEAR_TILES_PER_FRAME = 4;
    private static final int RAIN_HISTORY_PUSH_BYTES = 64;
    private static final int RAIN_PRECIP_CELL = 4;
    private static final int RAIN_PRECIP_MAX_DIM = RAIN_EXPOSURE_MAX / RAIN_PRECIP_CELL;
    private static final int RAIN_PRECIP_NONE = 0;
    private static final int RAIN_PRECIP_RAIN = 1;
    /**
     * Snow. Must equal PRECIP_SNOW in precipitation.slang.
     *
     * <p>It used to be folded into NONE, and that is exactly why a snowstorm rendered as an empty sky:
     * the exposure bake read "not rain" as "nothing falls here" and wrote the marker that culls every
     * flake. Snow falls, is sheltered by the same roofs, and does NOT wet anything -- three facts that
     * one boolean could not carry.
     */
    private static final int RAIN_PRECIP_SNOW = 3;
    private static final int RAIN_PRECIP_UNKNOWN = 2;
    /** Matches RtComposite's push ring; each slot is host-written only after its prior GPU use completes. */
    private static final int RAIN_PRECIP_RING = 6;
    private static final int BAKE_GROUP = 8; // matches [numthreads(8, 8, 1)] in both bake shaders

    /** One compute stage: its layout, its set, and its pipeline. */
    private record Bake(long descriptorSetLayout, long descriptorPool, long descriptorSet,
                        long pipelineLayout, long pipeline) {
        void destroy(VkDevice vk) {
            VK10.vkDestroyPipeline(vk, pipeline, null);
            VK10.vkDestroyPipelineLayout(vk, pipelineLayout, null);
            VK10.vkDestroyDescriptorPool(vk, descriptorPool, null);
            VK10.vkDestroyDescriptorSetLayout(vk, descriptorSetLayout, null);
        }
    }

    private final RtContext ctx;
    private final Bake transmittanceBake;
    private final Bake multiScatterBake;
    private final Bake skyViewBake;
    private final Bake mediumSkyReduceBake;
    private final Bake froxelBake;
    private final Bake visibilityBake;
    private final Bake rainExposureBake;
    private final Bake[] rainHistoryBakes;
    private final Bake cloudNoiseBake;
    private final Bake fogNoiseBake;
    private final Bake cloudWeatherBake;
    private final Bake cloudWarpBake;
    private final Bake cloudShadowBake;
    private final RtOverlayPipelines.AccelStructureSet froxelTlas;
    private final RtOverlayPipelines.AccelStructureSet visibilityTlas;
    private final RtOverlayPipelines.AccelStructureSet rainExposureTlas;
    private final long lutSampler;
    private final long noiseSampler;
    private final long rainHistorySampler;
    private RtImage transmittance;
    private RtImage multiScatter;
    private RtImage skyViewRayleigh;
    private RtImage skyViewMie;
    private RtImage skyViewMulti;
    private RtImage aerialPerspective;
    private RtImage visibilityGrid;
    private RtImage rainExposureDepth;
    private RtImage[] rainWetHistory;
    private int rainWetHistoryRead;
    private int rainWetHistoryResolution;
    private int rainWetHistoryOriginX;
    private int rainWetHistoryOriginZ;
    private boolean rainWetHistoryValid;
    private int rainExposureNearTileCursor;
    private int rainExposureFarTileCursor;
    private int rainExposureTileResolution;
    private int[][] rainExposureTiles = new int[0][2];
    private int rainExposureOriginX;
    private int rainExposureOriginZ;
    private float rainExposureDirectionX;
    private float rainExposureDirectionY;
    private float rainExposureDirectionZ;
    private boolean rainExposureOriginValid;
    private RtBuffer[] rainPrecipitation;
    // Keep unresolved cells explicit: client chunks outside the initial neighbourhood may not exist yet.
    // Near/far tile cursors revisit those cells after streaming catches up instead of permanently caching
    // the false "no rain" answer observed during world entry.
    private final int[] rainPrecipitationCpu = new int[RAIN_PRECIP_MAX_DIM * RAIN_PRECIP_MAX_DIM];
    private final int[] rainPrecipitationScratch = new int[RAIN_PRECIP_MAX_DIM * RAIN_PRECIP_MAX_DIM];
    private final boolean[] rainPrecipitationResolved =
            new boolean[RAIN_PRECIP_MAX_DIM * RAIN_PRECIP_MAX_DIM];
    private final boolean[] rainPrecipitationResolvedScratch =
            new boolean[RAIN_PRECIP_MAX_DIM * RAIN_PRECIP_MAX_DIM];
    private int rainPrecipitationNearTileCursor;
    private int rainPrecipitationFarTileCursor;
    private int[][] rainPrecipitationTiles = new int[0][2];
    private ClientLevel rainPrecipitationCpuLevel;
    private int rainPrecipitationCpuOriginX = Integer.MIN_VALUE;
    private int rainPrecipitationCpuOriginZ = Integer.MIN_VALUE;
    private int rainPrecipitationCpuResolution;
    private long rainPrecipitationGeneration;
    private final long[] rainPrecipitationUploadedGeneration = new long[RAIN_PRECIP_RING];
    private RtImage cloudNoise;
    private RtImage cloudWeather;
    private RtImage cloudWarp;
    private RtImage cloudShadow;
    /** False until the high-cloud patch array has been bound; the bake reads it, so it must wait. */
    private boolean cloudShadowReady;
    private RtImage fogNoise;
    // The interactive water simulation's height field (M12), in metres of displacement. Three buffers
    // rotate through prev/cur/next: leapfrog needs both previous states, so writing next over prev in
    // place would corrupt neighbours a later thread still has to read.
    private RtImage[] waterHeight;
    private RtImage waterObstacle;
    // What the shading samples. Fixed, so binding 18 is written once ever: see water_sim.comp for why
    // rotating it instead would be a descriptor hazard rather than a saving.
    private RtImage waterDisplay;
    /** Must equal WATER_SIM_DIM in water_sim.comp.slang and water.slang. */
    public static final int WATER_SIM_DIM = 256;
    private static final int WATER_SIM_GROUP = 8;
    /** 16-byte header plus six inline 16-byte impulses; inside Vulkan's guaranteed 128. */
    private static final int WATER_SIM_PUSH_BYTES = 128;
    /** Matches WaterDeformPush in water_deform.comp.slang; see that struct for the field order. */
    private static final int WATER_DEFORM_PUSH_BYTES = 64;
    private static final int WATER_DEFORM_GROUP = 64;
    private Bake waterDeformBake;
    // The absolute cell origin each height image's CONTENT was written in, so a re-anchor can be resolved
    // by shifting the reads instead of copying the images. Long.MIN_VALUE = never written, treated as the
    // current origin so the first step reads its (zeroed) self rather than a wild offset.
    private final long[] waterImageCellX = new long[3];
    private final long[] waterImageCellZ = new long[3];
    private boolean waterOriginsSeeded;
    public static final int WATER_MAX_IMPULSES = 6;
    private static final int WATER_OBSTACLE_PUSH_BYTES = 32;
    // One descriptor set per rotation phase, all written once at creation. Phase i reads heights[i] as
    // current and heights[(i+2)%3] as previous, and writes heights[(i+1)%3]. Three fixed sets rather
    // than one set rewritten per frame, for the same reason the display image exists.
    private Bake[] waterSimBakes;
    private Bake waterObstacleBake;
    private RtOverlayPipelines.AccelStructureSet waterObstacleTlas;
    private int waterPhase;
    private boolean waterObstacleReady;
    /** Must equal CLOUD_NOISE_DIM in cloud_field.slang, and numthreads in the bake. */
    private static final int CLOUD_NOISE_DIM = 128;
    /**
     * Full chain, 128 down to 1.
     *
     * <p>The march picks a level from its own step length, so the field is prefiltered to whatever the
     * step is rather than point-sampled at a scale far below it. Costs one eighth of the base level and
     * then a sixty-fourth and so on -- 8/7 of 8 MB, about 1.1 MB.
     */
    private static final int CLOUD_NOISE_LEVELS = 8;
    /** Must equal CLOUD_WEATHER_DIM in cloud_weather.comp.slang. */
    private static final int CLOUD_WEATHER_DIM = 128;
    /** Must equal CLOUD_WARP_DIM in cloud_warp.comp.slang, and numthreads there. */
    private static final int CLOUD_WARP_DIM = 32;
    private static final int CLOUD_WARP_GROUP = 4;
    /**
     * D176's cloud shadow map. Must equal CLOUD_SHADOW_DIM in cloud_density.slang -- the shader owns
     * that constant because the CONSUMERS need it too, and a map baked at one size and read at another
     * would put every cloud shadow somewhere other than under its cloud.
     *
     * <p>512 texels over 8192 blocks is 16 blocks a texel, which is the width of a real cloud shadow's
     * penumbra at a 30-degree sun; R8 makes it 256 KB.
     */
    private static final int CLOUD_SHADOW_DIM = 512;
    private static final int CLOUD_SHADOW_GROUP = 8;
    /** One device address: the same WorldPush the shading reads. See the bake's header. */
    private static final int CLOUD_SHADOW_PUSH_BYTES = 8;
    private static final int CLOUD_NOISE_GROUP = 4;
    /** Must match FOG_NOISE_DIM in fog_noise.comp.slang. */
    private static final int FOG_NOISE_DIM = 128;
    private static final int FOG_NOISE_GROUP = 4;
    private boolean baked;

    private RtSky(RtContext ctx, Bake transmittanceBake, Bake multiScatterBake, Bake skyViewBake,
                  Bake mediumSkyReduceBake, Bake froxelBake,
                  RtOverlayPipelines.AccelStructureSet froxelTlas,
                  Bake visibilityBake, RtOverlayPipelines.AccelStructureSet visibilityTlas,
                  Bake rainExposureBake, Bake[] rainHistoryBakes,
                  RtOverlayPipelines.AccelStructureSet rainExposureTlas,
                  Bake cloudNoiseBake, Bake fogNoiseBake, Bake cloudWeatherBake, Bake cloudWarpBake,
                  Bake cloudShadowBake, long lutSampler, long noiseSampler,
                  long rainHistorySampler) {
        this.ctx = ctx;
        this.transmittanceBake = transmittanceBake;
        this.multiScatterBake = multiScatterBake;
        this.skyViewBake = skyViewBake;
        this.mediumSkyReduceBake = mediumSkyReduceBake;
        this.froxelBake = froxelBake;
        this.froxelTlas = froxelTlas;
        this.visibilityBake = visibilityBake;
        this.rainExposureBake = rainExposureBake;
        this.rainHistoryBakes = rainHistoryBakes;
        this.cloudNoiseBake = cloudNoiseBake;
        this.fogNoiseBake = fogNoiseBake;
        this.cloudWeatherBake = cloudWeatherBake;
        this.cloudWarpBake = cloudWarpBake;
        this.cloudShadowBake = cloudShadowBake;
        this.visibilityTlas = visibilityTlas;
        this.rainExposureTlas = rainExposureTlas;
        this.lutSampler = lutSampler;
        this.noiseSampler = noiseSampler;
        this.rainHistorySampler = rainHistorySampler;
    }

    public static RtSky create(RtContext ctx) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Transmittance: one storage image out, nothing in.
            Bake transmittanceBake = createBake(ctx, stack, "sky_transmittance.comp.spv",
                    "sky transmittance", new int[]{VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}, 0);
            // Multi-scatter: the transmittance table in, one storage image out. Binding order matches
            // sky_multiscatter.comp.slang.
            Bake multiScatterBake = createBake(ctx, stack, "sky_multiscatter.comp.spv",
                    "sky multi-scatter", new int[]{VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                            VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}, 0);
            // Sky view: both earlier tables in, three storage images out, and a push constant for the sun
            // — the field that makes this one a per-frame bake rather than a once-ever one.
            Bake skyViewBake = createBake(ctx, stack, "sky_view.comp.spv", "sky view",
                    new int[]{VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                            VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                            VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                            VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                            VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}, SKY_VIEW_PUSH_BYTES);
            // M16: all three completed sky-view bands in, one phase-integrated upper-hemisphere source
            // written through the WorldPush BDA. One shared field is what prevents air and water from
            // silently acquiring separate fixed-colour or fixed-brightness source paths again.
            Bake mediumSkyReduceBake = createBake(ctx, stack, "sky_medium_reduce.comp.spv",
                    "medium sky reduction",
                    new int[]{VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                            VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                            VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER},
                    MEDIUM_SKY_REDUCE_PUSH_BYTES);
            // Aerial perspective: transmittance in, one 3D image out, and the WorldPush address so the
            // camera, fog parameters and just-reduced medium sky source are read rather than restated.
            // Set 1 is a ring of TLAS-only descriptor sets, borrowed from the overlay passes rather than
            // rebuilt: the handle changes almost every frame, and rewriting one set while an earlier
            // frame may still be reading it is a hazard that already has exactly one correct solution in
            // this codebase.
            RtOverlayPipelines.AccelStructureSet froxelTlas = RtOverlayPipelines.accelStructureSet(
                    ctx, VK10.VK_SHADER_STAGE_COMPUTE_BIT, "sky froxel TLAS");
            Bake froxelBake = createBake(ctx, stack, "sky_froxel.comp.spv", "sky froxel",
                    new int[]{VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                            VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                            VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}, FROXEL_PUSH_BYTES,
                    froxelTlas.layout);

            // LINEAR + CLAMP_TO_EDGE. The multi-scatter bake reads the transmittance table off the
            // horizon-crowded end of its u axis, where nearest sampling shows the texel grid as banding
            // along the one gradient the table exists to get right.
            VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK10.VK_FILTER_LINEAR).minFilter(VK10.VK_FILTER_LINEAR)
                    .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .maxLod(0.0f);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateSampler(vk, sci, null, p), "vkCreateSampler(rt sky lut)");
            long sampler = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SAMPLER, sampler, "sky LUT chain sampler");

            // Packed integer wetness is always read with Load(), but its combined descriptor still owns a
            // sampler. Keep that sampler NEAREST so the descriptor remains valid for an integer format;
            // the LINEAR atmosphere sampler above is not filter-compatible with R32G32_UINT.
            sci.magFilter(VK10.VK_FILTER_NEAREST).minFilter(VK10.VK_FILTER_NEAREST);
            check(VK10.vkCreateSampler(vk, sci, null, p), "vkCreateSampler(rain wet history)");
            long rainHistorySampler = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SAMPLER, rainHistorySampler,
                    "rain wet-history sampler");

            // The two packed density fields are periodic in all three axes. The bake makes their lattice
            // continuous at the seam; REPEAT is still needed so filtering across that seam reads the
            // opposite face instead of clamping to the last texel.
            sci.magFilter(VK10.VK_FILTER_LINEAR).minFilter(VK10.VK_FILTER_LINEAR)
                    .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT);
            check(VK10.vkCreateSampler(vk, sci, null, p), "vkCreateSampler(rt sky noise)");
            long noiseSampler = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SAMPLER, noiseSampler,
                    "sky periodic-noise sampler");

            // The volumetric visibility grid (M13.2). Its own TLAS ring rather than a second bind of the
            // froxel's: a ring slot is claimed per bind, and binding one ring twice a frame is exactly the
            // kind of arithmetic that works right up until the ring wraps.
            // The cloud and fog noises: one storage image out apiece, nothing in. Once-ever like the
            // transmittance table, and for the same reason -- nothing in it depends on the time of day or
            // the camera. It lives on this bake chain rather than in its own object because the chain's
            // whole value is that the ORDER is settled in one place; a second owner would be a second
            // opinion about when things are ready.
            Bake cloudNoiseBake = createBake(ctx, stack, "cloud_noise.comp.spv", "cloud noise",
                    new int[]{VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}, 0);
            Bake fogNoiseBake = createBake(ctx, stack, "fog_noise.comp.spv", "fog noise",
                    new int[]{VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}, 0);
            Bake cloudWeatherBake = createBake(ctx, stack, "cloud_weather.comp.spv", "cloud weather",
                    new int[]{VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}, 0);
            Bake cloudWarpBake = createBake(ctx, stack, "cloud_warp.comp.spv", "cloud warp",
                    new int[]{VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}, 0);
            // The cloud shadow map, and the one bake on this chain that is NOT once-ever: what it
            // integrates moves with the sun. Four fields in, one map out, in the order
            // cloud_shadow.comp.slang binds them -- noise, weather, warp, high-cloud patches, output.
            // The patch array is not this object's to own (RtComposite loads it from a KTX2 asset), so
            // its descriptor arrives later through setHighCloudPatches and the dispatch waits for it.
            Bake cloudShadowBake = createBake(ctx, stack, "cloud_shadow.comp.spv", "cloud shadow",
                    new int[]{VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                              VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                              VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                              VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                              VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}, CLOUD_SHADOW_PUSH_BYTES);

            // One binding: the simulated height. Everything else the displacement needs arrives as a
            // device address in the push constants, because the buffers it writes belong to a terrain
            // section rather than to this chain, and there is one of them per section per frame --
            // descriptor writes at that rate would cost more than the dispatch.
            //
            // COMBINED_IMAGE_SAMPLER, matching Sampler2D on the shader side and writeSampledImage on this
            // one, for the same reason spelled out on the sim bake below.
            Bake waterDeformBake = createBake(ctx, stack, "water_deform.comp.spv", "water deform",
                    new int[]{VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER},
                    WATER_DEFORM_PUSH_BYTES);

            Bake[] waterSimBakes = new Bake[3];
            for (int phase = 0; phase < 3; phase++) {
                waterSimBakes[phase] = createBake(ctx, stack, "water_sim.comp.spv", "water sim " + phase,
                        // COMBINED_IMAGE_SAMPLER, matching Sampler2D on the shader side and
                        // writeSampledImage on this one. Declaring SAMPLED_IMAGE here while writing a
                        // combined descriptor into it is a type mismatch the validation layers do not
                        // always catch and the GPU answers with a device fault.
                        new int[]{VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                                  VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                                  VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                                  VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                                  VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE},
                        WATER_SIM_PUSH_BYTES);
            }
            RtOverlayPipelines.AccelStructureSet waterObstacleTlas = RtOverlayPipelines.accelStructureSet(
                    ctx, VK10.VK_SHADER_STAGE_COMPUTE_BIT, "water obstacle TLAS");
            Bake waterObstacleBake = createBake(ctx, stack, "water_obstacle.comp.spv", "water obstacle",
                    new int[]{VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}, WATER_OBSTACLE_PUSH_BYTES,
                    waterObstacleTlas.layout);

            RtOverlayPipelines.AccelStructureSet visibilityTlas = RtOverlayPipelines.accelStructureSet(
                    ctx, VK10.VK_SHADER_STAGE_COMPUTE_BIT, "volume visibility TLAS");
            Bake visibilityBake = createBake(ctx, stack, "volume_visibility.comp.spv", "volume visibility",
                    new int[]{VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}, VIS_PUSH_BYTES,
                    visibilityTlas.layout);
            RtOverlayPipelines.AccelStructureSet rainExposureTlas = RtOverlayPipelines.accelStructureSet(
                    ctx, VK10.VK_SHADER_STAGE_COMPUTE_BIT, "rain exposure TLAS");
            Bake rainExposureBake = createBake(ctx, stack, "rain_exposure.comp.spv", "rain exposure",
                    new int[]{VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}, RAIN_EXPOSURE_PUSH_BYTES,
                    rainExposureTlas.layout);
            Bake[] rainHistoryBakes = new Bake[2];
            for (int target = 0; target < 2; target++) {
                rainHistoryBakes[target] = createBake(ctx, stack, "rain_history.comp.spv",
                        "rain wet history " + target,
                        new int[]{VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                                VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                                VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER}, RAIN_HISTORY_PUSH_BYTES);
            }

            RtSky sky = new RtSky(ctx, transmittanceBake, multiScatterBake, skyViewBake,
                    mediumSkyReduceBake, froxelBake, froxelTlas,
                    visibilityBake, visibilityTlas, rainExposureBake, rainHistoryBakes, rainExposureTlas,
                    cloudNoiseBake, fogNoiseBake, cloudWeatherBake, cloudWarpBake, cloudShadowBake,
                    sampler, noiseSampler, rainHistorySampler);
            sky.waterSimBakes = waterSimBakes;
            sky.waterDeformBake = waterDeformBake;
            sky.waterObstacleBake = waterObstacleBake;
            sky.waterObstacleTlas = waterObstacleTlas;
            // The images are allocated HERE rather than lazily inside the bake, so the views exist from
            // the moment this object does. Creating them in the bake made a view's availability depend on
            // whether the bake had run, which in turn made the ray-tracing pipeline's binding depend on
            // frame ordering — and the world pipeline is rebuilt on resource reloads, so a pipeline
            // created after the one-and-only bake got no LUT at all and sampled an unbound descriptor.
            // Contents are undefined until the bake, but nothing samples them before then either.
            sky.transmittance = ctx.createStorageImage(TRANSMITTANCE_W, TRANSMITTANCE_H,
                    VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "sky transmittance LUT");
            sky.multiScatter = ctx.createStorageImage(MULTISCATTER_N, MULTISCATTER_N,
                    VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "sky multi-scatter LUT");

            writeStorageImage(vk, stack, transmittanceBake.descriptorSet(), 0, sky.transmittance.view);
            writeSampledImage(vk, stack, multiScatterBake.descriptorSet(), 0, sky.transmittance.view,
                    sampler);
            sky.skyViewRayleigh = ctx.createStorageImage(SKY_VIEW_W, SKY_VIEW_H,
                    VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "sky view Rayleigh LUT");
            sky.skyViewMie = ctx.createStorageImage(SKY_VIEW_W, SKY_VIEW_H,
                    VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "sky view Mie LUT");
            sky.skyViewMulti = ctx.createStorageImage(SKY_VIEW_W, SKY_VIEW_H,
                    VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "sky view multi-scatter LUT");

            writeStorageImage(vk, stack, multiScatterBake.descriptorSet(), 1, sky.multiScatter.view);
            writeSampledImage(vk, stack, skyViewBake.descriptorSet(), 0, sky.transmittance.view, sampler);
            writeSampledImage(vk, stack, skyViewBake.descriptorSet(), 1, sky.multiScatter.view, sampler);
            writeStorageImage(vk, stack, skyViewBake.descriptorSet(), 2, sky.skyViewRayleigh.view);
            writeStorageImage(vk, stack, skyViewBake.descriptorSet(), 3, sky.skyViewMie.view);
            writeStorageImage(vk, stack, skyViewBake.descriptorSet(), 4, sky.skyViewMulti.view);
            writeSampledImage(vk, stack, mediumSkyReduceBake.descriptorSet(), 0,
                    sky.skyViewRayleigh.view, sampler);
            writeSampledImage(vk, stack, mediumSkyReduceBake.descriptorSet(), 1,
                    sky.skyViewMie.view, sampler);
            writeSampledImage(vk, stack, mediumSkyReduceBake.descriptorSet(), 2,
                    sky.skyViewMulti.view, sampler);

            // 128^3 RGBA8, 8 MB. Eight bits per channel because both channels are a density in [0,1]
            // read through a filter -- the octaves are summed at bake time, so nothing here needs range.
            sky.cloudNoise = ctx.createStorageImage3D(CLOUD_NOISE_DIM, CLOUD_NOISE_DIM, CLOUD_NOISE_DIM,
                    VK10.VK_FORMAT_R8G8B8A8_UNORM, "cloud noise volume", CLOUD_NOISE_LEVELS);
            // Mip 0 only: a storage view may name exactly one level. The chain below it is filled by blit
            // after the bake, and the shading reads the full view.
            writeStorageImage(vk, stack, cloudNoiseBake.descriptorSet(), 0, sky.cloudNoise.storageView);
            // R broad variation, G detail, both signed-zero-mean after decode. One RGBA8 volume rather
            // than the old plan's 128^3 + 32^3 pair: one filtered fetch carries both frequencies.
            sky.fogNoise = ctx.createStorageImage3D(FOG_NOISE_DIM, FOG_NOISE_DIM, FOG_NOISE_DIM,
                    VK10.VK_FORMAT_R8G8B8A8_UNORM, "fog noise volume");
            writeStorageImage(vk, stack, fogNoiseBake.descriptorSet(), 0, sky.fogNoise.view);
            // Where cloud systems stand (R) and which family they are (G). 128^2 RG8 is 32 KB, against
            // the 8 MB volume these two fields used to be read out of as fixed horizontal slices -- they
            // have no third dimension, so they were paying a 3D fetch for 2D data twice per density call.
            // Same dimension as the slices it replaces, so the field is the field that shipped.
            sky.cloudWeather = ctx.createStorageImage(CLOUD_WEATHER_DIM, CLOUD_WEATHER_DIM,
                    VK10.VK_FORMAT_R8G8_UNORM, "cloud weather map");
            writeStorageImage(vk, stack, cloudWeatherBake.descriptorSet(), 0, sky.cloudWeather.view);
            // The domain-warp vector field. 32^3 RGBA8 is 128 KB; one filtered fetch replaces six Perlin
            // evaluations, which the march would otherwise pay several times per step.
            sky.cloudWarp = ctx.createStorageImage3D(CLOUD_WARP_DIM, CLOUD_WARP_DIM, CLOUD_WARP_DIM,
                    VK10.VK_FORMAT_R8G8B8A8_UNORM, "cloud warp volume");
            writeStorageImage(vk, stack, cloudWarpBake.descriptorSet(), 0, sky.cloudWarp.view);
            // R8: this is a transmittance, so it is bounded in [0,1] and read through a filter. 512^2
            // is 256 KB. The three fields it marches are bound here once -- they never change identity
            // -- and read through the tiling noise sampler, which is the same sampler the shading uses
            // for them and therefore the same filtering.
            sky.cloudShadow = ctx.createStorageImage(CLOUD_SHADOW_DIM, CLOUD_SHADOW_DIM,
                    VK10.VK_FORMAT_R8_UNORM, "cloud shadow map");
            // CLEARED TO ONE, not to the zero every other storage image here starts at, because this
            // image's zero is not "empty" -- it is FULL SHADOW. Every other bake on this chain writes
            // its whole output before anything samples it; this one waits for a descriptor that arrives
            // from another object, and if that texture ever failed to load the map would stay at its
            // initial value forever. Zero would then black out the world under a clear sky, which is a
            // spectacular symptom for a missing cirrus texture and would be chased anywhere but here.
            // One is clear sky, which is what the absence of a cloud shadow means.
            clearImageToWhite(ctx, sky.cloudShadow);
            writeSampledImage(vk, stack, cloudShadowBake.descriptorSet(), 0, sky.cloudNoise.view,
                    noiseSampler);
            writeSampledImage(vk, stack, cloudShadowBake.descriptorSet(), 1, sky.cloudWeather.view,
                    noiseSampler);
            writeSampledImage(vk, stack, cloudShadowBake.descriptorSet(), 2, sky.cloudWarp.view,
                    noiseSampler);
            writeStorageImage(vk, stack, cloudShadowBake.descriptorSet(), 4, sky.cloudShadow.view);

            // R16F: a displacement in metres, signed, and a ripple's amplitude spans four orders of
            // magnitude between a raindrop and a boat wake -- which is what a float format buys over the
            // R8 the masks use. 256^2 x 2 bytes x 3 is 384 KB.
            sky.waterHeight = new RtImage[3];
            for (int i = 0; i < 3; i++) {
                sky.waterHeight[i] = ctx.createStorageImage(WATER_SIM_DIM, WATER_SIM_DIM,
                        VK10.VK_FORMAT_R16_SFLOAT, "water sim height " + i);
            }
            sky.waterObstacle = ctx.createStorageImage(WATER_SIM_DIM, WATER_SIM_DIM,
                    VK10.VK_FORMAT_R8_UNORM, "water sim obstacle mask");
            sky.waterDisplay = ctx.createStorageImage(WATER_SIM_DIM, WATER_SIM_DIM,
                    VK10.VK_FORMAT_R16_SFLOAT, "water sim height (display)");
            // The DISPLAY image, which is exactly what the shading reads -- so the geometry and the
            // normal cannot be looking at different states of the same field.
            writeSampledImage(vk, stack, sky.waterDeformBake.descriptorSet(), 0,
                    sky.waterDisplay.view, sampler);
            for (int phase = 0; phase < 3; phase++) {
                long set = sky.waterSimBakes[phase].descriptorSet();
                writeSampledImage(vk, stack, set, 0, sky.waterHeight[phase].view, sampler);
                writeSampledImage(vk, stack, set, 1, sky.waterHeight[(phase + 2) % 3].view, sampler);
                writeStorageImage(vk, stack, set, 2, sky.waterHeight[(phase + 1) % 3].view);
                writeSampledImage(vk, stack, set, 3, sky.waterObstacle.view, sampler);
                writeStorageImage(vk, stack, set, 4, sky.waterDisplay.view);
            }
            writeStorageImage(vk, stack, sky.waterObstacleBake.descriptorSet(), 0, sky.waterObstacle.view);

            sky.aerialPerspective = ctx.createStorageImage3D(FROXEL_W, FROXEL_H, FROXEL_D,
                    VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "aerial perspective froxel");
            writeSampledImage(vk, stack, froxelBake.descriptorSet(), 0, sky.transmittance.view, sampler);
            writeSampledImage(vk, stack, froxelBake.descriptorSet(), 1, sky.fogNoise.view, noiseSampler);
            writeStorageImage(vk, stack, froxelBake.descriptorSet(), 2, sky.aerialPerspective.view);

            // Eight bits per channel. R/G are binary visibility before filtering; B/A carry the
            // visibility-weighted unit-square coordinates of the sampled celestial point. Those moments
            // need spatial correlation, not HDR range, and a float format would spend four times the
            // bandwidth for no demonstrated gain.
            //
            // Vulkan's required storage-image format list contains R8G8B8A8_UNORM. Keeping the portable
            // four-channel format also lets the sunrise estimator preserve which emitter coordinates
            // survived visibility filtering without allocating a second image.
            sky.visibilityGrid = ctx.createStorageImage3D(VIS_GRID_W, VIS_GRID_H, VIS_GRID_D,
                    VK10.VK_FORMAT_R8G8B8A8_UNORM, "volume visibility grid");
            writeStorageImage(vk, stack, visibilityBake.descriptorSet(), 0, sky.visibilityGrid.view);
            // One fixed 512² R32 depth image. Low quality dispatches and samples a 256² prefix, so a live
            // option change never rewrites a descriptor still referenced by an in-flight frame.
            sky.rainExposureDepth = ctx.createStorageImage(RAIN_EXPOSURE_MAX, RAIN_EXPOSURE_MAX,
                    VK10.VK_FORMAT_R32_SFLOAT, "directional rain exposure depth");
            writeStorageImage(vk, stack, rainExposureBake.descriptorSet(), 0, sky.rainExposureDepth.view);
            sky.rainWetHistory = new RtImage[2];
            for (int i = 0; i < 2; i++) {
                sky.rainWetHistory[i] = ctx.createStorageImage(RAIN_EXPOSURE_MAX, RAIN_EXPOSURE_MAX,
                        VK10.VK_FORMAT_R32G32_UINT, "rain wet history " + i);
            }
            for (int target = 0; target < 2; target++) {
                Bake history = rainHistoryBakes[target];
                writeSampledImage(vk, stack, history.descriptorSet(), 0,
                        sky.rainWetHistory[1 - target].view, rainHistorySampler);
                writeStorageImage(vk, stack, history.descriptorSet(), 1,
                        sky.rainWetHistory[target].view);
                writeSampledImage(vk, stack, history.descriptorSet(), 2,
                        sky.rainExposureDepth.view, sampler);
            }
            sky.rainPrecipitation = new RtBuffer[RAIN_PRECIP_RING];
            for (int slot = 0; slot < RAIN_PRECIP_RING; slot++) {
                sky.rainPrecipitation[slot] = ctx.createBuffer(
                        (long) RAIN_PRECIP_MAX_DIM * RAIN_PRECIP_MAX_DIM * Integer.BYTES,
                        VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true,
                        "rain precipitation cache " + slot);
            }
            return sky;
        }
    }

    /** The transmittance table's view. Valid from construction; contents defined after the first bake. */
    public long transmittanceView() {
        return transmittance == null ? 0L : transmittance.view;
    }

    /** Shared linear/clamp sampler for consumers of the atmospheric transmittance table. */
    public long transmittanceSampler() {
        return lutSampler;
    }

    /** The multi-scatter table's view. Valid from construction; contents defined after the first bake. */
    public long multiScatterView() {
        return multiScatter == null ? 0L : multiScatter.view;
    }

    public long skyViewRayleighView() {
        return skyViewRayleigh == null ? 0L : skyViewRayleigh.view;
    }

    public long skyViewMieView() {
        return skyViewMie == null ? 0L : skyViewMie.view;
    }

    public long skyViewMultiView() {
        return skyViewMulti == null ? 0L : skyViewMulti.view;
    }

    public long aerialPerspectiveView() {
        return aerialPerspective == null ? 0L : aerialPerspective.view;
    }

    /** M13.2 visibility grid: R sun, G sky, BA visibility-weighted celestial sample coordinates. */
    public long visibilityGridView() {
        return visibilityGrid == null ? 0L : visibilityGrid.view;
    }

    /** D105 directional depth map shared by every opaque path vertex. */
    public long rainExposureDepthView() {
        return rainExposureDepth == null ? 0L : rainExposureDepth.view;
    }

    /** Current persistent wet/puddle history sampled by both ray-generation passes. */
    public long rainWetHistoryView() {
        return rainWetHistory == null ? 0L : rainWetHistory[rainWetHistoryRead].view;
    }

    public long rainWetHistoryView(int index) {
        return rainWetHistory == null ? 0L : rainWetHistory[Math.clamp(index, 0, 1)].view;
    }

    /** NEAREST is required by the packed R32G32_UINT history descriptor. */
    public long rainWetHistorySampler() {
        return rainHistorySampler;
    }

    /**
     * The precipitation classes for one ring slot, as a device address.
     *
     * <p>Read by four things in three pipelines: the exposure bake, the wet history, the streak lighting
     * and the shading's wetness funnel. They cannot share a descriptor set, so the buffer travels by
     * address and precipitation.slang owns the decoding.
     */
    public long rainPrecipitationAddress(int slot) {
        return rainPrecipitation == null || slot < 0 || slot >= rainPrecipitation.length
                ? 0L : rainPrecipitation[slot].deviceAddress;
    }

    /** Index this frame's history compute will publish; written into rainPuddle.z before dispatch. */
    public int rainWetHistoryTargetIndex() {
        return 1 - rainWetHistoryRead;
    }

    /**
     * Slide the one-cell-per-four-block precipitation cache with the world map, then resolve loaded
     * cells in the same near-first/far-progressive order as the GPU exposure bake. Cells whose chunks
     * have not streamed in remain UNKNOWN and are revisited; they must never be frozen as dry merely
     * because the first client frame arrived before the chunk.
     */
    public void updateRainPrecipitation(ClientLevel level, int originX, int originZ,
                                        int resolution, int slot) {
        if (level == null || rainPrecipitation == null || resolution <= 0
                || slot < 0 || slot >= rainPrecipitation.length) {
            return;
        }
        int coarseDim = resolution / RAIN_PRECIP_CELL;
        boolean mapChanged = level != rainPrecipitationCpuLevel || originX != rainPrecipitationCpuOriginX
                || originZ != rainPrecipitationCpuOriginZ
                || resolution != rainPrecipitationCpuResolution;
        if (mapChanged) {
            boolean canSlide = level == rainPrecipitationCpuLevel
                    && resolution == rainPrecipitationCpuResolution
                    && rainPrecipitationCpuOriginX != Integer.MIN_VALUE
                    && rainPrecipitationCpuOriginZ != Integer.MIN_VALUE
                    && (originX - rainPrecipitationCpuOriginX) % RAIN_PRECIP_CELL == 0
                    && (originZ - rainPrecipitationCpuOriginZ) % RAIN_PRECIP_CELL == 0;
            int shiftX = canSlide
                    ? (originX - rainPrecipitationCpuOriginX) / RAIN_PRECIP_CELL : coarseDim;
            int shiftZ = canSlide
                    ? (originZ - rainPrecipitationCpuOriginZ) / RAIN_PRECIP_CELL : coarseDim;
            canSlide &= Math.abs(shiftX) < coarseDim && Math.abs(shiftZ) < coarseDim;

            for (int z = 0; z < coarseDim; z++) {
                for (int x = 0; x < coarseDim; x++) {
                    int oldX = x + shiftX;
                    int oldZ = z + shiftZ;
                    int index = z * coarseDim + x;
                    if (canSlide && oldX >= 0 && oldX < coarseDim
                            && oldZ >= 0 && oldZ < coarseDim) {
                        int oldIndex = oldZ * coarseDim + oldX;
                        rainPrecipitationScratch[index] = rainPrecipitationCpu[oldIndex];
                        rainPrecipitationResolvedScratch[index] = rainPrecipitationResolved[oldIndex];
                    } else {
                        rainPrecipitationScratch[index] = RAIN_PRECIP_UNKNOWN;
                        rainPrecipitationResolvedScratch[index] = false;
                    }
                }
            }
            System.arraycopy(rainPrecipitationScratch, 0, rainPrecipitationCpu, 0,
                    coarseDim * coarseDim);
            System.arraycopy(rainPrecipitationResolvedScratch, 0, rainPrecipitationResolved, 0,
                    coarseDim * coarseDim);
            rainPrecipitationCpuLevel = level;
            rainPrecipitationCpuOriginX = originX;
            rainPrecipitationCpuOriginZ = originZ;
            rainPrecipitationCpuResolution = resolution;
            rainPrecipitationNearTileCursor = 0;
            rainPrecipitationFarTileCursor = 0;
            rainPrecipitationTiles = rainExposureTileOrder(resolution);
            rainPrecipitationGeneration++;
        }
        long queries = refreshRainPrecipitationTiles(level, originX, originZ, resolution, coarseDim);
        if (queries > 0L) {
            rainPrecipitationGeneration++;
        }
        RtFrameStats.FRAME.count("rainExposureCpuQueries", queries);
        if (rainPrecipitationUploadedGeneration[slot] == rainPrecipitationGeneration) {
            return;
        }
        RtBuffer cache = rainPrecipitation[slot];
        int cellCount = coarseDim * coarseDim;
        MemoryUtil.memIntBuffer(cache.mapped, cellCount).put(rainPrecipitationCpu, 0, cellCount);
        cache.flush(0L, (long) cellCount * Integer.BYTES);
        RtFrameStats.FRAME.count("rainExposureUploadBytes", (long) cellCount * Integer.BYTES);
        rainPrecipitationUploadedGeneration[slot] = rainPrecipitationGeneration;
    }

    /** Resolve only loaded chunks; unresolved cells stay queued until a later near/far visit. */
    private long refreshRainPrecipitationTiles(ClientLevel level, int originX, int originZ,
                                               int resolution, int coarseDim) {
        int tileCount = rainPrecipitationTiles.length;
        if (tileCount == 0) {
            return 0L;
        }
        long queries = 0L;
        int nearCount = Math.min(RAIN_EXPOSURE_NEAR_TILES, tileCount);
        int nearWork = Math.min(RAIN_EXPOSURE_NEAR_TILES_PER_FRAME, nearCount);
        for (int work = 0; work < nearWork; work++) {
            int[] tile = rainPrecipitationTiles[rainPrecipitationNearTileCursor++ % nearCount];
            queries += refreshRainPrecipitationTile(level, originX, originZ, resolution, coarseDim, tile);
        }
        int farCount = tileCount - nearCount;
        int farWork = Math.min(RAIN_EXPOSURE_TILES_PER_FRAME - nearWork, farCount);
        for (int work = 0; work < farWork; work++) {
            int[] tile = rainPrecipitationTiles[
                    nearCount + rainPrecipitationFarTileCursor++ % farCount];
            queries += refreshRainPrecipitationTile(level, originX, originZ, resolution, coarseDim, tile);
        }
        return queries;
    }

    private long refreshRainPrecipitationTile(ClientLevel level, int originX, int originZ,
                                              int resolution, int coarseDim, int[] tile) {
        int extentX = Math.min(RAIN_EXPOSURE_TILE, resolution - tile[0]);
        int extentZ = Math.min(RAIN_EXPOSURE_TILE, resolution - tile[1]);
        int startX = tile[0] / RAIN_PRECIP_CELL;
        int startZ = tile[1] / RAIN_PRECIP_CELL;
        int endX = (tile[0] + extentX + RAIN_PRECIP_CELL - 1) / RAIN_PRECIP_CELL;
        int endZ = (tile[1] + extentZ + RAIN_PRECIP_CELL - 1) / RAIN_PRECIP_CELL;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        long queries = 0L;
        for (int z = startZ; z < endZ; z++) {
            for (int x = startX; x < endX; x++) {
                int index = z * coarseDim + x;
                if (rainPrecipitationResolved[index]) {
                    continue;
                }
                int worldX = originX + x * RAIN_PRECIP_CELL + RAIN_PRECIP_CELL / 2;
                int worldZ = originZ + z * RAIN_PRECIP_CELL + RAIN_PRECIP_CELL / 2;
                if (!level.getChunkSource().hasChunk(worldX >> 4, worldZ >> 4)) {
                    continue;
                }
                // Height is sampled at the motion-blocking surface because rain/snow may depend on local
                // altitude; a fixed sea-level query would turn mountain snow into rain.
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, worldX, worldZ);
                pos.set(worldX, y, worldZ);
                Biome.Precipitation falling = level.getPrecipitationAt(pos);
                // Queried at the MOTION_BLOCKING surface, which is what puts a snow line on a mountain:
                // Minecraft lowers a biome's temperature with altitude, so one biome rains at sea level
                // and snows on its peaks. Reading a fixed sea-level height here would have made a whole
                // region one or the other.
                rainPrecipitationCpu[index] = switch (falling) {
                    case RAIN -> RAIN_PRECIP_RAIN;
                    case SNOW -> RAIN_PRECIP_SNOW;
                    default -> RAIN_PRECIP_NONE;
                };
                rainPrecipitationResolved[index] = true;
                queries++;
            }
        }
        return queries;
    }

    /** M11.1 cloud noise: R the billow that shapes a cloud, G the detail that erodes its edges. */
    public long cloudNoiseView() {
        return cloudNoise == null ? 0L : cloudNoise.view;
    }

    /** The domain-warp vector field, curl noise encoded signed into RGB. */
    public long cloudWarpView() {
        return cloudWarp == null ? 0L : cloudWarp.view;
    }

    public long cloudShadowView() {
        return cloudShadow == null ? 0L : cloudShadow.view;
    }

    /**
     * Hand the shadow bake the high-cloud patch array, which RtComposite owns.
     *
     * <p>The bake cannot run until this arrives: it integrates the high sheet as well as the low deck,
     * and an unwritten combined-image descriptor is not a zero, it is undefined behaviour. Idempotent,
     * because the caller rebinds on every resource reload.
     */
    public void setHighCloudPatches(long imageView, long sampler) {
        if (imageView == 0L || sampler == 0L) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            writeSampledImage(ctx.vk(), stack, cloudShadowBake.descriptorSet(), 3, imageView, sampler);
        }
        cloudShadowReady = true;
    }

    /** The weather map: R where cloud systems stand, G which family they are. */
    public long cloudWeatherView() {
        return cloudWeather == null ? 0L : cloudWeather.view;
    }

    /** M13 packed heterogeneous fog: R broad variation, G detail, both mean-zero after decode. */
    public long fogNoiseView() {
        return fogNoise == null ? 0L : fogNoise.view;
    }

    /**
     * Displace one section's water vertices onto the wave surface, and record the barrier that lets the
     * BLAS refit read them.
     *
     * <p>ORDER IS THE WHOLE RISK HERE. The dispatch writes the vertex buffer and the refit reads it back
     * as an acceleration-structure build input; without the barrier between them the refit is free to
     * consume last frame's positions, and per F24 nothing in Vulkan will say so -- no error, no device
     * loss, just geometry that is quietly wrong. The barrier is recorded here, beside the dispatch, so it
     * cannot be forgotten by a caller that only wanted to move some vertices.
     *
     * <p>Caller records the refit itself, after this returns, because the BLAS and its scratch belong to
     * the terrain.
     */
    public void recordWaterDeform(VkCommandBuffer cmd, long positionsAddr, long restAddr,
                                  long worldPushAddr, int vertBase, int vertCount,
                                  float worldOffsetX, float worldOffsetZ,
                                  float fadeCentreX, float fadeCentreZ,
                                  float fadeStart, float fadeEnd, float cellSize) {
        if (waterDeformBake == null || vertCount <= 0) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "water deform")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, waterDeformBake.pipeline());
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    waterDeformBake.pipelineLayout(), 0,
                    stack.longs(waterDeformBake.descriptorSet()), null);
            ByteBuffer push = stack.malloc(WATER_DEFORM_PUSH_BYTES);
            push.putLong(0, positionsAddr).putLong(8, restAddr).putLong(16, worldPushAddr);
            push.putFloat(24, worldOffsetX).putFloat(28, worldOffsetZ);
            push.putFloat(32, fadeCentreX).putFloat(36, fadeCentreZ);
            push.putFloat(40, fadeStart).putFloat(44, fadeEnd).putFloat(48, cellSize);
            push.putInt(52, vertBase).putInt(56, vertCount).putInt(60, 0);
            VK10.vkCmdPushConstants(cmd, waterDeformBake.pipelineLayout(),
                    VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, (vertCount + WATER_DEFORM_GROUP - 1) / WATER_DEFORM_GROUP, 1, 1);
        }
    }

    /**
     * The one barrier between every displacement recorded this frame and the refits that read them.
     *
     * <p>Once for the whole batch rather than once per section: they all write disjoint buffers and all
     * feed the same later stage, so a barrier apiece would serialise dispatches that have no reason to
     * wait for each other.
     */
    public static void recordWaterDeformBarrier(VkCommandBuffer cmd) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
            barrier.get(0).sType$Default()
                    .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT);
            VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    org.lwjgl.vulkan.KHRAccelerationStructure
                            .VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    0, barrier, null, null);
        }
    }

    /** Beyond a whole grid there is no overlap left, so saturate rather than wrap. */
    private static int clampCellShift(long delta) {
        return (int) Math.max(-WATER_SIM_DIM, Math.min(WATER_SIM_DIM, delta));
    }

    /** The height field the shading samples. Fixed; the rotation happens behind it. */
    public long waterHeightView() {
        return waterDisplay == null ? 0L : waterDisplay.view;
    }

    /**
     * Record the volumetric visibility grid. Per frame, and unlike the froxel it does not follow the
     * camera's ORIENTATION — only its position, snapped to whole cells — so turning on the spot rebakes an
     * identical field. That stability under rotation is something a view-space structure cannot have, and
     * it is why the bounce segments get a grid of their own rather than a second read of the froxel.
     *
     * <p>Takes only the WorldPush address. The grid's origin and cell size are fields in that buffer
     * because the consumers read them too, and the one thing that must not drift between the shader that
     * writes a cell and the shader that reads it is where the cell IS.
     */
    public void recordVisibilityBake(VkCommandBuffer cmd, long worldPushAddr, long tlas,
                                     RtGpuExecutor.GraphicsUse graphicsUse) {
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "volume visibility bake")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, visibilityBake.pipeline());
            long tlasSet = visibilityTlas.bind(ctx, tlas, graphicsUse);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    visibilityBake.pipelineLayout(), 0,
                    stack.longs(visibilityBake.descriptorSet(), tlasSet), null);
            ByteBuffer pushData = stack.malloc(VIS_PUSH_BYTES);
            pushData.putLong(0, worldPushAddr);
            VK10.vkCmdPushConstants(cmd, visibilityBake.pipelineLayout(),
                    VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, pushData);
            // One thread per CELL. Unlike the froxel there is nothing to accumulate along an axis, so no
            // thread owns a column and a flat 3D dispatch is the honest shape.
            VK10.vkCmdDispatch(cmd, (VIS_GRID_W + VIS_GROUP - 1) / VIS_GROUP,
                    (VIS_GRID_H + VIS_GROUP - 1) / VIS_GROUP,
                    (VIS_GRID_D + VIS_GROUP - 1) / VIS_GROUP);
        }
    }

    /**
     * Record D105's directional depth after the frame TLAS is complete. The centre 32x32 tiles are
     * always first; eight tiles advance per frame, so the player's neighbourhood refreshes immediately
     * while the far square converges without a 512^2 burst.
     */
    public void recordRainExposureBake(VkCommandBuffer cmd, long worldPushAddr, long sectionTableAddr,
                                       long tlas, int originX, int originZ, int resolution,
                                       float directionX, float directionY, float directionZ, int slot,
                                       RtGpuExecutor.GraphicsUse graphicsUse) {
        if (resolution <= 0 || rainPrecipitation == null
                || slot < 0 || slot >= rainPrecipitation.length) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "rain exposure bake")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, rainExposureBake.pipeline());
            long tlasSet = rainExposureTlas.bind(ctx, tlas, graphicsUse);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    rainExposureBake.pipelineLayout(), 0,
                    stack.longs(rainExposureBake.descriptorSet(), tlasSet), null);
            boolean directionChanged = rainExposureOriginValid
                    && (Math.abs(rainExposureDirectionX - directionX) > 1.0e-5f
                    || Math.abs(rainExposureDirectionY - directionY) > 1.0e-5f
                    || Math.abs(rainExposureDirectionZ - directionZ) > 1.0e-5f);
            boolean moved = !rainExposureOriginValid || rainExposureTileResolution != resolution
                    || rainExposureOriginX != originX || rainExposureOriginZ != originZ
                    || directionChanged;
            if (moved) {
                rainExposureTileResolution = resolution;
                rainExposureOriginX = originX;
                rainExposureOriginZ = originZ;
                rainExposureDirectionX = directionX;
                rainExposureDirectionY = directionY;
                rainExposureDirectionZ = directionZ;
                rainExposureOriginValid = true;
                rainExposureNearTileCursor = 0;
                rainExposureFarTileCursor = 0;
                rainExposureTiles = rainExposureTileOrder(resolution);
                clearRainExposure(cmd, stack);
                if (directionChanged) {
                    // Packed depths are measured along the old ray. They cannot be reprojected to a new
                    // slant/heading by shifting xz, so start the local association from global reservoirs.
                    rainWetHistoryValid = false;
                }
            }
            int tileCount = rainExposureTiles.length;
            int nearCount = Math.min(RAIN_EXPOSURE_NEAR_TILES, tileCount);
            int nearWork = Math.min(RAIN_EXPOSURE_NEAR_TILES_PER_FRAME, nearCount);
            for (int work = 0; work < nearWork; work++) {
                int[] tile = rainExposureTiles[rainExposureNearTileCursor++ % nearCount];
                dispatchRainExposureTile(cmd, stack, worldPushAddr, sectionTableAddr,
                        resolution, slot, tile);
            }
            int farCount = tileCount - nearCount;
            int farWork = Math.min(RAIN_EXPOSURE_TILES_PER_FRAME - nearWork, farCount);
            for (int work = 0; work < farWork; work++) {
                int[] tile = rainExposureTiles[nearCount + rainExposureFarTileCursor++ % farCount];
                dispatchRainExposureTile(cmd, stack, worldPushAddr, sectionTableAddr,
                        resolution, slot, tile);
            }
        }
    }

    private void dispatchRainExposureTile(VkCommandBuffer cmd, MemoryStack stack, long worldPushAddr,
                                          long sectionTableAddr, int resolution, int slot, int[] tile) {
                int extentX = Math.min(RAIN_EXPOSURE_TILE, resolution - tile[0]);
                int extentY = Math.min(RAIN_EXPOSURE_TILE, resolution - tile[1]);
                ByteBuffer push = stack.malloc(RAIN_EXPOSURE_PUSH_BYTES);
                push.putLong(0, worldPushAddr)
                        .putLong(8, rainPrecipitation[slot].deviceAddress)
                        .putLong(16, sectionTableAddr);
                push.putInt(24, resolution).putInt(28, resolution / RAIN_PRECIP_CELL);
                push.putInt(32, tile[0]).putInt(36, tile[1]);
                push.putInt(40, extentX).putInt(44, extentY);
                VK10.vkCmdPushConstants(cmd, rainExposureBake.pipelineLayout(),
                        VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
                VK10.vkCmdDispatch(cmd, (extentX + RAIN_EXPOSURE_GROUP - 1) / RAIN_EXPOSURE_GROUP,
                        (extentY + RAIN_EXPOSURE_GROUP - 1) / RAIN_EXPOSURE_GROUP, 1);
    }

    /** Drop texels from the previous world anchor before the centre-out refresh begins. */
    private void clearRainExposure(VkCommandBuffer cmd, MemoryStack stack) {
        VkMemoryBarrier.Buffer before = VkMemoryBarrier.calloc(1, stack);
        before.get(0).sType$Default()
                .srcAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT);
        VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, 0, before, null, null);
        VkClearColorValue clear = VkClearColorValue.calloc(stack);
        // -2 means "not refreshed for this anchor yet". -1 is reserved for a resolved no-rain biome;
        // rain_history uses that distinction to retain rather than prematurely dry distant history.
        clear.float32(0, -2.0f);
        VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
        range.get(0).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        VK10.vkCmdClearColorImage(cmd, rainExposureDepth.image,
                VK10.VK_IMAGE_LAYOUT_GENERAL, clear, range);
        VkMemoryBarrier.Buffer after = VkMemoryBarrier.calloc(1, stack);
        after.get(0).sType$Default().srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
        VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, after, null, null);
    }

    /** Centre-out square-ring tile order; rebuilt only when the quality tier changes. */
    private static int[][] rainExposureTileOrder(int resolution) {
        int dim = (resolution + RAIN_EXPOSURE_TILE - 1) / RAIN_EXPOSURE_TILE;
        int[][] result = new int[dim * dim][2];
        int centre = dim / 2;
        int out = 0;
        for (int radius = 0; radius <= dim; radius++) {
            for (int y = 0; y < dim; y++) {
                for (int x = 0; x < dim; x++) {
                    if (Math.max(Math.abs(x - centre), Math.abs(y - centre)) == radius) {
                        result[out][0] = x * RAIN_EXPOSURE_TILE;
                        result[out][1] = y * RAIN_EXPOSURE_TILE;
                        out++;
                    }
                }
            }
        }
        return result;
    }

    /** Update and swap the compressed two-layer world-column wetness history. */
    public void recordRainHistory(VkCommandBuffer cmd, long worldPushAddr, int slot,
                                  int originX, int originZ, int resolution,
                                  float elapsedSeconds, float wetFillSeconds, float wetDrySeconds,
                                  float puddleFillSeconds, float puddleDrySeconds, float dryingScale) {
        if (resolution <= 0 || rainWetHistory == null) {
            return;
        }
        int shiftX = rainWetHistoryValid ? originX - rainWetHistoryOriginX : 0;
        int shiftZ = rainWetHistoryValid ? originZ - rainWetHistoryOriginZ : 0;
        boolean clear = !rainWetHistoryValid || rainWetHistoryResolution != resolution
                || Math.abs(shiftX) >= resolution || Math.abs(shiftZ) >= resolution;
        int write = 1 - rainWetHistoryRead;
        Bake rainHistoryBake = rainHistoryBakes[write];
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "rain wet history")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, rainHistoryBake.pipeline());
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    rainHistoryBake.pipelineLayout(), 0, stack.longs(rainHistoryBake.descriptorSet()), null);
            ByteBuffer push = stack.malloc(RAIN_HISTORY_PUSH_BYTES);
            // The precipitation class rides beside the WorldPush address, and every offset below moved
            // eight bytes for it. This reservoir keys on "does this column have an exposure depth", and
            // snow now has one -- so without the class a blizzard would fill the puddles.
            push.putLong(0, worldPushAddr).putLong(8, rainPrecipitationAddress(slot));
            push.putInt(16, resolution).putInt(20, clear ? 1 : 0);
            push.putFloat(24, elapsedSeconds).putFloat(28, wetFillSeconds)
                    .putFloat(32, wetDrySeconds).putFloat(36, puddleFillSeconds)
                    .putFloat(40, puddleDrySeconds).putFloat(44, dryingScale);
            push.putInt(48, shiftX).putInt(52, shiftZ).putInt(56, 0).putInt(60, 0);
            VK10.vkCmdPushConstants(cmd, rainHistoryBake.pipelineLayout(),
                    VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, (resolution + RAIN_EXPOSURE_GROUP - 1) / RAIN_EXPOSURE_GROUP,
                    (resolution + RAIN_EXPOSURE_GROUP - 1) / RAIN_EXPOSURE_GROUP, 1);
        }
        rainWetHistoryRead = write;
        rainWetHistoryResolution = resolution;
        rainWetHistoryOriginX = originX;
        rainWetHistoryOriginZ = originZ;
        rainWetHistoryValid = true;
    }

    /**
     * Record the aerial-perspective froxel. Per frame, like the sky-view table and for a stronger reason:
     * this one follows the camera, not just the sun.
     *
     * <p>Takes the WorldPush device address rather than a wall of camera and fog parameters. That buffer
     * already holds the inverse view-projection the primary rays are built from and all four of the fog's
     * parameter vectors, and a froxel column that disagreed with the pixels above it — by half a tile, or
     * by a stale density — would read as the fog sliding over the geometry as the camera turns.
     */
    public void recordFroxelBake(VkCommandBuffer cmd, long worldPushAddr,
                                 long lightBufAddr, long lightAliasAddr, long lightLocalAliasAddr,
                                 long lightGridCellAddr, long lightGridSpanAddr, long tlas,
                                 RtGpuExecutor.GraphicsUse graphicsUse) {
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "aerial perspective bake")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, froxelBake.pipeline());
            long tlasSet = froxelTlas.bind(ctx, tlas, graphicsUse);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    froxelBake.pipelineLayout(), 0,
                    stack.longs(froxelBake.descriptorSet(), tlasSet), null);
            ByteBuffer pushData = stack.malloc(FROXEL_PUSH_BYTES);
            pushData.putLong(0, worldPushAddr);
            pushData.putLong(8, lightBufAddr);
            pushData.putLong(16, lightAliasAddr);
            pushData.putLong(24, lightLocalAliasAddr);
            pushData.putLong(32, lightGridCellAddr);
            pushData.putLong(40, lightGridSpanAddr);
            VK10.vkCmdPushConstants(cmd, froxelBake.pipelineLayout(),
                    VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, pushData);
            // One thread per COLUMN, not per froxel: each walks all 64 (FROXEL_D) slices and writes them as it goes.
            // With a shadow ray at every step, the per-froxel shape would trace the same ray once for
            // every cell behind it.
            VK10.vkCmdDispatch(cmd, (FROXEL_W + FROXEL_GROUP - 1) / FROXEL_GROUP,
                    (FROXEL_H + FROXEL_GROUP - 1) / FROXEL_GROUP, 1);
        }
    }

    /**
     * Record the sky-view bake. Unlike everything else here this runs EVERY FRAME, and the distinction is
     * the point rather than an inconsistency: the sun's direction enters this integral where the table is
     * baked, not where it is sampled, so a once-ever bake would freeze the sky at whatever time the world
     * happened to load. The two tables it reads genuinely do not depend on the time of day, which is why
     * they are baked once and this one cannot be.
     *
     * <p>The following reduction is part of this operation: it consumes all 24576 texels and writes the
     * one shared medium source before the froxel. Keeping both dispatches in one method makes their two
     * compute barriers an invariant instead of a caller convention.
     */
    public void recordSkyViewBake(VkCommandBuffer cmd, float sunX, float sunY, float sunZ,
                                   float[] tint, float intensity, long worldPushAddr) {
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "sky view bake")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, skyViewBake.pipeline());
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    skyViewBake.pipelineLayout(), 0, stack.longs(skyViewBake.descriptorSet()), null);
            ByteBuffer pushData = stack.malloc(SKY_VIEW_PUSH_BYTES);
            pushData.putFloat(0, sunX).putFloat(4, sunY).putFloat(8, sunZ).putFloat(12, 0.0f);
            // The sky's art tint rides the bake rather than the sample: 24576 texels once per frame
            // instead of one multiply per escaping ray, and rmiss does not have to know it exists.
            pushData.putFloat(16, tint[0] * intensity).putFloat(20, tint[1] * intensity)
                    .putFloat(24, tint[2] * intensity).putFloat(28, 0.0f);
            VK10.vkCmdPushConstants(cmd, skyViewBake.pipelineLayout(),
                    VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, pushData);
            VK10.vkCmdDispatch(cmd, (SKY_VIEW_W + BAKE_GROUP - 1) / BAKE_GROUP,
                    (SKY_VIEW_H + BAKE_GROUP - 1) / BAKE_GROUP, 1);

            // The reduction reads all three images just written above. Keep this barrier inside the LUT
            // owner: exposing the intermediate ordering to RtComposite would let a future caller sample a
            // half-written sky and produce a one-frame medium flash without any validation error.
            shaderWriteToComputeReadBarrier(cmd, stack);
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    mediumSkyReduceBake.pipeline());
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    mediumSkyReduceBake.pipelineLayout(), 0,
                    stack.longs(mediumSkyReduceBake.descriptorSet()), null);
            ByteBuffer reducePush = stack.malloc(MEDIUM_SKY_REDUCE_PUSH_BYTES);
            reducePush.putLong(0, worldPushAddr).putLong(8, 0L);
            reducePush.putFloat(16, sunX).putFloat(20, sunY).putFloat(24, sunZ).putFloat(28, 0.0f);
            VK10.vkCmdPushConstants(cmd, mediumSkyReduceBake.pipelineLayout(),
                    VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, reducePush);
            // Exactly one 256-lane group; each lane folds a strided subset of the full 192x128 table.
            VK10.vkCmdDispatch(cmd, 1, 1, 1);
            // The froxel is the immediate consumer of mediumSkyRadiance. The later generic bake barrier
            // protects ray tracing, but it is too late for this compute-to-compute dependency.
            shaderWriteToComputeReadBarrier(cmd, stack);
        }
    }

    /**
     * Record D176's cloud shadow map. EVERY FRAME, like the sky view above and unlike everything on the
     * once-ever chain, and for the same kind of reason: the sun's direction enters this integral where
     * the map is baked rather than where it is sampled.
     *
     * <p>It is worth being precise about which motion forces the rate, because "clouds move slowly" is
     * the intuition that would argue for baking it rarely. Cloud advection alone would take about
     * sixteen seconds to carry a shadow across one 16-block texel. The SUN carries it across in 1.7 --
     * a shadow crosses the ground at roughly nine blocks a second — so the map is stale within two
     * frames of the sun moving, not within two seconds. Baking it every frame costs about a hundredth
     * of what the primary trace already spends and removes the entire question of how old the texture
     * is; the bake is deterministic and unjittered, so there is nothing a temporal filter would add.
     *
     * <p>Returns false when the map was not written, which is not an error: the high-cloud patch array
     * is loaded from a resource pack and may not have been bound yet. The caller keeps whatever it had.
     */
    public boolean recordCloudShadowBake(VkCommandBuffer cmd, long worldPushAddr) {
        if (!cloudShadowReady || cloudShadowBake == null || worldPushAddr == 0L) {
            return false;
        }
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "cloud shadow bake")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, cloudShadowBake.pipeline());
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    cloudShadowBake.pipelineLayout(), 0, stack.longs(cloudShadowBake.descriptorSet()),
                    null);
            ByteBuffer push = stack.malloc(CLOUD_SHADOW_PUSH_BYTES);
            push.putLong(0, worldPushAddr);
            VK10.vkCmdPushConstants(cmd, cloudShadowBake.pipelineLayout(),
                    VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            int groups = (CLOUD_SHADOW_DIM + CLOUD_SHADOW_GROUP - 1) / CLOUD_SHADOW_GROUP;
            VK10.vkCmdDispatch(cmd, groups, groups, 1);
        }
        return true;
    }

    /**
     * Record the bakes if they have not been recorded yet. Returns true when work was added to
     * {@code cmd}, so the caller knows a barrier is needed before anything samples the tables.
     *
     * <p>Idempotent by design: the caller may invoke this every frame and it does nothing after the
     * first. Making the no-op the caller's normal path — rather than asking every call site to remember
     * whether the sky has been built — is what keeps a future second call site from quietly rebaking.
     *
     * <p>The barrier BETWEEN the two dispatches is not optional and is not the caller's business: the
     * second bake samples what the first wrote. It lives here because the chain lives here.
     */
    public boolean recordBakeIfNeeded(VkCommandBuffer cmd) {
        if (baked) {
            return false;
        }
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "sky LUT bake")) {
            dispatch(cmd, stack, transmittanceBake, TRANSMITTANCE_W, TRANSMITTANCE_H);
            // The multi-scatter bake reads every texel the transmittance bake just wrote.
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
            barrier.get(0).sType$Default()
                    .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT);
            VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, barrier, null, null);
            dispatch(cmd, stack, multiScatterBake, MULTISCATTER_N, MULTISCATTER_N);
            // The two noise bakes share this once-ever pass but nothing in the chain above it: no barrier
            // between them because neither reads what another bake writes.
            dispatch3D(cmd, stack, cloudNoiseBake,
                    CLOUD_NOISE_DIM, CLOUD_NOISE_DIM, CLOUD_NOISE_DIM, CLOUD_NOISE_GROUP);
            generateMips3D(cmd, stack, cloudNoise, CLOUD_NOISE_DIM);
            dispatch3D(cmd, stack, fogNoiseBake,
                    FOG_NOISE_DIM, FOG_NOISE_DIM, FOG_NOISE_DIM, FOG_NOISE_GROUP);
            // Reads nothing any other bake writes, so it joins them with no barrier.
            dispatch(cmd, stack, cloudWeatherBake, CLOUD_WEATHER_DIM, CLOUD_WEATHER_DIM);
            dispatch3D(cmd, stack, cloudWarpBake,
                    CLOUD_WARP_DIM, CLOUD_WARP_DIM, CLOUD_WARP_DIM, CLOUD_WARP_GROUP);
        }
        baked = true;
        return true;
    }

    /**
     * One simulation step, plus the obstacle refresh when the domain has moved or the terrain changed.
     *
     * <p>The obstacle mask is PERSISTENT and refreshed only on those two events (D40), so standing still
     * costs nothing at all and walking costs one full refresh per re-anchor rather than one per frame.
     *
     * @param originX  rebased world X of cell (0,0)'s centre, already snapped to whole cells
     * @param reanchor true when the domain moved this frame, or the terrain changed under it
     */
    public void recordWaterSim(VkCommandBuffer cmd, long tlas, RtGpuExecutor.GraphicsUse graphicsUse,
                               float originX, float originZ, float cellSize, float surfaceY,
                               float courant2, float damping, float spongeWidth,
                               float[] impulses, int impulseCount, boolean reanchor,
                               long domainCellX, long domainCellZ) {
        if (waterSimBakes == null) {
            return;
        }
        if (!waterOriginsSeeded) {
            java.util.Arrays.fill(waterImageCellX, domainCellX);
            java.util.Arrays.fill(waterImageCellZ, domainCellZ);
            waterOriginsSeeded = true;
        }
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "water sim")) {
            Bake step = waterSimBakes[waterPhase];
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, step.pipeline());
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    step.pipelineLayout(), 0, stack.longs(step.descriptorSet()), null);
            int prevPhase = (waterPhase + 2) % 3;
            int nextPhase = (waterPhase + 1) % 3;
            // How far each input image's content sits from this step's domain. Clamped to the grid:
            // anything further means nothing of the old field overlaps, and every read returns zero --
            // which is the correct answer, and keeps the arithmetic away from int overflow after a
            // teleport across the world.
            int curDx = clampCellShift(domainCellX - waterImageCellX[waterPhase]);
            int curDz = clampCellShift(domainCellZ - waterImageCellZ[waterPhase]);
            int prevDx = clampCellShift(domainCellX - waterImageCellX[prevPhase]);
            int prevDz = clampCellShift(domainCellZ - waterImageCellZ[prevPhase]);
            ByteBuffer push = stack.malloc(WATER_SIM_PUSH_BYTES);
            push.putFloat(0, courant2).putFloat(4, damping).putFloat(8, spongeWidth)
                    .putInt(12, Math.min(impulseCount, WATER_MAX_IMPULSES));
            push.putInt(112, curDx).putInt(116, curDz).putInt(120, prevDx).putInt(124, prevDz);
            // cellX, cellZ, radius, amount per record, in the order the shader's struct declares them.
            for (int i = 0; i < WATER_MAX_IMPULSES; i++) {
                int base = 16 + i * 16;
                for (int c = 0; c < 4; c++) {
                    int src = i * 4 + c;
                    push.putFloat(base + c * 4, src < impulses.length ? impulses[src] : 0f);
                }
            }
            VK10.vkCmdPushConstants(cmd, step.pipelineLayout(),
                    VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            int groups = (WATER_SIM_DIM + WATER_SIM_GROUP - 1) / WATER_SIM_GROUP;
            VK10.vkCmdDispatch(cmd, groups, groups, 1);
            // What this step wrote is expressed in THIS step's domain, so that is the origin it carries.
            waterImageCellX[nextPhase] = domainCellX;
            waterImageCellZ[nextPhase] = domainCellZ;
            // Advance the rotation: what this step wrote becomes the next step's current.
            waterPhase = nextPhase;
        }
    }

    /**
     * Replace the obstacle mask with one the CPU worked out, one byte per cell.
     *
     * <p>NOT A RAY. The probe this replaces cast one downward ray per cell against the TLAS, and the
     * water surface is IN that TLAS -- it is rendered geometry -- so every ray hit the water itself and
     * every cell came back an obstacle. Nothing could propagate. Excluding water by cull mask is not
     * available either: the mask is per instance, and a terrain section's water shares its instance with
     * its stone.
     *
     * <p>Asking the level directly is the question we actually wanted answered, and it cannot be
     * confused by anything: a cell is open where the block under the surface is water. The cost is
     * 65k block lookups on a re-anchor, which is a frame's hitch every sixteen blocks of travel rather
     * than anything continuous -- and lookups into a loaded chunk are an array index.
     *
     * @param mask one byte per cell, row-major, 255 for open water and 0 for an obstacle
     */
    public void uploadWaterObstacles(byte[] mask) {
        if (waterObstacle == null || mask.length != WATER_SIM_DIM * WATER_SIM_DIM) {
            return;
        }
        RtBuffer staging = ctx.createUploadBuffer(mask.length, "water obstacle upload");
        try {
            MemoryUtil.memByteBuffer(staging.mapped, mask.length).put(mask);
            staging.flush();
            long image = waterObstacle.view == 0L ? 0L : waterObstacle.image;
            long buffer = staging.handle;
            ctx.submitSync(cmd -> {
                try (MemoryStack up = MemoryStack.stackPush()) {
                    VkImageMemoryBarrier.Buffer toTransfer = VkImageMemoryBarrier.calloc(1, up);
                    toTransfer.get(0).sType$Default()
                            .oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED)
                            .newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                            .srcAccessMask(0).dstAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                            .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                            .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).image(image);
                    toTransfer.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
                    VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                            VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, toTransfer);

                    VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, up);
                    copy.get(0).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
                    copy.get(0).imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                            .mipLevel(0).baseArrayLayer(0).layerCount(1);
                    copy.get(0).imageOffset().set(0, 0, 0);
                    copy.get(0).imageExtent().set(WATER_SIM_DIM, WATER_SIM_DIM, 1);
                    VK10.vkCmdCopyBufferToImage(cmd, buffer, image,
                            VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copy);

                    VkImageMemoryBarrier.Buffer toGeneral = VkImageMemoryBarrier.calloc(1, up);
                    toGeneral.get(0).sType$Default()
                            .oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                            .newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                            .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                            .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT)
                            .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                            .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).image(image);
                    toGeneral.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
                    VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                            VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, null, null, toGeneral);
                }
            });
            waterObstacleReady = true;
        } finally {
            staging.destroy();
        }
    }

    /** The domain moved or the terrain under it changed; the obstacle mask is no longer trustworthy. */
    public void invalidateWaterObstacles() {
        waterObstacleReady = false;
    }

    /**
     * Drop the baked tables so the next frame rebakes them. For whatever ends up changing the
     * atmosphere's parameters — a dimension preset, a reload — rather than for resizes: nothing here is
     * sized to the window.
     */
    public void invalidate() {
        baked = false;
    }

    public void destroy() {
        VkDevice vk = ctx.vk();
        if (transmittance != null) {
            transmittance.destroy();
            transmittance = null;
        }
        if (multiScatter != null) {
            multiScatter.destroy();
            multiScatter = null;
        }
        if (skyViewRayleigh != null) {
            skyViewRayleigh.destroy();
            skyViewRayleigh = null;
        }
        if (skyViewMie != null) {
            skyViewMie.destroy();
            skyViewMie = null;
        }
        if (skyViewMulti != null) {
            skyViewMulti.destroy();
            skyViewMulti = null;
        }
        if (aerialPerspective != null) {
            aerialPerspective.destroy();
            aerialPerspective = null;
        }
        if (visibilityGrid != null) {
            visibilityGrid.destroy();
            visibilityGrid = null;
        }
        if (rainExposureDepth != null) {
            rainExposureDepth.destroy();
            rainExposureDepth = null;
        }
        if (rainWetHistory != null) {
            for (RtImage history : rainWetHistory) {
                if (history != null) {
                    history.destroy();
                }
            }
            rainWetHistory = null;
        }
        if (rainPrecipitation != null) {
            for (RtBuffer cache : rainPrecipitation) {
                if (cache != null) {
                    cache.destroy();
                }
            }
            rainPrecipitation = null;
        }
        if (cloudNoise != null) {
            cloudNoise.destroy();
            cloudNoise = null;
        }
        if (cloudWeather != null) {
            cloudWeather.destroy();
            cloudWeather = null;
        }
        if (cloudWarp != null) {
            cloudWarp.destroy();
            cloudWarp = null;
        }
        if (cloudShadow != null) {
            cloudShadow.destroy();
            cloudShadow = null;
        }
        if (fogNoise != null) {
            fogNoise.destroy();
            fogNoise = null;
        }
        if (waterHeight != null) {
            for (RtImage img : waterHeight) {
                if (img != null) {
                    img.destroy();
                }
            }
            waterHeight = null;
        }
        if (waterObstacle != null) {
            waterObstacle.destroy();
            waterObstacle = null;
        }
        if (waterDisplay != null) {
            waterDisplay.destroy();
            waterDisplay = null;
        }
        if (lutSampler != 0L) {
            VK10.vkDestroySampler(vk, lutSampler, null);
        }
        if (noiseSampler != 0L) {
            VK10.vkDestroySampler(vk, noiseSampler, null);
        }
        if (rainHistorySampler != 0L) {
            VK10.vkDestroySampler(vk, rainHistorySampler, null);
        }
        visibilityTlas.destroy(vk);
        rainExposureTlas.destroy(vk);
        if (waterObstacleTlas != null) {
            waterObstacleTlas.destroy(vk);
        }
        if (waterSimBakes != null) {
            for (Bake b : waterSimBakes) {
                if (b != null) {
                    b.destroy(vk);
                }
            }
        }
        if (waterObstacleBake != null) {
            waterObstacleBake.destroy(vk);
        }
        visibilityBake.destroy(vk);
        rainExposureBake.destroy(vk);
        for (Bake history : rainHistoryBakes) {
            history.destroy(vk);
        }
        cloudNoiseBake.destroy(vk);
        cloudWeatherBake.destroy(vk);
        cloudWarpBake.destroy(vk);
        cloudShadowBake.destroy(vk);
        fogNoiseBake.destroy(vk);
        froxelTlas.destroy(vk);
        froxelBake.destroy(vk);
        mediumSkyReduceBake.destroy(vk);
        skyViewBake.destroy(vk);
        multiScatterBake.destroy(vk);
        transmittanceBake.destroy(vk);
    }

    /**
     * Fill a 3D image's mip chain by successive halving blits.
     *
     * <p>A LINEAR AVERAGE, and that is a named approximation rather than the exact thing. The correct
     * prefilter for a participating medium averages transmittance, not density -- exp() does not commute
     * with a mean, which is why the high-cloud KTX2 mips average exp(-tau) and convert back. It cannot be
     * done here: this volume holds a SHAPE field that only becomes a density after coverage, the height
     * profile and erosion have been applied, so there is no optical depth at this point to preserve. The
     * bias is toward slightly thinner distant cloud, and it is the filter the technique uses in practice.
     *
     * <p>Blit rather than a compute downsample because the image already carries TRANSFER_SRC/DST for its
     * own creation-time clear, so this needs no new usage bit, no second pipeline and no descriptor.
     */
    private static void generateMips3D(VkCommandBuffer cmd, MemoryStack stack, RtImage image, int dim) {
        if (image == null || image.levels <= 1) {
            return;
        }
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
        int size = dim;
        for (int level = 1; level < image.levels; level++) {
            // The level just written has to finish before it is read as this one's source. Both stay in
            // GENERAL: they are read and written by compute and sampled by the trace, and transitioning
            // per level would buy nothing on a path that runs once at startup.
            barrier.get(0).sType$Default()
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT | VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .image(image.image);
            barrier.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(level - 1).levelCount(1).baseArrayLayer(0).layerCount(1);
            VK10.vkCmdPipelineBarrier(cmd,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, barrier);

            int next = Math.max(1, size / 2);
            VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
            blit.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(level - 1).baseArrayLayer(0).layerCount(1);
            blit.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(level).baseArrayLayer(0).layerCount(1);
            blit.get(0).srcOffsets(1).set(size, size, size);
            blit.get(0).dstOffsets(1).set(next, next, next);
            VK10.vkCmdBlitImage(cmd,
                    image.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                    image.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                    blit, VK10.VK_FILTER_LINEAR);
            size = next;
        }
        // Everything the trace samples must be visible to it.
        VkImageMemoryBarrier.Buffer done = VkImageMemoryBarrier.calloc(1, stack);
        done.get(0).sType$Default()
                .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .image(image.image);
        done.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(image.levels).baseArrayLayer(0).layerCount(1);
        VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, null, null, done);
    }

    private static void dispatch(VkCommandBuffer cmd, MemoryStack stack, Bake bake, int w, int h) {
        VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, bake.pipeline());
        VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, bake.pipelineLayout(), 0,
                stack.longs(bake.descriptorSet()), null);
        VK10.vkCmdDispatch(cmd, (w + BAKE_GROUP - 1) / BAKE_GROUP, (h + BAKE_GROUP - 1) / BAKE_GROUP, 1);
    }

    /** Three-dimensional dispatch, for the bakes whose output is a volume rather than a table. */
    private static void dispatch3D(VkCommandBuffer cmd, MemoryStack stack, Bake bake,
                                   int w, int h, int d, int group) {
        VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, bake.pipeline());
        VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, bake.pipelineLayout(), 0,
                stack.longs(bake.descriptorSet()), null);
        VK10.vkCmdDispatch(cmd, (w + group - 1) / group, (h + group - 1) / group, (d + group - 1) / group);
    }

    private static void shaderWriteToComputeReadBarrier(VkCommandBuffer cmd, MemoryStack stack) {
        VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
        barrier.get(0).sType$Default()
                .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT);
        VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, barrier, null, null);
    }

    /** Build one bake's layout/pool/set/pipeline. {@code types} is the set-0 binding list, in order. */
    private static Bake createBake(RtContext ctx, MemoryStack stack, String shader, String label,
                                   int[] types, int pushBytes) {
        return createBake(ctx, stack, shader, label, types, pushBytes, 0L);
    }

    /** {@code extraSetLayout} adds a second descriptor set to the pipeline layout; 0 for none. */
    private static Bake createBake(RtContext ctx, MemoryStack stack, String shader, String label,
                                   int[] types, int pushBytes, long extraSetLayout) {
        VkDevice vk = ctx.vk();
        LongBuffer p = stack.mallocLong(1);
        VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(types.length, stack);
        for (int i = 0; i < types.length; i++) {
            binds.get(i).binding(i).descriptorType(types[i]).descriptorCount(1)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
        }
        VkDescriptorSetLayoutCreateInfo dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType$Default().pBindings(binds);
        check(VK10.vkCreateDescriptorSetLayout(vk, dslci, null, p),
                "vkCreateDescriptorSetLayout(rt " + label + ")");
        long dsl = p.get(0);
        RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, dsl, label + " descriptor set layout");

        VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(types.length, stack);
        for (int i = 0; i < types.length; i++) {
            sizes.get(i).type(types[i]).descriptorCount(1);
        }
        VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                .maxSets(1).pPoolSizes(sizes);
        check(VK10.vkCreateDescriptorPool(vk, dpci, null, p), "vkCreateDescriptorPool(rt " + label + ")");
        long pool = p.get(0);
        RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_POOL, pool, label + " descriptor pool");

        VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                .descriptorPool(pool).pSetLayouts(stack.longs(dsl));
        LongBuffer pSet = stack.mallocLong(1);
        check(VK10.vkAllocateDescriptorSets(vk, dsai, pSet), "vkAllocateDescriptorSets(rt " + label + ")");
        long set = pSet.get(0);
        RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, set, label + " descriptor set");

        VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                .pSetLayouts(extraSetLayout == 0L ? stack.longs(dsl) : stack.longs(dsl, extraSetLayout));
        if (pushBytes > 0) {
            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack);
            pcr.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(pushBytes);
            plci.pPushConstantRanges(pcr);
        }
        check(VK10.vkCreatePipelineLayout(vk, plci, null, p), "vkCreatePipelineLayout(rt " + label + ")");
        long pipelineLayout = p.get(0);
        RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT, pipelineLayout, label + " pipeline layout");

        long module = loadModule(vk, stack, shader);
        VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                .sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module)
                .pName(stack.UTF8("main"));
        VkComputePipelineCreateInfo.Buffer cpci = VkComputePipelineCreateInfo.calloc(1, stack);
        cpci.get(0).sType$Default().stage(stage).layout(pipelineLayout);
        check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, cpci, null, p),
                "vkCreateComputePipelines(rt " + label + ")");
        long pipeline = p.get(0);
        RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, pipeline, label + " pipeline");
        VK10.vkDestroyShaderModule(vk, module, null);
        return new Bake(dsl, pool, set, pipelineLayout, pipeline);
    }

    /** Fill an image with 1.0 on every channel. See the cloud shadow map's creation for why it needs it. */
    private static void clearImageToWhite(RtContext ctx, RtImage image) {
        ctx.submitSync(cmd -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkClearColorValue clear = VkClearColorValue.calloc(stack);
                clear.float32(stack.floats(1f, 1f, 1f, 1f));
                VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
                range.get(0).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                VK10.vkCmdClearColorImage(cmd, image.image, VK10.VK_IMAGE_LAYOUT_GENERAL, clear, range);
            }
        });
    }

    private static void writeStorageImage(VkDevice vk, MemoryStack stack, long set, int binding, long view) {
        writeImage(vk, stack, set, binding, VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, view, VK10.VK_NULL_HANDLE);
    }

    private static void writeSampledImage(VkDevice vk, MemoryStack stack, long set, int binding, long view,
                                          long sampler) {
        writeImage(vk, stack, set, binding, VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, view, sampler);
    }

    private static void writeImage(VkDevice vk, MemoryStack stack, long set, int binding, int type,
                                   long view, long sampler) {
        VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
        info.get(0).sampler(sampler).imageView(view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
        VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(1, stack);
        // descriptorCount MUST be set explicitly. calloc zeroes it and LWJGL does not derive it from
        // pImageInfo — the count is shared between pImageInfo/pBufferInfo/pTexelBufferView, so the
        // binding is left to the caller. Omitting it makes vkUpdateDescriptorSets update ZERO descriptors
        // and return success, so the bake wrote to an unbound storage image and the table read back as
        // zero: the sun and moon discs went black while the sky, which marches the integral itself,
        // stayed correct. Nothing caught it because the validation layers are off in dev runs. Every
        // descriptor write in this class goes through here now so there is one place to get it right.
        writes.get(0).sType$Default().dstSet(set).dstBinding(binding).descriptorCount(1)
                .descriptorType(type).pImageInfo(info);
        VK10.vkUpdateDescriptorSets(vk, writes, null);
    }

    private static long loadModule(VkDevice vk, MemoryStack stack, String name) {
        byte[] bytes;
        try (InputStream in = RtSky.class.getResourceAsStream(SHADER_DIR + name)) {
            if (in == null) {
                throw new IllegalStateException("missing shader " + SHADER_DIR + name);
            }
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read shader " + name, e);
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length);
        try {
            code.put(bytes).flip();
            VkShaderModuleCreateInfo ci = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(vk, ci, null, p), "vkCreateShaderModule(" + name + ")");
            return p.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }
}
