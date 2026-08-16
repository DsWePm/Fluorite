package io.github.dswepm.fluorite.rt.pipeline;

import io.github.dswepm.fluorite.FluoriteMod;
import io.github.dswepm.fluorite.rt.RtContext;
import io.github.dswepm.fluorite.rt.RtShaderModules;
import io.github.dswepm.fluorite.rt.RtDebugLabels;
import io.github.dswepm.fluorite.rt.accel.RtImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
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
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static io.github.dswepm.fluorite.rt.RtContext.check;

/**
 * Five resident 65-cube ACES 2 output LUTs: SDR100 and HDR 500/1000/2000/4000 nit.
 *
 * <p>The fixed official analytic transform bakes these once on the GPU at renderer initialization. The
 * normal display pass then pays one trilinear 3D sample instead of the full JMh tonescale and gamut
 * compression. Keeping all five resident costs 10.48 MiB in RGBA16F and makes mode/preset changes
 * immediate: no runtime rebake, disk cache, or opaque checked-in binary asset.
 */
final class RtAces2Luts {
    static final int LUT_SIZE = 65;
    static final int LUT_COUNT = 5;

    private final RtContext ctx;
    private final RtImage[] images;
    private final long sampler;
    private boolean destroyed;

    private RtAces2Luts(RtContext ctx, RtImage[] images, long sampler) {
        this.ctx = ctx;
        this.images = images;
        this.sampler = sampler;
    }

    static RtAces2Luts create(RtContext ctx) {
        long started = System.nanoTime();
        RtImage[] images = new RtImage[LUT_COUNT];
        long sampler = 0L;
        try {
            String[] names = {"SDR 100", "HDR 500", "HDR 1000", "HDR 2000", "HDR 4000"};
            for (int i = 0; i < LUT_COUNT; i++) {
                images[i] = ctx.createStorageImage3D(LUT_SIZE, LUT_SIZE, LUT_SIZE,
                        VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "ACES 2 " + names[i] + " LUT");
            }
            sampler = createSampler(ctx);
            bake(ctx, images);
            double millis = (System.nanoTime() - started) / 1_000_000.0;
            long bytes = (long) LUT_COUNT * LUT_SIZE * LUT_SIZE * LUT_SIZE * 8L;
            FluoriteMod.LOGGER.info("Baked {} ACES 2 {}^3 RGBA16F LUTs in {} ms ({} MiB)",
                    LUT_COUNT, LUT_SIZE, String.format(java.util.Locale.ROOT, "%.1f", millis),
                    String.format(java.util.Locale.ROOT, "%.2f", bytes / 1048576.0));
            return new RtAces2Luts(ctx, images, sampler);
        } catch (Throwable t) {
            if (sampler != 0L) VK10.vkDestroySampler(ctx.vk(), sampler, null);
            for (RtImage image : images) if (image != null) image.destroy();
            throw t;
        }
    }

    long view(int index) {
        return images[index].view;
    }

    long sampler() {
        return sampler;
    }

    void destroy() {
        if (destroyed) return;
        VK10.vkDestroySampler(ctx.vk(), sampler, null);
        for (RtImage image : images) image.destroy();
        destroyed = true;
    }

