package io.github.dswepm.fluorite.rt.pipeline;

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
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import io.github.dswepm.fluorite.rt.RtContext;
import io.github.dswepm.fluorite.rt.RtDebugLabels;

import static io.github.dswepm.fluorite.rt.RtContext.check;

/** Compute pass that grades display-res scene-linear HDR and applies the selected SDR/HDR output transform. */
public final class RtDisplayPipeline {
    private static final String SHADER_DIR = "/fluorite/rt/";
    /** Four ints followed by eight floats; layout mirrored by {@code shaders/display/display.comp}. */
    private static final int PUSH_BYTES = 12 * Integer.BYTES;

    private final RtContext ctx;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long descriptorSet;
    private final long pipelineLayout;
    private final long fastPipeline;
    private final long exactPipeline;
    private final RtAces2Luts acesLuts;
    private long boundOutputView;
    private long boundRtView;
    private long boundExposureView;
    private long boundHdrView;
    private boolean destroyed;

    private RtDisplayPipeline(RtContext ctx, long dsl, long pool, long set, long layout,
                              long fastPipeline, long exactPipeline, RtAces2Luts acesLuts) {
        this.ctx = ctx;
        this.descriptorSetLayout = dsl;
        this.descriptorPool = pool;
        this.descriptorSet = set;
        this.pipelineLayout = layout;
        this.fastPipeline = fastPipeline;
        this.exactPipeline = exactPipeline;
        this.acesLuts = acesLuts;
    }

