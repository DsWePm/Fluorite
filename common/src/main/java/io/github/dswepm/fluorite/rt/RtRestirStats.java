package io.github.dswepm.fluorite.rt;

import io.github.dswepm.fluorite.FluoriteConfig;
import io.github.dswepm.fluorite.FluoriteMod;
import io.github.dswepm.fluorite.rt.accel.RtBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.system.MemoryStack;

import java.util.Locale;

/**
 * How often a stored reservoir is actually reusable, per bounce depth.
 *
 * <p><b>This is the number M24 is on trial for.</b> The reservoir store costs 265 MB per depth per frame
 * half at 1080p, and the case for the deep ones rests entirely on a claim nobody has measured: that this
 * frame's vertex at depth 3 is often enough the same surface as last frame's to be worth keeping. If the
 * acceptance rate there is in single digits, the depth is paying every frame for data that is rejected
 * every frame, and the answer needs no argument. {@code RESTIR_REUSE_DEPTH}'s own documentation promises
 * this measurement; this is it.
 *
 * <p>The counters live in device-local memory and are copied into a per-ring-slot host-visible buffer at
 * the end of the frame that wrote them. Reading happens only once that slot comes back around, which is
 * {@code PUSH_RING} frames later and therefore needs no fence of its own — the same trick the water probe
 * uses, and the reason neither diagnostic introduces a readback stall.
 *
 * <p>The shader samples one pixel in sixteen (see {@code restirStatsAccumulate}). That is invisible here
 * by design: a ratio over a sampled population is the same ratio, and this class deliberately reports the
 * rate rather than the raw counts so nobody reads the totals as a pixel census.
 */
public final class RtRestirStats {
    /** Depths a pixel can account for. Matches RESTIR_MAX_STAT_DEPTH in restir.slang and the knob's cap. */
    public static final int MAX_DEPTH = 8;
    /** Temporal attempts and accepts, then spatial attempts and accepts. Matches RESTIR_STAT_LANES. */
    public static final int LANES = 4;
    public static final long BYTE_SIZE = (long) MAX_DEPTH * LANES * Integer.BYTES;

    /** Slow enough that the log is readable while flying, fast enough to follow walking into a cave. */
    private static final long LOG_INTERVAL_NS = 1_000_000_000L;

    private RtBuffer counters;
    private RtBuffer[] readback;
    private final boolean[] armed;
    private final int[] armedDepth;
    private long loggedAt = Long.MIN_VALUE;

    public RtRestirStats(int ringSize) {
        this.armed = new boolean[ringSize];
        this.armedDepth = new int[ringSize];
    }

    public static boolean enabled() {
        return FluoriteConfig.Rt.Diagnostics.RESTIR_STATS.value();
    }

    /** Device address for WorldPush, or 0 when the diagnostic is off — which the shader reads as "skip". */
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
                false, "ReSTIR reuse counters");
        readback = new RtBuffer[ringSize];
        for (int i = 0; i < ringSize; i++) {
            readback[i] = ctx.createBuffer(BYTE_SIZE, VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT, true,
                    "ReSTIR reuse counters readback " + i);
        }
    }

    /**
     * Report what this slot measured the last time it was used, before anything overwrites it.
     *
     * <p>Called with the slot already advanced and the ring's own await already done, so these bytes are
     * from a frame the GPU finished {@code PUSH_RING} frames ago. That is what keeps the readback free of
     * a fence of its own — the same arrangement the water probe uses.
     */
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
     * <p>SEPARATE FROM {@link #recordReset} ON PURPOSE, and the ordering is the whole reason: WorldPush is
     * serialized early in the frame and the trace reads it late, so a buffer released after that write
     * would leave the trace pointed at freed memory for one frame — and up to {@code PUSH_RING} older
     * frames still in flight hold the same address in their own push buffers. Hence the idle wait on the
     * way down. It costs a stall on the frame the checkbox is clicked and nothing else, ever.
     */
    public void prepare(RtContext ctx, int ringSize, int reuseDepth) {
        boolean want = enabled() && reuseDepth > 0;
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
    public void recordReset(VkCommandBuffer cmd, int slot, int reuseDepth) {
        if (counters == null || reuseDepth <= 0) {
            return;
        }
        armed[slot] = true;
        armedDepth[slot] = Math.min(reuseDepth, MAX_DEPTH);
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
        long[][] lane = new long[LANES][armedDepth[slot]];
        long total = 0L;
        for (int d = 0; d < armedDepth[slot]; d++) {
            for (int l = 0; l < LANES; l++) {
                lane[l][d] = Integer.toUnsignedLong(
                        MemoryUtil.memGetInt(src.mapped + (long) (d * LANES + l) * Integer.BYTES));
            }
            total += lane[0][d];
        }
        // Read before throttling, and skip an empty frame WITHOUT spending the interval on it. A frame that
        // shaded no reservoir vertex at all — the menu, a loading screen, a pure-sky view — carries no
        // information, and letting it consume the budget would silence the next second that did.
        if (total == 0L) {
            return;
        }
        long now = System.nanoTime();
        if (loggedAt != Long.MIN_VALUE && now - loggedAt < LOG_INTERVAL_NS) {
            return;
        }
        loggedAt = now;
        StringBuilder line = new StringBuilder();
        for (int d = 0; d < armedDepth[slot]; d++) {
            if (d > 0) {
                line.append(' ');
            }
            // The attempt count travels with each rate: a depth almost nothing reaches can post a
            // flattering percentage off a handful of vertices, and the pair is what makes that visible.
            // The two rates are reported apart because they answer different questions -- "was this the
            // same point" and "was this the same surface" -- and averaging them would report neither.
            line.append('d').append(d).append("=t");
            appendRate(line, lane[0][d], lane[1][d]);
            if (lane[2][d] != 0L) {
                line.append("/s");
                appendRate(line, lane[2][d], lane[3][d]);
            }
        }
        FluoriteMod.LOGGER.info("RT ReSTIR reuse acceptance (1/16 pixel sample, t=temporal s=spatial): {}",
                line);
    }

    private static void appendRate(StringBuilder line, long attempts, long accepts) {
        line.append(attempts == 0L ? "--"
                : String.format(Locale.ROOT, "%.1f%%", 100.0 * accepts / attempts));
        line.append('(').append(attempts).append(')');
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
