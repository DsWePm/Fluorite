package io.github.dswepm.fluorite.rt.overlay;

import io.github.dswepm.fluorite.rt.RtContext;
import io.github.dswepm.fluorite.rt.RtDebugLabels;
import io.github.dswepm.fluorite.rt.RtGpuExecutor;
import io.github.dswepm.fluorite.rt.accel.RtBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static io.github.dswepm.fluorite.rt.RtContext.check;

/** M21 full-resolution HDR rain streaks, recorded after RR and before exposure. */
public final class RtRainStreaks {
    private static final int PUSH_BYTES = 96;
    private static final int LIGHT_GROUP = 64;
    private static final int MAX_INSTANCES = 16_384; // high quality (8192) at the maximum 2x density

    private RtContext ctx;
    private RtOverlayPipelines.StorageImageSet images;
    private RtOverlayPipelines.Pipeline pipeline;
    private RtOverlayPipelines.SampledImageSet transmittance;
    private RtOverlayPipelines.AccelStructureSet lightingTlas;
    private ComputePipeline lightingPipeline;
    private RtBuffer radiance;

    private record ComputePipeline(long layout, long handle) {
        void destroy(org.lwjgl.vulkan.VkDevice vk) {
            VK10.vkDestroyPipeline(vk, handle, null);
            VK10.vkDestroyPipelineLayout(vk, layout, null);
        }
    }