    public static RtDisplayPipeline create(RtContext ctx) {
        VkDevice vk = ctx.vk();
        RtAces2Luts acesLuts = RtAces2Luts.create(ctx);
        long dsl = 0L;
        long pool = 0L;
        long layout = 0L;
        long fastPipeline = 0L;
        long exactPipeline = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(9, stack);
            for (int i = 0; i < 4; i++) {
                binds.get(i).binding(i).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            for (int i = 4; i < 9; i++) {
                binds.get(i).binding(i).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            }

            VkDescriptorSetLayoutCreateInfo dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(vk, dslci, null, p), "vkCreateDescriptorSetLayout(rt display)");
            dsl = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, dsl, "display descriptor set layout");

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(4);
            poolSizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(5);
            VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(poolSizes);
            check(VK10.vkCreateDescriptorPool(vk, dpci, null, p), "vkCreateDescriptorPool(rt display)");
            pool = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_POOL, pool, "display descriptor pool");

            VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(stack.longs(dsl));
            LongBuffer pSet = stack.mallocLong(1);
            check(VK10.vkAllocateDescriptorSets(vk, dsai, pSet), "vkAllocateDescriptorSets(rt display)");
            long set = pSet.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, set, "display descriptor set");

            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack);
            pushRange.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
            VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(dsl)).pPushConstantRanges(pushRange);
            check(VK10.vkCreatePipelineLayout(vk, plci, null, p), "vkCreatePipelineLayout(rt display)");
            layout = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT, layout, "display pipeline layout");

            fastPipeline = createComputePipeline(ctx, stack, layout, "display.comp.spv", "display AgX/LUT");
            exactPipeline = createComputePipeline(ctx, stack, layout,
                    "display_aces_exact.comp.spv", "display ACES 2 exact");
            return new RtDisplayPipeline(ctx, dsl, pool, set, layout,
                    fastPipeline, exactPipeline, acesLuts);
        } catch (Throwable t) {
            if (exactPipeline != 0L) VK10.vkDestroyPipeline(vk, exactPipeline, null);
            if (fastPipeline != 0L) VK10.vkDestroyPipeline(vk, fastPipeline, null);
            if (layout != 0L) VK10.vkDestroyPipelineLayout(vk, layout, null);
            if (pool != 0L) VK10.vkDestroyDescriptorPool(vk, pool, null);
            if (dsl != 0L) VK10.vkDestroyDescriptorSetLayout(vk, dsl, null);
            acesLuts.destroy();
            throw t;
        }
    }

    public void setImages(long outputImageView, long rtImageView, long exposureImageView, long hdrImageView) {
        if (boundOutputView == outputImageView && boundRtView == rtImageView
                && boundExposureView == exposureImageView && boundHdrView == hdrImageView) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer outputInfo = VkDescriptorImageInfo.calloc(1, stack);
            outputInfo.get(0).imageView(outputImageView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer rtInfo = VkDescriptorImageInfo.calloc(1, stack);
            rtInfo.get(0).imageView(rtImageView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer exposureInfo = VkDescriptorImageInfo.calloc(1, stack);
            exposureInfo.get(0).imageView(exposureImageView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer hdrInfo = VkDescriptorImageInfo.calloc(1, stack);
            hdrInfo.get(0).imageView(hdrImageView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer[] lutInfo = new VkDescriptorImageInfo.Buffer[RtAces2Luts.LUT_COUNT];
            for (int i = 0; i < lutInfo.length; i++) {
                lutInfo[i] = VkDescriptorImageInfo.calloc(1, stack);
                lutInfo[i].get(0).sampler(acesLuts.sampler()).imageView(acesLuts.view(i))
                        .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            }

            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(9, stack);
            writes.get(0).sType$Default().dstSet(descriptorSet).dstBinding(0)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(outputInfo);
            writes.get(1).sType$Default().dstSet(descriptorSet).dstBinding(1)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(rtInfo);
            writes.get(2).sType$Default().dstSet(descriptorSet).dstBinding(2)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(exposureInfo);
            writes.get(3).sType$Default().dstSet(descriptorSet).dstBinding(3)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(hdrInfo);
            for (int i = 0; i < lutInfo.length; i++) {
                writes.get(4 + i).sType$Default().dstSet(descriptorSet).dstBinding(4 + i)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .pImageInfo(lutInfo[i]);
            }
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
        }
        boundOutputView = outputImageView;
        boundRtView = rtImageView;
        boundExposureView = exposureImageView;
        boundHdrView = hdrImageView;
    }

    /**
     * Run grading and display mapping. SDR is always written; when {@code hdrEnabled}, the selected
     * transform also writes its parallel Rec.2020/PQ image. Paper white/headroom belong only to legacy
     * AgX HDR. ACES uses one of the four fixed official peak presets.
     */
    public void dispatch(VkCommandBuffer cmd, int width, int height, boolean hdrEnabled,
                         int outputTransformMode, int acesHdrPresetNits,
                         float paperWhiteNits, float headroom,
                         boolean gradingEnabled, float temperatureK, float tint,
                         float contrast, float saturation, float hueDegrees) {
        try (MemoryStack stack = MemoryStack.stackPush(); RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd,
                outputTransformMode == 2 ? "display ACES 2 exact" : "display AgX/LUT")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    outputTransformMode == 2 ? exactPipeline : fastPipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(descriptorSet), null);
            ByteBuffer push = stack.malloc(PUSH_BYTES);
            push.putInt(0, hdrEnabled ? 1 : 0);
            push.putInt(4, outputTransformMode);
            push.putInt(8, acesPresetIndex(acesHdrPresetNits));
            push.putInt(12, gradingEnabled ? 1 : 0);
            push.putFloat(16, paperWhiteNits);
            push.putFloat(20, headroom);
            push.putFloat(24, temperatureK);
            push.putFloat(28, tint);
            push.putFloat(32, contrast);
            push.putFloat(36, saturation);
            push.putFloat(40, hueDegrees);
            push.putFloat(44, 0.0f);
            VK10.vkCmdPushConstants(cmd, pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, (width + 15) / 16, (height + 15) / 16, 1);
        }
    }

    private static long createComputePipeline(RtContext ctx, MemoryStack stack, long layout,
                                              String moduleName, String label) {
        VkDevice vk = ctx.vk();
        long module = loadModule(vk, stack, moduleName);
        try {
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SHADER_MODULE, module, label + " shader module");
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack);
            pipelineInfo.get(0).sType$Default().stage(stage).layout(layout);
            LongBuffer out = stack.mallocLong(1);
            check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, pipelineInfo, null, out),
                    "vkCreateComputePipelines(" + label + ")");
            long pipeline = out.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, pipeline, label + " pipeline");
            return pipeline;
        } finally {
            VK10.vkDestroyShaderModule(vk, module, null);
        }
    }

    private static int acesPresetIndex(int nits) {
        if (nits <= 500) return 0;
        if (nits <= 1000) return 1;
        if (nits <= 2000) return 2;
        return 3;
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        VkDevice vk = ctx.vk();
        VK10.vkDestroyPipeline(vk, exactPipeline, null);
        VK10.vkDestroyPipeline(vk, fastPipeline, null);
        VK10.vkDestroyPipelineLayout(vk, pipelineLayout, null);
        VK10.vkDestroyDescriptorPool(vk, descriptorPool, null);
        VK10.vkDestroyDescriptorSetLayout(vk, descriptorSetLayout, null);
        acesLuts.destroy();
        destroyed = true;
    }

    private static long loadModule(VkDevice vk, MemoryStack stack, String name) {
        byte[] bytes;
        try (InputStream in = RtDisplayPipeline.class.getResourceAsStream(SHADER_DIR + name)) {
            if (in == null) {
                throw new IllegalStateException("missing SPIR-V resource: " + SHADER_DIR + name);
            }
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read SPIR-V resource: " + SHADER_DIR + name, e);
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length).put(bytes);
        code.flip();
        try {
            VkShaderModuleCreateInfo smci = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
            LongBuffer pModule = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(vk, smci, null, pModule), "vkCreateShaderModule(" + name + ")");
            return pModule.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }
}
