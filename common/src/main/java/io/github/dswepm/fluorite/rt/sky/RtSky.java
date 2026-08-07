package io.github.dswepm.fluorite.rt.sky;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
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
import io.github.dswepm.fluorite.rt.overlay.RtOverlayPipelines;
import io.github.dswepm.fluorite.rt.accel.RtImage;

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
    private static final int FROXEL_PUSH_BYTES = 8; // WorldPush device address
    /** Must match VIS_GRID_W/H/D in shaders/world/volume_visibility{,.comp}.slang. */
    public static final int VIS_GRID_W = 64;
    public static final int VIS_GRID_H = 32;
    public static final int VIS_GRID_D = 64;
    private static final int VIS_GROUP = 4; // matches [numthreads(4, 4, 4)]
    private static final int VIS_PUSH_BYTES = 8; // WorldPush address; the grid's placement is a field in it
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
    private final Bake cloudNoiseBake;
    private final RtOverlayPipelines.AccelStructureSet froxelTlas;
    private final RtOverlayPipelines.AccelStructureSet visibilityTlas;
    private final long lutSampler;
    private RtImage transmittance;
    private RtImage multiScatter;
    private RtImage skyViewRayleigh;
    private RtImage skyViewMie;
    private RtImage skyViewMulti;
    private RtImage aerialPerspective;
    private RtImage visibilityGrid;
    private RtImage cloudNoise;
    // The interactive water simulation's height field (M12), in metres of displacement. Three buffers
    // rotate through prev/cur/next: leapfrog needs both previous states, so writing next over prev in
    // place would corrupt neighbours a later thread still has to read.
    private RtImage[] waterHeight;
    private RtImage waterObstacle;
    /** Must equal WATER_SIM_DIM in water_sim.comp.slang and water.slang. */
    public static final int WATER_SIM_DIM = 256;
    /** Must equal CLOUD_NOISE_DIM in cloud_noise.comp.slang, and numthreads there. */
    private static final int CLOUD_NOISE_DIM = 128;
    private static final int CLOUD_NOISE_GROUP = 4;
    private boolean baked;

    private RtSky(RtContext ctx, Bake transmittanceBake, Bake multiScatterBake, Bake skyViewBake,
                  Bake mediumSkyReduceBake, Bake froxelBake,
                  RtOverlayPipelines.AccelStructureSet froxelTlas,
                  Bake visibilityBake, RtOverlayPipelines.AccelStructureSet visibilityTlas,
                  Bake cloudNoiseBake, long lutSampler) {
        this.ctx = ctx;
        this.transmittanceBake = transmittanceBake;
        this.multiScatterBake = multiScatterBake;
        this.skyViewBake = skyViewBake;
        this.mediumSkyReduceBake = mediumSkyReduceBake;
        this.froxelBake = froxelBake;
        this.froxelTlas = froxelTlas;
        this.visibilityBake = visibilityBake;
        this.cloudNoiseBake = cloudNoiseBake;
        this.visibilityTlas = visibilityTlas;
        this.lutSampler = lutSampler;
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

            // The volumetric visibility grid (M13.2). Its own TLAS ring rather than a second bind of the
            // froxel's: a ring slot is claimed per bind, and binding one ring twice a frame is exactly the
            // kind of arithmetic that works right up until the ring wraps.
            // The cloud noise (M11.1): one storage image out, nothing in. Once-ever like the
            // transmittance table, and for the same reason -- nothing in it depends on the time of day or
            // the camera. It lives on this bake chain rather than in its own object because the chain's
            // whole value is that the ORDER is settled in one place; a second owner would be a second
            // opinion about when things are ready.
            Bake cloudNoiseBake = createBake(ctx, stack, "cloud_noise.comp.spv", "cloud noise",
                    new int[]{VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}, 0);

            RtOverlayPipelines.AccelStructureSet visibilityTlas = RtOverlayPipelines.accelStructureSet(
                    ctx, VK10.VK_SHADER_STAGE_COMPUTE_BIT, "volume visibility TLAS");
            Bake visibilityBake = createBake(ctx, stack, "volume_visibility.comp.spv", "volume visibility",
                    new int[]{VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}, VIS_PUSH_BYTES,
                    visibilityTlas.layout);

            RtSky sky = new RtSky(ctx, transmittanceBake, multiScatterBake, skyViewBake,
                    mediumSkyReduceBake, froxelBake, froxelTlas,
                    visibilityBake, visibilityTlas, cloudNoiseBake, sampler);
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
                    VK10.VK_FORMAT_R8G8B8A8_UNORM, "cloud noise volume");
            writeStorageImage(vk, stack, cloudNoiseBake.descriptorSet(), 0, sky.cloudNoise.view);

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

            sky.aerialPerspective = ctx.createStorageImage3D(FROXEL_W, FROXEL_H, FROXEL_D,
                    VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "aerial perspective froxel");
            writeSampledImage(vk, stack, froxelBake.descriptorSet(), 0, sky.transmittance.view, sampler);
            writeStorageImage(vk, stack, froxelBake.descriptorSet(), 1, sky.aerialPerspective.view);

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
            return sky;
        }
    }

    /** The transmittance table's view. Valid from construction; contents defined after the first bake. */
    public long transmittanceView() {
        return transmittance == null ? 0L : transmittance.view;
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

    /** M11.1 cloud noise: R the billow that shapes a cloud, G the detail that erodes its edges. */
    public long cloudNoiseView() {
        return cloudNoise == null ? 0L : cloudNoise.view;
    }

    /** The height field the shading samples. Index 1 is the current state between steps. */
    public long waterHeightView() {
        return waterHeight == null ? 0L : waterHeight[1].view;
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
     * Record the aerial-perspective froxel. Per frame, like the sky-view table and for a stronger reason:
     * this one follows the camera, not just the sun.
     *
     * <p>Takes the WorldPush device address rather than a wall of camera and fog parameters. That buffer
     * already holds the inverse view-projection the primary rays are built from and all four of the fog's
     * parameter vectors, and a froxel column that disagreed with the pixels above it — by half a tile, or
     * by a stale density — would read as the fog sliding over the geometry as the camera turns.
     */
    public void recordFroxelBake(VkCommandBuffer cmd, long worldPushAddr, long tlas,
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
            // The cloud noise shares this once-ever pass but nothing in the chain above it: no barrier
            // between them because neither reads what the other writes.
            dispatch3D(cmd, stack, cloudNoiseBake,
                    CLOUD_NOISE_DIM, CLOUD_NOISE_DIM, CLOUD_NOISE_DIM, CLOUD_NOISE_GROUP);
        }
        baked = true;
        return true;
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
        if (cloudNoise != null) {
            cloudNoise.destroy();
            cloudNoise = null;
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
        if (lutSampler != 0L) {
            VK10.vkDestroySampler(vk, lutSampler, null);
        }
        visibilityTlas.destroy(vk);
        visibilityBake.destroy(vk);
        cloudNoiseBake.destroy(vk);
        froxelTlas.destroy(vk);
        froxelBake.destroy(vk);
        mediumSkyReduceBake.destroy(vk);
        skyViewBake.destroy(vk);
        multiScatterBake.destroy(vk);
        transmittanceBake.destroy(vk);
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
