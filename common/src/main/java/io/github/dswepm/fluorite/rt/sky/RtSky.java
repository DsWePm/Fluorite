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
import io.github.dswepm.fluorite.rt.accel.RtImage;

import static io.github.dswepm.fluorite.rt.RtContext.check;

/**
 * The atmosphere's precomputed tables. Owns the LUT images, bakes them when their inputs change, and
 * hands the views to whoever binds them — the same ownership shape {@code RtComposite} uses for the
 * trace targets, and the same compute-pipeline shape {@code RtExposurePipeline} uses for its two passes.
 *
 * <p>Today that is two tables: transmittance to space ({@link #TRANSMITTANCE_W} x
 * {@link #TRANSMITTANCE_H}) and multiple scattering ({@link #MULTISCATTER_N} squared). Sky-view and the
 * aerial-perspective froxel follow, and they belong here rather than in four more classes because they
 * form a CHAIN — each is baked from the one before, so the thing that has to be got right is the ORDER,
 * and an order is easier to keep correct in one file than across four. {@link #recordBakeIfNeeded} is
 * where that order lives, barrier included; no caller can get it wrong because no caller can see it.
 *
 * <p><b>The tables are baked once, not per frame, and that is a property rather than a cache.</b>
 * Nothing in them depends on the time of day: the sun's position enters where they are SAMPLED, not
 * where they are baked. Rebaking every frame would cost the same and mean the same, which is exactly why
 * the reflex to do so is worth naming — it looks like safety and is only waste.
 */
public final class RtSky {
    private static final String SHADER_DIR = "/fluorite/rt/";
    /** Must match SKY_TRANSMITTANCE_W/H in shaders/world/atmosphere.slang. */
    public static final int TRANSMITTANCE_W = 256;
    public static final int TRANSMITTANCE_H = 64;
    /** Must match SKY_MULTISCATTER_N in shaders/world/atmosphere.slang. */
    public static final int MULTISCATTER_N = 32;
    private static final int BAKE_GROUP = 8; // matches [numthreads(8, 8, 1)] in both bake shaders

    /** One compute bake: its layout, its set, and the pipeline that writes one table. */
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
    private final long lutSampler;
    private RtImage transmittance;
    private RtImage multiScatter;
    private boolean baked;

    private RtSky(RtContext ctx, Bake transmittanceBake, Bake multiScatterBake, long lutSampler) {
        this.ctx = ctx;
        this.transmittanceBake = transmittanceBake;
        this.multiScatterBake = multiScatterBake;
        this.lutSampler = lutSampler;
    }

    public static RtSky create(RtContext ctx) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Transmittance: one storage image out, nothing in.
            Bake transmittanceBake = createBake(ctx, stack, "sky_transmittance.comp.spv",
                    "sky transmittance", new int[]{VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE});
            // Multi-scatter: the transmittance table in, one storage image out. Binding order matches
            // sky_multiscatter.comp.slang.
            Bake multiScatterBake = createBake(ctx, stack, "sky_multiscatter.comp.spv",
                    "sky multi-scatter", new int[]{VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                            VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE});

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

            RtSky sky = new RtSky(ctx, transmittanceBake, multiScatterBake, sampler);
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
            writeStorageImage(vk, stack, multiScatterBake.descriptorSet(), 1, sky.multiScatter.view);
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
        if (lutSampler != 0L) {
            VK10.vkDestroySampler(vk, lutSampler, null);
        }
        multiScatterBake.destroy(vk);
        transmittanceBake.destroy(vk);
    }

    private static void dispatch(VkCommandBuffer cmd, MemoryStack stack, Bake bake, int w, int h) {
        VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, bake.pipeline());
        VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, bake.pipelineLayout(), 0,
                stack.longs(bake.descriptorSet()), null);
        VK10.vkCmdDispatch(cmd, (w + BAKE_GROUP - 1) / BAKE_GROUP, (h + BAKE_GROUP - 1) / BAKE_GROUP, 1);
    }

    /** Build one bake's layout/pool/set/pipeline. {@code types} is the set-0 binding list, in order. */
    private static Bake createBake(RtContext ctx, MemoryStack stack, String shader, String label,
                                   int[] types) {
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
                .pSetLayouts(stack.longs(dsl));
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
