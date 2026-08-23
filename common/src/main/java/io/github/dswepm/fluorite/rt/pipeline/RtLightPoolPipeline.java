package io.github.dswepm.fluorite.rt.pipeline;

import io.github.dswepm.fluorite.rt.RtContext;
import io.github.dswepm.fluorite.rt.RtDebugLabels;
import io.github.dswepm.fluorite.rt.RtShaderModules;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static io.github.dswepm.fluorite.rt.RtContext.check;

/**
 * M26's presampled emitter pool build: one dispatch a frame, no descriptor set at all.
 *
 * <p>NO DESCRIPTOR SET IS THE POINT OF THE CLASS BEING THIS SMALL. Every input is a buffer device
 * address, so the pipeline layout carries a push-constant range and nothing else -- no layout, no pool,
 * no allocated set, and nothing to rewrite when the light arena republishes at an arbitrary frame. The
 * sky bakes next door need sets because they bind storage images; this one does not.
 *
 * <p>It lives here rather than beside those bakes because it is not a sky table. The pool is a shading
 * data structure over the terrain's lights, and filing it under the atmosphere would put the next reader
 * looking for it in the wrong place.
 */
public final class RtLightPoolPipeline {
    /** 7 device addresses and 4 scalars. Well inside the 128-byte guaranteed floor. */
    private static final int PUSH_BYTES = 72;
    /** Must equal [numthreads(64, 1, 1)] in light_pool.comp.slang. */
    private static final int GROUP = 64;

    private final RtContext ctx;
    private final long layout;
    private final long pipeline;
    private boolean destroyed;

    private RtLightPoolPipeline(RtContext ctx, long layout, long pipeline) {
        this.ctx = ctx;
        this.layout = layout;
        this.pipeline = pipeline;
    }

    public static RtLightPoolPipeline create(RtContext ctx) {
        VkDevice vk = ctx.vk();
        long layout = 0L;
        long pipeline = 0L;
        long module = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer out = stack.mallocLong(1);
            VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack);
            range.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default().pPushConstantRanges(range);
            check(VK10.vkCreatePipelineLayout(vk, layoutInfo, null, out),
                    "vkCreatePipelineLayout(light pool build)");
            layout = out.get(0);

            module = RtShaderModules.load(vk, stack, "light_pool.comp.spv");
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack);
            info.get(0).sType$Default().stage(stage).layout(layout);
            check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, info, null, out),
                    "vkCreateComputePipelines(light pool build)");
            pipeline = out.get(0);
            VK10.vkDestroyShaderModule(vk, module, null);
            module = 0L;
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, pipeline, "light pool build");
            return new RtLightPoolPipeline(ctx, layout, pipeline);
        } catch (Throwable t) {
            if (module != 0L) VK10.vkDestroyShaderModule(vk, module, null);
            if (pipeline != 0L) VK10.vkDestroyPipeline(vk, pipeline, null);
            if (layout != 0L) VK10.vkDestroyPipelineLayout(vk, layout, null);
            throw t;
        }
    }

    /**
     * Fill every populated cell's slots for this frame.
     *
     * <p>Silently does nothing when any input is absent, which is the same shape the shader's own guard
     * takes: a pool with no grid to draw from is not an error, it is a world with no published lights.
     */
    public void record(VkCommandBuffer cmd, long worldPushAddr, long lightAddr, long localAliasAddr,
                       long gridCellAddr, long gridSpanAddr, long populatedCellAddr, long poolAddr,
                       int poolDepth, int populatedCells, int frameIndex) {
        if (poolAddr == 0L || poolDepth <= 0 || populatedCells <= 0
                || populatedCellAddr == 0L || gridCellAddr == 0L || gridSpanAddr == 0L
                || localAliasAddr == 0L || lightAddr == 0L) {
            return;
        }
        long slots = (long) populatedCells * (long) poolDepth;
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "light pool build")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            ByteBuffer push = stack.malloc(PUSH_BYTES);
            push.putLong(0, worldPushAddr);
            push.putLong(8, lightAddr);
            push.putLong(16, localAliasAddr);
            push.putLong(24, gridCellAddr);
            push.putLong(32, gridSpanAddr);
            push.putLong(40, populatedCellAddr);
            push.putLong(48, poolAddr);
            push.putInt(56, poolDepth);
            push.putInt(60, populatedCells);
            push.putInt(64, frameIndex);
            push.putInt(68, 0);
            VK10.vkCmdPushConstants(cmd, layout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            long groups = (slots + GROUP - 1) / GROUP;
            VK10.vkCmdDispatch(cmd, (int) Math.min(groups, Integer.MAX_VALUE), 1, 1);
        }
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        VkDevice vk = ctx.vk();
        VK10.vkDestroyPipeline(vk, pipeline, null);
        VK10.vkDestroyPipelineLayout(vk, layout, null);
        destroyed = true;
    }
}
