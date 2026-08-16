package io.github.dswepm.fluorite.rt.pipeline;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import io.github.dswepm.fluorite.rt.RtContext;
import io.github.dswepm.fluorite.rt.RtShaderModules;
import io.github.dswepm.fluorite.rt.RtDebugLabels;
import org.lwjgl.system.MemoryStack;
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
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.Arrays;

import static io.github.dswepm.fluorite.rt.RtContext.check;

/** D149A's shared HDR bright pyramid and separate Bloom/Lens Flare composite. */
public final class RtBloomFlarePipeline {
    public static final int LEVEL_COUNT = 5;
    private static final int BINDING_COUNT = 4 + LEVEL_COUNT * 2;
    private static final int PUSH_BYTES = 56;

    private final RtContext ctx;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long[] descriptorSets;
    private final long pipelineLayout;
    private final long pipeline;
    private final long[] boundViews = new long[BINDING_COUNT];
    private boolean destroyed;

    private RtBloomFlarePipeline(RtContext ctx, long descriptorSetLayout, long descriptorPool,
                                 long[] descriptorSets, long pipelineLayout, long pipeline) {
        this.ctx = ctx;
        this.descriptorSetLayout = descriptorSetLayout;
        this.descriptorPool = descriptorPool;
        this.descriptorSets = descriptorSets;
        this.pipelineLayout = pipelineLayout;
        this.pipeline = pipeline;
    }

