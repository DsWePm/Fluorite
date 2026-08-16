package io.github.dswepm.fluorite.rt.pipeline;

import io.github.dswepm.fluorite.rt.RtContext;
import io.github.dswepm.fluorite.rt.RtShaderModules;
import io.github.dswepm.fluorite.rt.RtDebugLabels;
import io.github.dswepm.fluorite.rt.accel.RtImage;
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
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static io.github.dswepm.fluorite.rt.RtContext.check;

/** One scene-linear 65-cube creative grading LUT, rebaked in the frame command buffer only on edits. */
final class RtCreativeGradingLut {
    static final int LUT_SIZE = 65;
    private static final int PUSH_BYTES = 112;

    private final RtContext ctx;
    private final RtImage image;
    private final long sampler;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long descriptorSet;
    private final long pipelineLayout;
    private final long pipeline;
    private RtDisplayPipeline.CreativeGrade lastGrade;
    private boolean destroyed;

    private RtCreativeGradingLut(RtContext ctx, RtImage image, long sampler,
                                 long descriptorSetLayout, long descriptorPool,
                                 long descriptorSet, long pipelineLayout, long pipeline) {
        this.ctx = ctx;
        this.image = image;
        this.sampler = sampler;
        this.descriptorSetLayout = descriptorSetLayout;
        this.descriptorPool = descriptorPool;
        this.descriptorSet = descriptorSet;
        this.pipelineLayout = pipelineLayout;
        this.pipeline = pipeline;
    }

    static RtCreativeGradingLut create(RtContext ctx) {
        VkDevice vk = ctx.vk();
        RtImage image = ctx.createStorageImage3D(LUT_SIZE, LUT_SIZE, LUT_SIZE,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "creative grading scene-linear LUT");
        long sampler = 0L;
        long dsl = 0L;
        long pool = 0L;
        long layout = 0L;
        long pipeline = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer out = stack.mallocLong(1);
            sampler = createSampler(ctx, stack);

            VkDescriptorSetLayoutBinding.Buffer binding = VkDescriptorSetLayoutBinding.calloc(1, stack);
            binding.get(0).binding(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            VkDescriptorSetLayoutCreateInfo dslInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(binding);
            check(VK10.vkCreateDescriptorSetLayout(vk, dslInfo, null, out),
                    "vkCreateDescriptorSetLayout(creative grading LUT)");
            dsl = out.get(0);

            VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc(1, stack);
            poolSize.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1);
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(1).pPoolSizes(poolSize);
            check(VK10.vkCreateDescriptorPool(vk, poolInfo, null, out),
                    "vkCreateDescriptorPool(creative grading LUT)");
            pool = out.get(0);

            VkDescriptorSetAllocateInfo allocate = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(stack.longs(dsl));
            check(VK10.vkAllocateDescriptorSets(vk, allocate, out),
                    "vkAllocateDescriptorSets(creative grading LUT)");
            long set = out.get(0);
            VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack);
            imageInfo.get(0).imageView(image.view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
            write.get(0).sType$Default().dstSet(set).dstBinding(0).descriptorCount(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(imageInfo);
            VK10.vkUpdateDescriptorSets(vk, write, null);

            VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack);
            range.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(dsl)).pPushConstantRanges(range);
            check(VK10.vkCreatePipelineLayout(vk, layoutInfo, null, out),
                    "vkCreatePipelineLayout(creative grading LUT)");
            layout = out.get(0);

            pipeline = createPipeline(ctx, stack, layout);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_IMAGE, image.image, "creative grading LUT image");
            return new RtCreativeGradingLut(ctx, image, sampler, dsl, pool, set, layout, pipeline);
        } catch (Throwable t) {
            if (pipeline != 0L) VK10.vkDestroyPipeline(vk, pipeline, null);
            if (layout != 0L) VK10.vkDestroyPipelineLayout(vk, layout, null);
            if (pool != 0L) VK10.vkDestroyDescriptorPool(vk, pool, null);
            if (dsl != 0L) VK10.vkDestroyDescriptorSetLayout(vk, dsl, null);
            if (sampler != 0L) VK10.vkDestroySampler(vk, sampler, null);
            image.destroy();
            throw t;
        }
    }

    long view() {
        return image.view;
    }

    long sampler() {
        return sampler;
    }

    boolean recordIfDirty(VkCommandBuffer cmd, RtDisplayPipeline.CreativeGrade grade) {
        if (!grade.enabled() || grade.equals(lastGrade)) return false;
        // This is deliberately one LUT (2.10 MiB), not an in-flight ring.  A live slider may rebake it
        // while older display command buffers still sample the same image, so finish those rare edit
        // frames before recording the overwrite. Stable gameplay never enters this branch.
        ctx.waitIdle();
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "rebake creative grading LUT")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    pipelineLayout, 0, stack.longs(descriptorSet), null);
            ByteBuffer push = stack.malloc(PUSH_BYTES);
            push.putInt(0, 1);
            push.putFloat(4, grade.temperatureK());
            push.putFloat(8, grade.tint());
            push.putFloat(12, grade.globalContrast());
            push.putFloat(16, grade.globalSaturation());
            push.putFloat(20, grade.hueDegrees());
            putZone(push, 24, grade.shadows());
            putZone(push, 48, grade.midtones());
            putZone(push, 72, grade.highlights());
            push.putFloat(96, grade.shadowBoundaryEv());
            push.putFloat(100, grade.highlightBoundaryEv());
            push.putFloat(104, 0.0f);
            push.putFloat(108, 0.0f);
            VK10.vkCmdPushConstants(cmd, pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            int groups = (LUT_SIZE + 3) / 4;
            VK10.vkCmdDispatch(cmd, groups, groups, groups);
        }
        lastGrade = grade;
        return true;
    }

    private static void putZone(ByteBuffer push, int offset, RtDisplayPipeline.TonalGrade zone) {
        push.putFloat(offset, zone.exposureEv());
        push.putFloat(offset + 4, zone.redEv());
        push.putFloat(offset + 8, zone.greenEv());
        push.putFloat(offset + 12, zone.blueEv());
        push.putFloat(offset + 16, zone.saturation());
        push.putFloat(offset + 20, zone.contrast());
    }

    void destroy() {
        if (destroyed) return;
        VkDevice vk = ctx.vk();
        VK10.vkDestroyPipeline(vk, pipeline, null);
        VK10.vkDestroyPipelineLayout(vk, pipelineLayout, null);
        VK10.vkDestroyDescriptorPool(vk, descriptorPool, null);
        VK10.vkDestroyDescriptorSetLayout(vk, descriptorSetLayout, null);
        VK10.vkDestroySampler(vk, sampler, null);
        image.destroy();
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
        check(VK10.vkCreateSampler(ctx.vk(), info, null, out), "vkCreateSampler(creative grading LUT)");
        return out.get(0);
    }

    private static long createPipeline(RtContext ctx, MemoryStack stack, long layout) {
        VkDevice vk = ctx.vk();
        long module = RtShaderModules.load(vk, stack, "creative_grading_lut.comp.spv");
        try {
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack);
            info.get(0).sType$Default().stage(stage).layout(layout);
            LongBuffer out = stack.mallocLong(1);
            check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, info, null, out),
                    "vkCreateComputePipelines(creative grading LUT)");
            return out.get(0);
        } finally {
            VK10.vkDestroyShaderModule(vk, module, null);
        }
    }

}
