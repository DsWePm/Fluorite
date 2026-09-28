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
 * How often the M28 S2 path reservoir's temporal candidate actually survives validation, and how
 * often it reaches the pixel.
 *
 * <p>The same reason {@link RtRestirStats} exists, one store over: the merge can silently never fire
 * -- the reprojection can miss, the drift test can reject every record, the depth gate can sit at a
 * depth nothing reaches -- and the picture then shows nothing but "the switch did nothing", which is
 * indistinguishable from a broken merge without this counter. The D211 lesson, applied before the
 * acceptance run rather than after it: a capture must carry its own attribution.
 *
 * <p>Five lanes, one per frame: temporal records READ and USABLE, spatial records ATTEMPTED and
 * USABLE, merges APPLIED. The shift rates answer the two different questions M24's split already
 * named -- "was this the same point" against "is this the same surface" -- and the applied lane is
 * the D221 gate: how many pixels actually took a merged value (zero until a history carries
 * PATH_RESERVOIR_APPLY_MIN_M independent candidates). The shader samples one pixel in sixteen, so
 * the rates are ratios over a sampled population -- the same convention as RtRestirStats.
 *
 * <p>The counters live in device-local memory and are copied into a per-ring-slot host-visible buffer
 * at the end of the frame that wrote them; reading happens once the slot comes back around
 * ({@code PUSH_RING} frames later, no fence of its own -- the RtRestirStats arrangement verbatim).
 */
public final class RtPathReservoirStats {
    /** Temporal read/usable, spatial attempted/usable, applied, deep-recon, replay rejected/valid. */
    public static final int LANES = 8;
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
        long tRead = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped));
        long tUsable = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 4L));
        long sAttempt = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 8L));
        long sUsable = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 12L));
        long applied = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 16L));
        long deepRecon = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 20L));
        long replayAttempt = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 24L));
        long replayValid = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 28L));
        if (tRead == 0L && sAttempt == 0L && replayAttempt == 0L) {
            return; // nothing attempted: a sky view or a menu carries no information either way
        }
        long now = System.nanoTime();
        if (loggedAt != Long.MIN_VALUE && now - loggedAt < LOG_INTERVAL_NS) {
            return;
        }
        loggedAt = now;
        // Attempts travel with the rates (RtRestirStats's reasoning); the two rates are reported
        // apart because the shifts answer different questions. Applied is the D221 gate's counter --
        // zero while no history carries enough independent candidates, which is the honest reading.
        // deep-recon counts accepted candidates whose receiver reconDepth is above zero. The legacy
        // replay-attempt lane now counts deep spatial candidates rejected while the invalid D227
        // estimator is suspended; the valid lane stays zero until a derived shift replaces it.
        FluoriteMod.LOGGER.info(
                "RT path reservoir reuse (1/16 pixel sample): t={} ({}), s={} ({}), applied {}, "
                        + "deep-recon {}, replay-suspended {} ({})",
                tRead, rate(tUsable, tRead), sAttempt, rate(sUsable, sAttempt), applied, deepRecon,
                replayAttempt, rate(replayValid, replayAttempt));
    }

    private static String rate(long part, long total) {
        return total == 0L ? "--" : String.format(Locale.ROOT, "%.1f%%", 100.0 * part / total);
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
