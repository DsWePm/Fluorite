package io.github.dswepm.fluorite.rt.pipeline;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
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
 * D145A's signed-CoC, tile-dilated, split near/far depth-of-field pipeline.
 *
 * <p>Only the final recombine participates in the rrOutput/scratch ping-pong.  CoC is full resolution;
 * near and far gathers are half resolution; the two RG16F tile fields are one texel per 16x16 display
 * pixels.  Two immutable descriptor sets preserve the source/target direction contract used by the
 * neighbouring motion-blur pass.
 */
public final class RtDepthOfFieldPipeline {
    private static final String SHADER_DIR = "/fluorite/rt/";
    private static final int PUSH_BYTES = 128;
    private static final int BINDING_COUNT = 9;

    private final RtContext ctx;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long[] descriptorSets;
    private final long pipelineLayout;
    private final long pipeline;
    private final long sampler;
    private final long[] boundViews = new long[BINDING_COUNT];
    private boolean destroyed;

    private RtDepthOfFieldPipeline(RtContext ctx, long descriptorSetLayout, long descriptorPool,
                                   long[] descriptorSets, long pipelineLayout, long pipeline,
                                   long sampler) {
        this.ctx = ctx;
        this.descriptorSetLayout = descriptorSetLayout;
        this.descriptorPool = descriptorPool;
        this.descriptorSets = descriptorSets;
        this.pipelineLayout = pipelineLayout;
        this.pipeline = pipeline;
        this.sampler = sampler;
    }

    public static RtDepthOfFieldPipeline create(RtContext ctx) {
        VkDevice vk = ctx.vk();
        long dsl = 0L;
        long pool = 0L;
        long layout = 0L;
        long pipeline = 0L;
        long sampler = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings =
                    VkDescriptorSetLayoutBinding.calloc(BINDING_COUNT, stack);
            bindings.get(0).binding(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            for (int i = 1; i < BINDING_COUNT; i++) {
                bindings.get(i).binding(i).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            LongBuffer out = stack.mallocLong(1);
            VkDescriptorSetLayoutCreateInfo dslInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(bindings);
            check(VK10.vkCreateDescriptorSetLayout(vk, dslInfo, null, out),
                    "vkCreateDescriptorSetLayout(depth of field)");
            dsl = out.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, dsl,
                    "depth of field descriptor layout");

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(2);
            poolSizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(2 * (BINDING_COUNT - 1));
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(2).pPoolSizes(poolSizes);
            check(VK10.vkCreateDescriptorPool(vk, poolInfo, null, out),
                    "vkCreateDescriptorPool(depth of field)");
            pool = out.get(0);

            VkDescriptorSetAllocateInfo allocate = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(stack.longs(dsl, dsl));
            LongBuffer setOut = stack.mallocLong(2);
            check(VK10.vkAllocateDescriptorSets(vk, allocate, setOut),
                    "vkAllocateDescriptorSets(depth of field)");
            long[] sets = {setOut.get(0), setOut.get(1)};
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, sets[0],
                    "depth of field rr to scratch set");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, sets[1],
                    "depth of field scratch to rr set");

            VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack);
            range.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(dsl)).pPushConstantRanges(range);
            check(VK10.vkCreatePipelineLayout(vk, layoutInfo, null, out),
                    "vkCreatePipelineLayout(depth of field)");
            layout = out.get(0);

            sampler = createSampler(ctx, stack);
            pipeline = createPipeline(ctx, stack, layout);
            return new RtDepthOfFieldPipeline(ctx, dsl, pool, sets, layout, pipeline, sampler);
        } catch (Throwable t) {
            if (pipeline != 0L) VK10.vkDestroyPipeline(vk, pipeline, null);
            if (sampler != 0L) VK10.vkDestroySampler(vk, sampler, null);
            if (layout != 0L) VK10.vkDestroyPipelineLayout(vk, layout, null);
            if (pool != 0L) VK10.vkDestroyDescriptorPool(vk, pool, null);
            if (dsl != 0L) VK10.vkDestroyDescriptorSetLayout(vk, dsl, null);
            throw t;
        }
    }

    public void setImages(long rrView, long scratchView, long depthView, long focusView,
                          long cocView, long nearView, long farView,
                          long tileView, long dilatedTileView) {
        long[] views = {rrView, scratchView, depthView, focusView, cocView,
                nearView, farView, tileView, dilatedTileView};
        if (java.util.Arrays.equals(boundViews, views)) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer[] infos =
                    new VkDescriptorImageInfo.Buffer[2 * BINDING_COUNT];
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2 * BINDING_COUNT, stack);
            for (int direction = 0; direction < 2; direction++) {
                long source = direction == RtLensPipeline.RR_TO_SCRATCH ? rrView : scratchView;
                long target = direction == RtLensPipeline.RR_TO_SCRATCH ? scratchView : rrView;
                long[] directionViews = {source, target, depthView, focusView, cocView,
                        nearView, farView, tileView, dilatedTileView};
                for (int binding = 0; binding < BINDING_COUNT; binding++) {
                    int writeIndex = direction * BINDING_COUNT + binding;
                    infos[writeIndex] = VkDescriptorImageInfo.calloc(1, stack);
                    infos[writeIndex].get(0).imageView(directionViews[binding])
                            .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                    int type = binding == 0 ? VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                            : VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
                    if (binding == 0) infos[writeIndex].get(0).sampler(sampler);
                    writes.get(writeIndex).sType$Default().dstSet(descriptorSets[direction])
                            .dstBinding(binding).descriptorCount(1).descriptorType(type)
                            .pImageInfo(infos[writeIndex]);
                }
            }
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
        }
        System.arraycopy(views, 0, boundViews, 0, views.length);
    }

    public void record(VkCommandBuffer cmd, int direction, Matrix4fc inverseProjection,
                       int displayWidth, int displayHeight, int guideWidth, int guideHeight,
                       boolean autoFocus, float manualFocusDistance, float fStop,
                       float maxRadius, float focalLengthMm, int apertureBlades) {
        int halfWidth = (displayWidth + 1) / 2;
        int halfHeight = (displayHeight + 1) / 2;
        int tileWidth = (displayWidth + 15) / 16;
        int tileHeight = (displayHeight + 15) / 16;
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "cinematic depth of field")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout,
                    0, stack.longs(descriptorSets[direction]), null);
            ByteBuffer push = stack.malloc(PUSH_BYTES);
            inverseProjection.get(0, push);
            push.putInt(64, displayWidth);
            push.putInt(68, displayHeight);
            push.putInt(72, guideWidth);
            push.putInt(76, guideHeight);
            push.putInt(80, halfWidth);
            push.putInt(84, halfHeight);
            push.putInt(88, tileWidth);
            push.putInt(92, tileHeight);
            push.putInt(100, autoFocus ? 1 : 0);
            push.putInt(104, apertureBlades);
            push.putInt(108, 0);
            push.putFloat(112, manualFocusDistance);
            push.putFloat(116, fStop);
            push.putFloat(120, maxRadius);
            push.putFloat(124, focalLengthMm);

            dispatch(cmd, push, 0, displayWidth, displayHeight);
            VulkanCommandEncoder.memoryBarrier(cmd, stack);
            dispatch(cmd, push, 1, tileWidth, tileHeight);
            VulkanCommandEncoder.memoryBarrier(cmd, stack);
            dispatch(cmd, push, 2, tileWidth, tileHeight);
            VulkanCommandEncoder.memoryBarrier(cmd, stack);
            dispatch(cmd, push, 3, halfWidth, halfHeight);
            VulkanCommandEncoder.memoryBarrier(cmd, stack);
            dispatch(cmd, push, 4, displayWidth, displayHeight);
        }
    }

    private void dispatch(VkCommandBuffer cmd, ByteBuffer push, int mode, int width, int height) {
        push.putInt(96, mode);
        VK10.vkCmdPushConstants(cmd, pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
        VK10.vkCmdDispatch(cmd, (width + 15) / 16, (height + 15) / 16, 1);
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
        check(VK10.vkCreateSampler(ctx.vk(), info, null, out), "vkCreateSampler(depth of field)");
        long sampler = out.get(0);
        RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SAMPLER, sampler, "depth of field sampler");
        return sampler;
    }

    private static long createPipeline(RtContext ctx, MemoryStack stack, long layout) {
        VkDevice vk = ctx.vk();
        long module = loadModule(vk, stack, "depth_of_field.comp.spv");
        try {
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack);
            info.get(0).sType$Default().stage(stage).layout(layout);
            LongBuffer out = stack.mallocLong(1);
            check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, info, null, out),
                    "vkCreateComputePipelines(depth of field)");
            long pipeline = out.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, pipeline,
                    "cinematic depth of field pipeline");
            return pipeline;
        } finally {
            VK10.vkDestroyShaderModule(vk, module, null);
        }
    }

    private static long loadModule(VkDevice vk, MemoryStack stack, String name) {
        byte[] bytes;
        try (InputStream in = RtDepthOfFieldPipeline.class.getResourceAsStream(SHADER_DIR + name)) {
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