    private static long createSampler(RtContext ctx) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK10.VK_FILTER_LINEAR).minFilter(VK10.VK_FILTER_LINEAR)
                    .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .maxLod(0.0f);
            LongBuffer out = stack.mallocLong(1);
            check(VK10.vkCreateSampler(ctx.vk(), sci, null, out), "vkCreateSampler(ACES 2 LUT)");
            long sampler = out.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SAMPLER, sampler, "ACES 2 LUT sampler");
            return sampler;
        }
    }

    private static void bake(RtContext ctx, RtImage[] images) {
        VkDevice vk = ctx.vk();
        long descriptorSetLayout = 0L;
        long descriptorPool = 0L;
        long pipelineLayout = 0L;
        long pipeline = 0L;
        long module = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer out = stack.mallocLong(1);
            VkDescriptorSetLayoutBinding.Buffer binding = VkDescriptorSetLayoutBinding.calloc(1, stack);
            binding.get(0).binding(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            VkDescriptorSetLayoutCreateInfo dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(binding);
            check(VK10.vkCreateDescriptorSetLayout(vk, dslci, null, out),
                    "vkCreateDescriptorSetLayout(ACES 2 LUT bake)");
            descriptorSetLayout = out.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, descriptorSetLayout,
                    "ACES 2 LUT bake descriptor layout");

            VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc(1, stack);
            poolSize.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(LUT_COUNT);
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(LUT_COUNT).pPoolSizes(poolSize);
            check(VK10.vkCreateDescriptorPool(vk, poolInfo, null, out),
                    "vkCreateDescriptorPool(ACES 2 LUT bake)");
            descriptorPool = out.get(0);

            LongBuffer layouts = stack.mallocLong(LUT_COUNT);
            for (int i = 0; i < LUT_COUNT; i++) layouts.put(i, descriptorSetLayout);
            LongBuffer sets = stack.mallocLong(LUT_COUNT);
            VkDescriptorSetAllocateInfo allocate = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(descriptorPool).pSetLayouts(layouts);
            check(VK10.vkAllocateDescriptorSets(vk, allocate, sets),
                    "vkAllocateDescriptorSets(ACES 2 LUT bake)");
            for (int i = 0; i < LUT_COUNT; i++) {
                RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, sets.get(i),
                        "ACES 2 LUT bake set " + i);
                VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack);
                imageInfo.get(0).imageView(images[i].view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
                write.get(0).sType$Default().dstSet(sets.get(i)).dstBinding(0).descriptorCount(1)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(imageInfo);
                VK10.vkUpdateDescriptorSets(vk, write, null);
            }

            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack);
            pushRange.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(Integer.BYTES);
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(descriptorSetLayout)).pPushConstantRanges(pushRange);
            check(VK10.vkCreatePipelineLayout(vk, layoutInfo, null, out),
                    "vkCreatePipelineLayout(ACES 2 LUT bake)");
            pipelineLayout = out.get(0);

            module = RtShaderModules.load(vk, stack, "aces2_lut_bake.comp.spv");
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack);
            pipelineInfo.get(0).sType$Default().stage(stage).layout(pipelineLayout);
            check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, pipelineInfo, null, out),
                    "vkCreateComputePipelines(ACES 2 LUT bake)");
            pipeline = out.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, pipeline, "ACES 2 LUT bake pipeline");
            VK10.vkDestroyShaderModule(vk, module, null);
            module = 0L;

            long bakePipeline = pipeline;
            long bakeLayout = pipelineLayout;
            long[] bakeSets = new long[LUT_COUNT];
            for (int i = 0; i < LUT_COUNT; i++) bakeSets[i] = sets.get(i);
            ctx.submitSync(cmd -> {
                try (MemoryStack submit = MemoryStack.stackPush();
                     RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "bake ACES 2 LUTs")) {
                    VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, bakePipeline);
                    ByteBuffer push = submit.malloc(Integer.BYTES);
                    int groups = (LUT_SIZE + 3) / 4;
                    for (int i = 0; i < LUT_COUNT; i++) {
                        VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                                bakeLayout, 0, submit.longs(bakeSets[i]), null);
                        push.putInt(0, i);
                        VK10.vkCmdPushConstants(cmd, bakeLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
                        VK10.vkCmdDispatch(cmd, groups, groups, groups);
                    }
                    VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, submit);
                    barrier.get(0).sType$Default().srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                            .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT);
                    VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                            VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, barrier, null, null);
                }
            });
        } finally {
            if (module != 0L) VK10.vkDestroyShaderModule(vk, module, null);
            if (pipeline != 0L) VK10.vkDestroyPipeline(vk, pipeline, null);
            if (pipelineLayout != 0L) VK10.vkDestroyPipelineLayout(vk, pipelineLayout, null);
            if (descriptorPool != 0L) VK10.vkDestroyDescriptorPool(vk, descriptorPool, null);
            if (descriptorSetLayout != 0L) VK10.vkDestroyDescriptorSetLayout(vk, descriptorSetLayout, null);
        }
    }

}
