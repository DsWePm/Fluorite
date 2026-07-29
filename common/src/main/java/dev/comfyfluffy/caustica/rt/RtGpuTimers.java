package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaMod;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;

import java.nio.LongBuffer;

/**
 * GPU-side timing for a handful of named zones, resolved into {@link RtFrameStats}.
 *
 * <p>Every other timer in this renderer measures the CPU thread that <em>records</em> commands, which for a
 * path tracer says nothing at all: {@code frame.traceIndirect} sat at five microseconds while the trace it
 * names cost milliseconds on the device. Anything gated on how expensive a shader is — every performance
 * budget in the rendering work — needs the device's own clock, and that means timestamp queries.
 *
 * <p>Slots mirror the caller's frame ring and the caller resolves a slot only after awaiting the graphics
 * use that wrote it, so results are always complete and the read never blocks. Availability is still
 * checked, because a lost or reset device leaves queries unwritten and a stale zero is worse than a gap.
 */
public final class RtGpuTimers {
    /** Two timestamps per zone: one before the work, one after. */
    private static final int STAMPS_PER_ZONE = 2;

    private final long pool;
    private final String[] zoneNames;
    private final int slots;
    private final double nanosPerTick;
    private final long validBitsMask;
    private boolean poolNeedsReset = true;

    private RtGpuTimers(long pool, String[] zoneNames, int slots, double nanosPerTick, long validBitsMask) {
        this.pool = pool;
        this.zoneNames = zoneNames;
        this.slots = slots;
        this.nanosPerTick = nanosPerTick;
        this.validBitsMask = validBitsMask;
    }

    /**
     * Creates a pool for {@code zoneNames.length} zones across {@code slots} frames, or returns null when
     * the device cannot timestamp on this queue.
     *
     * <p>Null rather than a no-op instance: a renderer that cannot measure itself should say so once at
     * startup, not report zeroes that read like a fast frame.
     */
    public static RtGpuTimers create(RtContext ctx, int slots, String... zoneNames) {
        int validBits = ctx.graphicsTimestampValidBits();
        float period = ctx.timestampPeriodNanos();
        if (validBits == 0 || period <= 0.0f) {
            CausticaMod.LOGGER.info("GPU timing unavailable: queue reports {} valid timestamp bits, "
                    + "period {}ns. Frame stats will carry CPU record times only.", validBits, period);
            return null;
        }
        // 64 valid bits means every bit counts, and 1L << 64 is not the mask you asked for.
        long mask = validBits >= 64 ? -1L : (1L << validBits) - 1L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkQueryPoolCreateInfo info = VkQueryPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .queryType(VK10.VK_QUERY_TYPE_TIMESTAMP)
                    .queryCount(slots * zoneNames.length * STAMPS_PER_ZONE);
            long[] out = new long[1];
            int result = VK10.vkCreateQueryPool(ctx.vk(), info, null, out);
            if (result != VK10.VK_SUCCESS) {
                CausticaMod.LOGGER.warn("Could not create the GPU timing query pool ({}); "
                        + "frame stats will carry CPU record times only.", result);
                return null;
            }
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_QUERY_POOL, out[0], "rt gpu timers");
            return new RtGpuTimers(out[0], zoneNames.clone(), slots, period, mask);
        }
    }

    /**
     * Recycles one slot's queries. Must be recorded before the slot's zones and outside a render pass.
     *
     * <p>The first call resets the whole pool, not just this slot: a query that has never been written is
     * in an undefined state, and reading one is undefined behaviour rather than simply an unavailable
     * result. Slots the ring has not reached yet are read before they are first written.
     */
    public void beginFrame(VkCommandBuffer cmd, int slot) {
        if (poolNeedsReset) {
            poolNeedsReset = false;
            VK10.vkCmdResetQueryPool(cmd, pool, 0, slots * zoneNames.length * STAMPS_PER_ZONE);
            return;
        }
        VK10.vkCmdResetQueryPool(cmd, pool, base(slot, 0), zoneNames.length * STAMPS_PER_ZONE);
    }

    /** Timestamps the start of a zone: everything submitted after this point is inside it. */
    public void begin(VkCommandBuffer cmd, int slot, int zone) {
        VK10.vkCmdWriteTimestamp(cmd, VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, pool, base(slot, zone));
    }

    /** Timestamps the end of a zone, once everything in it has completed. */
    public void end(VkCommandBuffer cmd, int slot, int zone) {
        VK10.vkCmdWriteTimestamp(cmd, VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, pool, base(slot, zone) + 1);
    }

    /**
     * Reads one slot's zones and adds them to the frame profile under {@code <zoneName>}.
     *
     * <p>Call only once the graphics work that wrote the slot has completed — the caller already awaits
     * that to reuse the slot's buffers, so this costs no extra synchronisation.
     */
    public void resolve(RtContext ctx, int slot) {
        if (!RtFrameStats.enabled()) {
            return;
        }
        int stamps = zoneNames.length * STAMPS_PER_ZONE;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Two longs per query: the value and its availability. Asking for availability instead of
            // waiting keeps a slot that was never written (early frames, a reset device) from stalling the
            // render thread on a query that will never arrive.
            LongBuffer results = stack.mallocLong(stamps * 2L > Integer.MAX_VALUE ? 0 : stamps * 2);
            int status = VK10.vkGetQueryPoolResults(ctx.vk(), pool, base(slot, 0), stamps, results,
                    2L * Long.BYTES,
                    VK10.VK_QUERY_RESULT_64_BIT | VK10.VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
            if (status != VK10.VK_SUCCESS && status != VK10.VK_NOT_READY) {
                return;
            }
            for (int zone = 0; zone < zoneNames.length; zone++) {
                int i = zone * STAMPS_PER_ZONE * 2;
                if (results.get(i + 1) == 0L || results.get(i + 3) == 0L) {
                    continue; // one or both ends unavailable
                }
                long start = results.get(i) & validBitsMask;
                long endStamp = results.get(i + 2) & validBitsMask;
                // Unsigned wraparound of the device counter, not an out-of-order pair.
                long ticks = endStamp >= start ? endStamp - start : (validBitsMask - start) + endStamp + 1;
                RtFrameStats.FRAME.addStage(zoneNames[zone], Math.round(ticks * nanosPerTick));
            }
        }
    }

    public void destroy(RtContext ctx) {
        VK10.vkDestroyQueryPool(ctx.vk(), pool, null);
    }

    private int base(int slot, int zone) {
        return (slot * zoneNames.length + zone) * STAMPS_PER_ZONE;
    }
}
