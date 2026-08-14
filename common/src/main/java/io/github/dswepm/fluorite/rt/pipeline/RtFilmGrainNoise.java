package io.github.dswepm.fluorite.rt.pipeline;

import io.github.dswepm.fluorite.rt.RtContext;
import io.github.dswepm.fluorite.rt.RtDebugLabels;
import io.github.dswepm.fluorite.rt.accel.RtImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
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
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static io.github.dswepm.fluorite.rt.RtContext.check;

/** Resident 64x64 monochrome blue-noise-style grain texture, procedurally baked once at startup. */
final class RtFilmGrainNoise {
    private static final String SHADER_DIR = "/fluorite/rt/";
    private final RtContext ctx;
    private final RtImage image;
    private final long sampler;
    private boolean destroyed;

    private RtFilmGrainNoise(RtContext ctx, RtImage image, long sampler) {
        this.ctx = ctx;
        this.image = image;
        this.sampler = sampler;
    }

    static RtFilmGrainNoise create(RtContext ctx) {
        RtImage image = ctx.createStorageImage(64, 64, VK10.VK_FORMAT_R16_SFLOAT,
                "film grain blue noise 64x64");
        long sampler = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            sampler = createSampler(ctx, stack);
            bake(ctx, image);
            return new RtFilmGrainNoise(ctx, image, sampler);
        } catch (Throwable t) {
            if (sampler != 0L) VK10.vkDestroySampler(ctx.vk(), sampler, null);
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

    void destroy() {
        if (destroyed) return;
        VK10.vkDestroySampler(ctx.vk(), sampler, null);
        image.destroy();
        destroyed = true;
    }

    private static long createSampler(RtContext ctx, MemoryStack stack) {
        VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack).sType$Default()
                .magFilter(VK10.VK_FILTER_NEAREST).minFilter(VK10.VK_FILTER_NEAREST)
                .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT).maxLod(0.0f);
        LongBuffer out = stack.mallocLong(1);
        check(VK10.vkCreateSampler(ctx.vk(), info, null, out), "vkCreateSampler(film grain noise)");
        return out.get(0);
    }

    private static void bake(RtContext ctx, RtImage image) {
        VkDevice vk = ctx.vk();
        long dsl = 0L;
        long pool = 0L;
        long layout = 0L;
        long pipeline = 0L;
        long module = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer out = stack.mallocLong(1);
            VkDescriptorSetLayoutBinding.Buffer binding = VkDescriptorSetLayoutBinding.calloc(1, stack);
            binding.get(0).binding(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            VkDescriptorSetLayoutCreateInfo dslInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(binding);
            check(VK10.vkCreateDescriptorSetLayout(vk, dslInfo, null, out),
                    "vkCreateDescriptorSetLayout(film grain bake)");
            dsl = out.get(0);

            VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc(1, stack);
            poolSize.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1);
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(1).pPoolSizes(poolSize);
            check(VK10.vkCreateDescriptorPool(vk, poolInfo, null, out),
                    "vkCreateDescriptorPool(film grain bake)");
            pool = out.get(0);
            VkDescriptorSetAllocateInfo allocate = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(stack.longs(dsl));
            check(VK10.vkAllocateDescriptorSets(vk, allocate, out),
                    "vkAllocateDescriptorSets(film grain bake)");
            long set = out.get(0);

            VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack);
            imageInfo.get(0).imageView(image.view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
            write.get(0).sType$Default().dstSet(set).dstBinding(0).descriptorCount(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(imageInfo);
            VK10.vkUpdateDescriptorSets(vk, write, null);

            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(dsl));
            check(VK10.vkCreatePipelineLayout(vk, layoutInfo, null, out),
                    "vkCreatePipelineLayout(film grain bake)");
            layout = out.get(0);

            module = loadModule(vk, stack, "film_grain_noise_bake.comp.spv");
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack);
            info.get(0).sType$Default().stage(stage).layout(layout);
            check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, info, null, out),
                    "vkCreateComputePipelines(film grain bake)");
            pipeline = out.get(0);
            VK10.vkDestroyShaderModule(vk, module, null);
            module = 0L;

            long bakePipeline = pipeline;
            long bakeLayout = layout;
            ctx.submitSync(cmd -> {
                try (MemoryStack submit = MemoryStack.stackPush();
                     RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "bake film grain noise")) {
                    VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, bakePipeline);
                    VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                            bakeLayout, 0, submit.longs(set), null);
                    VK10.vkCmdDispatch(cmd, 8, 8, 1);
                }
            });
        } finally {
            if (module != 0L) VK10.vkDestroyShaderModule(vk, module, null);
            if (pipeline != 0L) VK10.vkDestroyPipeline(vk, pipeline, null);
            if (layout != 0L) VK10.vkDestroyPipelineLayout(vk, layout, null);
            if (pool != 0L) VK10.vkDestroyDescriptorPool(vk, pool, null);
            if (dsl != 0L) VK10.vkDestroyDescriptorSetLayout(vk, dsl, null);
        }
    }

    private static long loadModule(VkDevice vk, MemoryStack stack, String name) {
        byte[] bytes;
        try (InputStream in = RtFilmGrainNoise.class.getResourceAsStream(SHADER_DIR + name)) {
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
