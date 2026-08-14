package io.github.dswepm.fluorite.rt.pipeline;

import io.github.dswepm.fluorite.rt.RtContext;
import io.github.dswepm.fluorite.rt.RtDebugLabels;
import org.joml.Matrix4fc;
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
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static io.github.dswepm.fluorite.rt.RtContext.check;

/**
 * Post-RR scene-linear spatial lens pass.
 *
 * <p>Two immutable descriptor sets encode the only legal directions: rrOutput -> scratch and scratch ->
 * rrOutput. Keeping them separate matters because descriptor updates are not captured into a command
 * buffer; rewriting one set between two recorded dispatches would make both dispatches observe the final
 * descriptors when the GPU eventually executes them.
 */
public final class RtLensPipeline {
    private static final String SHADER_DIR = "/fluorite/rt/";
    private static final int PUSH_BYTES = 128;
    public static final int RR_TO_SCRATCH = 0;
    public static final int SCRATCH_TO_RR = 1;

    private final RtContext ctx;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long[] descriptorSets;
    private final long pipelineLayout;
    private final long pipeline;
    private final long sampler;
    private long boundRrView;
    private long boundScratchView;
    private long boundDepthView;
    private long boundMotionView;
    private long boundFocusView;
    private long lastFocusNanos;
    private boolean destroyed;

    private RtLensPipeline(RtContext ctx, long descriptorSetLayout, long descriptorPool,
                           long[] descriptorSets, long pipelineLayout, long pipeline, long sampler) {
        this.ctx = ctx;
        this.descriptorSetLayout = descriptorSetLayout;
        this.descriptorPool = descriptorPool;
        this.descriptorSets = descriptorSets;
        this.pipelineLayout = pipelineLayout;
        this.pipeline = pipeline;
        this.sampler = sampler;
    }

    public static RtLensPipeline create(RtContext ctx) {
        VkDevice vk = ctx.vk();
        long dsl = 0L;
        long pool = 0L;
        long layout = 0L;
        long pipeline = 0L;
        long sampler = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(5, stack);
            bindings.get(0).binding(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            for (int i = 1; i < 5; i++) {
                bindings.get(i).binding(i).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            VkDescriptorSetLayoutCreateInfo dslInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(bindings);
            LongBuffer out = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(vk, dslInfo, null, out),
                    "vkCreateDescriptorSetLayout(lens spatial)");
            dsl = out.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, dsl,
                    "lens spatial descriptor layout");

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(2);
            poolSizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(8);
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(2).pPoolSizes(poolSizes);
            check(VK10.vkCreateDescriptorPool(vk, poolInfo, null, out),
                    "vkCreateDescriptorPool(lens spatial)");
            pool = out.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_POOL, pool,
                    "lens spatial descriptor pool");