    public static RtBloomFlarePipeline create(RtContext ctx) {
        VkDevice vk = ctx.vk();
        long dsl = 0L;
        long pool = 0L;
        long layout = 0L;
        long pipeline = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings =
                    VkDescriptorSetLayoutBinding.calloc(BINDING_COUNT, stack);
            for (int i = 0; i < BINDING_COUNT; i++) {
                bindings.get(i).binding(i).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            LongBuffer out = stack.mallocLong(1);
            VkDescriptorSetLayoutCreateInfo dslInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(bindings);
            check(VK10.vkCreateDescriptorSetLayout(vk, dslInfo, null, out),
                    "vkCreateDescriptorSetLayout(bloom flare)");
            dsl = out.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, dsl,
                    "bloom flare descriptor layout");

            VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc(1, stack);
            poolSize.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(BINDING_COUNT * 2);
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(2).pPoolSizes(poolSize);
            check(VK10.vkCreateDescriptorPool(vk, poolInfo, null, out),
                    "vkCreateDescriptorPool(bloom flare)");
            pool = out.get(0);

            VkDescriptorSetAllocateInfo allocate = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(stack.longs(dsl, dsl));
            LongBuffer setOut = stack.mallocLong(2);
            check(VK10.vkAllocateDescriptorSets(vk, allocate, setOut),
                    "vkAllocateDescriptorSets(bloom flare)");
            long[] sets = {setOut.get(0), setOut.get(1)};
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, sets[0],
                    "bloom flare rr to scratch set");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, sets[1],
                    "bloom flare scratch to rr set");

            VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack);
            range.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(dsl)).pPushConstantRanges(range);
            check(VK10.vkCreatePipelineLayout(vk, layoutInfo, null, out),
                    "vkCreatePipelineLayout(bloom flare)");
            layout = out.get(0);
            pipeline = createPipeline(ctx, stack, layout);
            return new RtBloomFlarePipeline(ctx, dsl, pool, sets, layout, pipeline);
        } catch (Throwable t) {
            if (pipeline != 0L) VK10.vkDestroyPipeline(vk, pipeline, null);
            if (layout != 0L) VK10.vkDestroyPipelineLayout(vk, layout, null);
            if (pool != 0L) VK10.vkDestroyDescriptorPool(vk, pool, null);
            if (dsl != 0L) VK10.vkDestroyDescriptorSetLayout(vk, dsl, null);
            throw t;
        }
    }

    /**
     * Binds both legal ping-pong directions once. Descriptor sets are never rewritten between recorded
     * pyramid dispatches; GPU execution therefore cannot observe a later Java-side direction change.
     */
    public void setImages(long rrView, long scratchView, long exposureView,
                          long[] brightViews, long[] bloomViews, long flareBokehView) {
        if (brightViews.length != LEVEL_COUNT || bloomViews.length != LEVEL_COUNT) {
            throw new IllegalArgumentException("bloom pyramid must have " + LEVEL_COUNT + " levels");
        }
        long[] canonical = new long[BINDING_COUNT];
        canonical[0] = rrView;
        canonical[1] = scratchView;
        canonical[2] = exposureView;
        System.arraycopy(brightViews, 0, canonical, 3, LEVEL_COUNT);
        System.arraycopy(bloomViews, 0, canonical, 3 + LEVEL_COUNT, LEVEL_COUNT);
        canonical[3 + LEVEL_COUNT * 2] = flareBokehView;
        if (Arrays.equals(boundViews, canonical)) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer[] infos = new VkDescriptorImageInfo.Buffer[BINDING_COUNT * 2];
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(BINDING_COUNT * 2, stack);
            for (int direction = 0; direction < 2; direction++) {
                long source = direction == RtLensPipeline.RR_TO_SCRATCH ? rrView : scratchView;
                long target = direction == RtLensPipeline.RR_TO_SCRATCH ? scratchView : rrView;
                long[] directionViews = new long[BINDING_COUNT];
                directionViews[0] = source;
                directionViews[1] = target;
                directionViews[2] = exposureView;
                System.arraycopy(brightViews, 0, directionViews, 3, LEVEL_COUNT);
                System.arraycopy(bloomViews, 0, directionViews, 3 + LEVEL_COUNT, LEVEL_COUNT);
                directionViews[3 + LEVEL_COUNT * 2] = flareBokehView;
                for (int binding = 0; binding < BINDING_COUNT; binding++) {
                    int index = direction * BINDING_COUNT + binding;
                    infos[index] = VkDescriptorImageInfo.calloc(1, stack);
                    infos[index].get(0).imageView(directionViews[binding])
                            .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                    writes.get(index).sType$Default().dstSet(descriptorSets[direction])
                            .dstBinding(binding).descriptorCount(1)
                            .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                            .pImageInfo(infos[index]);
                }
            }
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
        }
        System.arraycopy(canonical, 0, boundViews, 0, BINDING_COUNT);
    }

    public void record(VkCommandBuffer cmd, int direction, int width, int height,
                       boolean bloomEnabled, boolean flareEnabled,
                       float threshold, float softKnee, float bloomIntensity, float bloomRadius,
                       float flareIntensity, float ghostStrength, float haloStrength,
                       float streakStrength, float flareThreshold, float flareBokehSize) {
        if (!bloomEnabled && !flareEnabled) return;
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "HDR bloom and lens flare")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout,
                    0, stack.longs(descriptorSets[direction]), null);
            ByteBuffer push = stack.malloc(PUSH_BYTES);
            push.putInt(0, width);
            push.putInt(4, height);
            push.putInt(12, (bloomEnabled ? 1 : 0) | (flareEnabled ? 2 : 0));
            push.putFloat(16, threshold);
            push.putFloat(20, softKnee);
            push.putFloat(24, bloomIntensity);
            push.putFloat(28, bloomRadius);
            push.putFloat(32, flareIntensity);
            push.putFloat(36, ghostStrength);
            push.putFloat(40, haloStrength);
            push.putFloat(44, streakStrength);
            push.putFloat(48, flareThreshold);
            push.putFloat(52, flareBokehSize);

            if (bloomEnabled) {
                dispatch(cmd, stack, push, 0, levelSize(width, 0), levelSize(height, 0));
                for (int level = 1; level < LEVEL_COUNT; level++) {
                    barrier(cmd, stack);
                    dispatch(cmd, stack, push, level, levelSize(width, level), levelSize(height, level));
                }
                barrier(cmd, stack);
                dispatch(cmd, stack, push, 5, levelSize(width, 4), levelSize(height, 4));
                for (int mode = 6; mode <= 9; mode++) {
                    int level = 9 - mode;
                    barrier(cmd, stack);
                    dispatch(cmd, stack, push, mode, levelSize(width, level), levelSize(height, level));
                }
            }
            if (flareEnabled) {
                barrier(cmd, stack);
                dispatch(cmd, stack, push, 11, levelSize(width, 1), levelSize(height, 1));
                barrier(cmd, stack);
                dispatch(cmd, stack, push, 12, levelSize(width, 1), levelSize(height, 1));
            }
            barrier(cmd, stack);
            dispatch(cmd, stack, push, 10, width, height);
        }
    }

    private void dispatch(VkCommandBuffer cmd, MemoryStack stack, ByteBuffer push,
                          int mode, int width, int height) {
        push.putInt(8, mode);
        VK10.vkCmdPushConstants(cmd, pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
        VK10.vkCmdDispatch(cmd, (width + 15) / 16, (height + 15) / 16, 1);
    }

    private static void barrier(VkCommandBuffer cmd, MemoryStack stack) {
        VulkanCommandEncoder.memoryBarrier(cmd, stack);
    }

    public static int levelSize(int fullSize, int level) {
        int divisor = 1 << (level + 1);
        return Math.max(1, (fullSize + divisor - 1) / divisor);
    }

    /** Old image views may be destroyed while the pipeline itself stays cached across setting toggles. */
    public void invalidateImages() {
        Arrays.fill(boundViews, 0L);
    }

    public void destroy() {
        if (destroyed) return;
        VkDevice vk = ctx.vk();
        VK10.vkDestroyPipeline(vk, pipeline, null);
        VK10.vkDestroyPipelineLayout(vk, pipelineLayout, null);
        VK10.vkDestroyDescriptorPool(vk, descriptorPool, null);
        VK10.vkDestroyDescriptorSetLayout(vk, descriptorSetLayout, null);
        destroyed = true;
    }

    private static long createPipeline(RtContext ctx, MemoryStack stack, long layout) {
        VkDevice vk = ctx.vk();
        long module = RtShaderModules.load(vk, stack, "bloom_flare.comp.spv");
        try {
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack);
            info.get(0).sType$Default().stage(stage).layout(layout);
            LongBuffer out = stack.mallocLong(1);
            check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, info, null, out),
                    "vkCreateComputePipelines(bloom flare)");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, out.get(0), "bloom flare pipeline");
            return out.get(0);
        } finally {
            VK10.vkDestroyShaderModule(vk, module, null);
        }
    }

}
