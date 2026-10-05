package io.github.dswepm.fluorite.rt;

import io.github.dswepm.fluorite.FluoriteConfig;
import io.github.dswepm.fluorite.FluoriteMod;
import io.github.dswepm.fluorite.rt.accel.RtBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.Locale;

/**
 * Diagnostics for the D231 path reuse rebuild. R2a removes the legacy merge lanes;
 * prefix identity and R2b source/replayed hit geometry are measured independently.
 * The shader samples one pixel in sixteen. Readback follows the frame ring,
 * with no additional fence (the same ownership as RtRestirStats).
 */
public final class RtPathReservoirStats {
    /** 0-1 prefix identity, 2-6 source query/status partition, 7-8 metadata identity. */
    public static final int LANES = 9;
    public static final long BYTE_SIZE = (long) LANES * Integer.BYTES;

    /** Slow enough that the log is readable while flying, fast enough to follow walking into a cave. */
    private static final long LOG_INTERVAL_NS = 1_000_000_000L;

    private RtBuffer counters;
    private RtBuffer[] readback;
    private final boolean[] armed;
    private long loggedAt = Long.MIN_VALUE;

    public RtPathReservoirStats(int ringSize) {
        this.armed = new boolean[ringSize];
    }

    /** Same diagnostic checkbox as the M24 counters: one "ReSTIR stats" switch, two stores measured. */
    private static boolean enabled() {
        return FluoriteConfig.Rt.Diagnostics.RESTIR_STATS.value();
    }

    /** Device address for WorldPush, or 0 when the diagnostic is off -- which the shader reads as skip. */
    public long address() {
        return counters != null ? counters.deviceAddress : 0L;
    }

    private void ensure(RtContext ctx, int ringSize) {
        if (counters != null) {
            return;
        }
        counters = ctx.createBuffer(BYTE_SIZE,
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                        | VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT
                        | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                false, "ReSTIR path reservoir counters");
        readback = new RtBuffer[ringSize];
        for (int i = 0; i < ringSize; i++) {
            readback[i] = ctx.createBuffer(BYTE_SIZE, VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT, true,
                    "ReSTIR path reservoir counters readback " + i);
        }
    }

    /** Report what this slot measured the last time it was used, before anything overwrites it. */
    public void reportRecycledSlot(int slot) {
        if (counters == null || !armed[slot]) {
            return;
        }
        armed[slot] = false;
        report(slot);
    }

    /**
     * Create or release the counters, before anything publishes {@link #address()} this frame.
     *
     * <p>The same separation-from-recordReset ordering RtRestirStats documents: WorldPush is serialized
     * early and read late, so releasing after that write would leave the trace on freed memory for one
     * frame. Costs one idle wait on the frame the checkbox flips and nothing else.
     */
    public void prepare(RtContext ctx, int ringSize, boolean reservoirOn) {
        boolean want = enabled() && reservoirOn;
        if (want == (counters != null)) {
            return;
        }
        if (!want) {
            ctx.waitIdle();
            destroy();
            return;
        }
        ensure(ctx, ringSize);
    }

    /** Zero the counters for this frame and arm the slot. Records into the command buffer. */
    public void recordReset(VkCommandBuffer cmd, int slot, boolean reservoirOn) {
        if (counters == null || !reservoirOn) {
            return;
        }
        armed[slot] = true;
        VK10.vkCmdFillBuffer(cmd, counters.handle, 0L, counters.size, 0);
    }

    /** Copy this frame's totals somewhere the CPU may read them once the slot recycles. */
    public void recordCopy(VkCommandBuffer cmd, MemoryStack stack, int slot) {
        if (counters == null || !armed[slot]) {
            return;
        }
        VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
        region.get(0).srcOffset(0L).dstOffset(0L).size(BYTE_SIZE);
        VK10.vkCmdCopyBuffer(cmd, counters.handle, readback[slot].handle, region);
    }

    private void report(int slot) {
        RtBuffer src = readback[slot];
        src.invalidate(0L, BYTE_SIZE);
        long identityAttempt = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped));
        long identityPass = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 4L));
        long geometryAttempt = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 8L));
        long geometryResolved = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 12L));
        long geometryMissing = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 16L));
        long geometryAmbiguous = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 20L));
        long geometryInvalid = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 24L));
        long geometryReplayAttempt = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 28L));
        long geometryReplayPass = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 32L));
        if (identityAttempt == 0L && geometryAttempt == 0L) {
            return;
        }
        long now = System.nanoTime();
        if (loggedAt != Long.MIN_VALUE && now - loggedAt < LOG_INTERVAL_NS) {
            return;
        }
        loggedAt = now;
        // Raw counts avoid rounding a failing 99.9% gate into a passing log rate.
        FluoriteMod.LOGGER.info("RT path reuse diagnostics (1/16 pixel sample): prefix {}/{} ({}); "
                        + "geometry {}/{} ({}; missing {}, ambiguous {}, invalid {}); geometry replay {}/{} ({})",
                identityPass, identityAttempt, percentage(identityPass, identityAttempt),
                geometryResolved, geometryAttempt, percentage(geometryResolved, geometryAttempt),
                geometryMissing, geometryAmbiguous, geometryInvalid,
                geometryReplayPass, geometryReplayAttempt, percentage(geometryReplayPass, geometryReplayAttempt));
    }

    private static String percentage(long passed, long attempted) {
        return attempted == 0L ? "n/a" : String.format(Locale.ROOT, "%.3f%%", 100.0 * passed / attempted);
    }

    public void destroy() {
        if (counters != null) {
            counters.destroy();
            counters = null;
        }
        if (readback != null) {
            for (RtBuffer buffer : readback) {
                if (buffer != null) {
                    buffer.destroy();
                }
            }
            readback = null;
        }
        java.util.Arrays.fill(armed, false);
        loggedAt = Long.MIN_VALUE;
    }
}