            VkDescriptorSetAllocateInfo allocate = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(stack.longs(dsl, dsl));
            LongBuffer setsOut = stack.mallocLong(2);
            check(VK10.vkAllocateDescriptorSets(vk, allocate, setsOut),
                    "vkAllocateDescriptorSets(lens spatial)");
            long[] sets = {setsOut.get(0), setsOut.get(1)};
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, sets[0], "lens rr to scratch set");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, sets[1], "lens scratch to rr set");

            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack);
            pushRange.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(dsl)).pPushConstantRanges(pushRange);
            check(VK10.vkCreatePipelineLayout(vk, layoutInfo, null, out),
                    "vkCreatePipelineLayout(lens spatial)");
            layout = out.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT, layout, "lens spatial pipeline layout");

            sampler = createSampler(ctx, stack);
            pipeline = createPipeline(ctx, stack, layout);
            return new RtLensPipeline(ctx, dsl, pool, sets, layout, pipeline, sampler);
        } catch (Throwable t) {
            if (pipeline != 0L) VK10.vkDestroyPipeline(vk, pipeline, null);
            if (sampler != 0L) VK10.vkDestroySampler(vk, sampler, null);
            if (layout != 0L) VK10.vkDestroyPipelineLayout(vk, layout, null);
            if (pool != 0L) VK10.vkDestroyDescriptorPool(vk, pool, null);
            if (dsl != 0L) VK10.vkDestroyDescriptorSetLayout(vk, dsl, null);
            throw t;
        }
    }

    public void setImages(long rrView, long scratchView, long depthView, long motionView, long focusView) {
        if (boundRrView == rrView && boundScratchView == scratchView && boundDepthView == depthView
                && boundMotionView == motionView && boundFocusView == focusView) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer[] infos = new VkDescriptorImageInfo.Buffer[10];
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(10, stack);
            for (int direction = 0; direction < 2; direction++) {
                long source = direction == RR_TO_SCRATCH ? rrView : scratchView;
                long target = direction == RR_TO_SCRATCH ? scratchView : rrView;
                int base = direction * 5;
                long[] views = {source, target, depthView, motionView, focusView};
                for (int binding = 0; binding < 5; binding++) {
                    infos[base + binding] = VkDescriptorImageInfo.calloc(1, stack);
                    infos[base + binding].get(0).imageView(views[binding])
                            .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                    int type = binding == 0 ? VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                            : VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
                    if (binding == 0) infos[base].get(0).sampler(sampler);
                    writes.get(base + binding).sType$Default().dstSet(descriptorSets[direction])
                            .dstBinding(binding).descriptorCount(1).descriptorType(type)
                            .pImageInfo(infos[base + binding]);
                }
            }
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
        }
        boundRrView = rrView;
        boundScratchView = scratchView;
        boundDepthView = depthView;
        boundMotionView = motionView;
        boundFocusView = focusView;
        lastFocusNanos = 0L;
    }

    public void updateAutoFocus(VkCommandBuffer cmd, Matrix4fc inverseProjection,
                                int displayWidth, int displayHeight, int guideWidth, int guideHeight,
                                float focalLengthMm) {
        dispatch(cmd, RR_TO_SCRATCH, 0, inverseProjection, displayWidth, displayHeight,
                guideWidth, guideHeight, 0, true, frameTimeSeconds(), 10.0f,
                4.0f, 0.0f, 0.0f, focalLengthMm, "lens auto focus");
    }

    public void motionBlur(VkCommandBuffer cmd, int direction, Matrix4fc inverseProjection,
                           int displayWidth, int displayHeight, int guideWidth, int guideHeight,
                           int samples, float shutterAngle, float maxRadius, float focalLengthMm) {
        dispatch(cmd, direction, 1, inverseProjection, displayWidth, displayHeight,
                guideWidth, guideHeight, samples, false, 0.0f, 10.0f,
                4.0f, maxRadius, shutterAngle / 360.0f, focalLengthMm, "lens motion blur");
    }

    private void dispatch(VkCommandBuffer cmd, int direction, int mode, Matrix4fc inverseProjection,
                          int displayWidth, int displayHeight, int guideWidth, int guideHeight,
                          int sampleCount, boolean autoFocus, float deltaSeconds,
                          float focusDistance, float fStop, float maxRadius,
                          float shutterScale, float focalLengthMm, String label) {
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, label)) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout,
                    0, stack.longs(descriptorSets[direction]), null);
            ByteBuffer push = stack.malloc(PUSH_BYTES);
            inverseProjection.get(0, push);
            push.putInt(64, displayWidth);
            push.putInt(68, displayHeight);
            push.putInt(72, guideWidth);
            push.putInt(76, guideHeight);
            push.putInt(80, mode);
            push.putInt(84, sampleCount);
            push.putInt(88, autoFocus ? 1 : 0);
            push.putInt(92, 0);
            push.putFloat(96, deltaSeconds);
            push.putFloat(100, focusDistance);
            push.putFloat(104, fStop);
            push.putFloat(108, maxRadius);
            push.putFloat(112, shutterScale);
            push.putFloat(116, focalLengthMm);
            push.putFloat(120, 0.0f);
            push.putFloat(124, 0.0f);
            VK10.vkCmdPushConstants(cmd, pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            if (mode == 0) {
                VK10.vkCmdDispatch(cmd, 1, 1, 1);
            } else {
                VK10.vkCmdDispatch(cmd, (displayWidth + 15) / 16, (displayHeight + 15) / 16, 1);
            }
        }
    }

    private float frameTimeSeconds() {
        long now = System.nanoTime();
        float dt = lastFocusNanos == 0L ? 1.0f / 60.0f
                : Math.clamp((now - lastFocusNanos) / 1_000_000_000.0f, 1.0f / 240.0f, 0.25f);
        lastFocusNanos = now;
        return dt;
    }

    public void destroy() {
        if (destroyed) return;
        VkDevice vk = ctx.vk();
        VK10.vkDestroyPipeline(vk, pipeline, null);
        VK10.vkDestroySampler(vk, sampler, null);
        VK10.vkDestroyPipelineLayout(vk, pipelineLayout, null);
        VK10.vkDestroyDescriptorPool(vk, descriptorPool, null);
        VK10.vkDestroyDescriptorSetLayout(vk, descriptorSetLayout, null);
        destroyed = true;
    }

    private static long createSampler(RtContext ctx, MemoryStack stack) {
        VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack).sType$Default()
                .magFilter(VK10.VK_FILTER_LINEAR).minFilter(VK10.VK_FILTER_LINEAR)
                .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).maxLod(0.0f);
        LongBuffer out = stack.mallocLong(1);
        check(VK10.vkCreateSampler(ctx.vk(), info, null, out), "vkCreateSampler(lens spatial)");
        RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SAMPLER, out.get(0), "lens spatial sampler");
        return out.get(0);
    }

    private static long createPipeline(RtContext ctx, MemoryStack stack, long layout) {
        VkDevice vk = ctx.vk();
        long module = loadModule(vk, stack, "lens_spatial.comp.spv");
        try {
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack);
            info.get(0).sType$Default().stage(stage).layout(layout);
            LongBuffer out = stack.mallocLong(1);
            check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, info, null, out),
                    "vkCreateComputePipelines(lens spatial)");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, out.get(0), "lens spatial pipeline");
            return out.get(0);
        } finally {
            VK10.vkDestroyShaderModule(vk, module, null);
        }
    }

    private static long loadModule(VkDevice vk, MemoryStack stack, String name) {
        byte[] bytes;
        try (InputStream in = RtLensPipeline.class.getResourceAsStream(SHADER_DIR + name)) {
            if (in == null) throw new IllegalStateException("missing SPIR-V resource: " + SHADER_DIR + name);
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read SPIR-V resource: " + SHADER_DIR + name, e);
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length).put(bytes);
        code.flip();
        try {
            VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
            LongBuffer out = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(vk, info, null, out), "vkCreateShaderModule(" + name + ")");
            return out.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }
}