    public void record(RtContext context, VkCommandBuffer cmd, long targetView,
                       long sceneDepthView, long exposureDepthView, long transmittanceView,
                       long transmittanceSampler, long tlas,
                       int width, int height, long worldPushAddr, int frameIndex,
                       int instanceCount, float speed, float length, float density,
                       long lightBufAddr, long lightAliasAddr, long lightLocalAliasAddr,
                       long lightGridCellAddr, long lightGridSpanAddr,
                       RtGpuExecutor.GraphicsUse graphicsUse) {
        int drawCount = Math.min(Math.max(instanceCount, 0), MAX_INSTANCES);
        if (drawCount <= 0 || targetView == 0L || sceneDepthView == 0L || exposureDepthView == 0L
                || transmittanceView == 0L || transmittanceSampler == 0L || tlas == 0L) {
            return;
        }
        ensure(context, sceneDepthView, exposureDepthView);
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(context, cmd, "HDR rain streaks")) {
            transmittance.bind(context, transmittanceView, transmittanceSampler);
            long tlasSet = lightingTlas.bind(context, tlas, graphicsUse);
            ByteBuffer push = writePush(stack, worldPushAddr, frameIndex, drawCount, speed, length, density,
                    width, height, lightBufAddr, lightAliasAddr, lightLocalAliasAddr,
                    lightGridCellAddr, lightGridSpanAddr);

            // One invocation shades one procedural instance. It reuses the same Light/alias/grid records
            // as path tracing and writes a compact float4 array consumed by all six vertices afterwards.
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, lightingPipeline.handle);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    lightingPipeline.layout, 0, stack.longs(transmittance.set, tlasSet), null);
            VK10.vkCmdPushConstants(cmd, lightingPipeline.layout,
                    VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, (drawCount + LIGHT_GROUP - 1) / LIGHT_GROUP, 1, 1);
            computeWriteToVertexRead(cmd, stack);

            RtWorldOverlay.beginColorRendering(cmd, stack, targetView, width, height, false);
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout, 0,
                    stack.longs(images.set), null);
            VK10.vkCmdPushConstants(cmd, pipeline.layout,
                    VK10.VK_SHADER_STAGE_VERTEX_BIT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT, 0, push);
            VK10.vkCmdDraw(cmd, 6, drawCount, 0, 0);
            RtWorldOverlay.endRendering(cmd);
        }
    }

    private ByteBuffer writePush(MemoryStack stack, long worldPushAddr, int frameIndex, int instanceCount,
                                 float speed, float length, float density, int width, int height,
                                 long lightBufAddr, long lightAliasAddr, long lightLocalAliasAddr,
                                 long lightGridCellAddr, long lightGridSpanAddr) {
        ByteBuffer push = stack.malloc(PUSH_BYTES);
        push.putLong(0, worldPushAddr).putLong(8, lightBufAddr).putLong(16, lightAliasAddr)
                .putLong(24, lightLocalAliasAddr).putLong(32, lightGridCellAddr)
                .putLong(40, lightGridSpanAddr).putLong(48, radiance.deviceAddress);
        push.putInt(56, instanceCount).putInt(60, frameIndex);
        push.putFloat(64, speed).putFloat(68, length).putFloat(72, density).putFloat(76, 0f);
        push.putFloat(80, width).putFloat(84, height).putFloat(88, 0f).putFloat(92, 0f);
        return push;
    }

    private void ensure(RtContext context, long sceneDepthView, long exposureDepthView) {
        if (pipeline == null) {
            ctx = context;
            int graphicsStages = VK10.VK_SHADER_STAGE_VERTEX_BIT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
            images = RtOverlayPipelines.storageImageSet(context, 2, graphicsStages, "rain streak inputs");
            transmittance = RtOverlayPipelines.sampledImageSet(
                    context, VK10.VK_SHADER_STAGE_COMPUTE_BIT, "rain streak transmittance");
            lightingTlas = RtOverlayPipelines.accelStructureSet(
                    context, VK10.VK_SHADER_STAGE_COMPUTE_BIT, "rain streak lighting TLAS");
            radiance = context.createBuffer((long) MAX_INSTANCES * 4L * Float.BYTES,
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false, "rain streak radiance");
            lightingPipeline = createComputePipeline(context, transmittance.layout, lightingTlas.layout);
            pipeline = new RtOverlayPipelines.Spec("rain_streak.vert.spv", "rain_streak.frag.spv")
                    .blend(RtOverlayPipelines.Blend.ALPHA)
                    .attachment(VK10.VK_FORMAT_R16G16B16A16_SFLOAT)
                    .push(PUSH_BYTES, graphicsStages)
                    .descriptorSetLayout(images.layout)
                    .build(context, "HDR rain streaks");
        }
        // Images change only at resize/resource teardown, both behind a device-idle boundary. bind() is
        // a no-op otherwise, so no in-flight descriptor is rewritten per frame.
        images.bind(context, 0, sceneDepthView);
        images.bind(context, 1, exposureDepthView);
    }

    private static ComputePipeline createComputePipeline(RtContext context,
                                                          long imageLayout, long tlasLayout) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer out = stack.mallocLong(1);
            VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack);
            range.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default().pSetLayouts(stack.longs(imageLayout, tlasLayout))
                    .pPushConstantRanges(range);
            check(VK10.vkCreatePipelineLayout(context.vk(), layoutInfo, null, out),
                    "vkCreatePipelineLayout(rain streak lighting)");
            long layout = out.get(0);
            RtDebugLabels.name(context, VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT,
                    layout, "rain streak lighting pipeline layout");

            long module = RtOverlayPipelines.loadModule(
                    context.vk(), stack, "rain_streak_light.comp.spv");
            try {
                VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                        .sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                        .module(module).pName(stack.UTF8("main"));
                VkComputePipelineCreateInfo.Buffer createInfo = VkComputePipelineCreateInfo.calloc(1, stack);
                createInfo.get(0).sType$Default().stage(stage).layout(layout);
                check(VK10.vkCreateComputePipelines(context.vk(), VK10.VK_NULL_HANDLE,
                        createInfo, null, out), "vkCreateComputePipelines(rain streak lighting)");
                long handle = out.get(0);
                RtDebugLabels.name(context, VK10.VK_OBJECT_TYPE_PIPELINE,
                        handle, "rain streak lighting pipeline");
                return new ComputePipeline(layout, handle);
            } catch (RuntimeException e) {
                VK10.vkDestroyPipelineLayout(context.vk(), layout, null);
                throw e;
            } finally {
                VK10.vkDestroyShaderModule(context.vk(), module, null);
            }
        }
    }

    private static void computeWriteToVertexRead(VkCommandBuffer cmd, MemoryStack stack) {
        VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
        barrier.get(0).sType$Default().srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT);
        VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK10.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT, 0, barrier, null, null);
    }

    public void destroy() {
        if (pipeline != null && ctx != null) {
            pipeline.destroy(ctx.vk());
            images.destroy(ctx.vk());
            lightingPipeline.destroy(ctx.vk());
            transmittance.destroy(ctx.vk());
            lightingTlas.destroy(ctx.vk());
            radiance.destroy();
        }
        pipeline = null;
        images = null;
        lightingPipeline = null;
        transmittance = null;
        lightingTlas = null;
        radiance = null;
        ctx = null;
    }
}
