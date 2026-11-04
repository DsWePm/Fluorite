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
    /** 0-12 existing probes; 13-16 D candidates/records/depths; 17-18 source factors; 19 rejected trees. */
    // 20-25 selected-lobe source evaluation, 26-33 represented contribution/PDF error measurements.
    // 34-39 prefix composition, direction-plus-source-evaluation and incident contributions.
    // 40-43 isolate the direction codec: attempts, bit changes, PDF error and weight error.
    // 44-47 sky/emitter candidates and actual written sky/emitter records; solar is the remainder.
    // 48-59 fresh source scattering replay: depth attempts/passes, classified failures and maxima.
    public static final int LANES = 60;
    public static final long BYTE_SIZE = (long) LANES * Integer.BYTES;

    /** Slow enough that the log is readable while flying, fast enough to follow walking into a cave. */
    private static final long LOG_INTERVAL_NS = 1_000_000_000L;

    private RtBuffer counters;
    private RtBuffer[] readback;
    private final boolean[] armed;
    private long loggedAt = Long.MIN_VALUE;
    private final FrameTotals allFrames = new FrameTotals();

    /** Preserve every completed readback, including failures between the periodic log frames. */
    static final class FrameTotals {
        private final long[] lanes = new long[LANES];
        long frames;

        void add(long mapped) {
            frames++;
            for (int lane = 0; lane < LANES; lane++) {
                long value = Integer.toUnsignedLong(MemoryUtil.memGetInt(mapped + lane * 4L));
                // Nonnegative float error maxima have the same ordering as their unsigned bits.
                lanes[lane] = switch (lane) {
                    case 24, 25, 31, 32, 34, 36, 38, 42, 43, 56, 57, 58 -> Math.max(lanes[lane], value);
                    default -> lanes[lane] + value;
                };
            }
        }

        long count(int lane) { return lanes[lane]; }
        float maximum(int lane) { return Float.intBitsToFloat((int) lanes[lane]); }

        void clear() {
            java.util.Arrays.fill(lanes, 0L);
            frames = 0L;
        }
    }

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
        long footprintAttempt = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 36L));
        long footprintAccepted = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 40L));
        long footprintK2 = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 44L));
        long footprintK3 = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 48L));
        long eventCandidates = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 52L));
        long eventRecords = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 56L));
        long eventK2 = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 60L));
        long eventDeep = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 64L));
        long sourceFactorAttempt = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 68L));
        long sourceFactorPass = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 72L));
        long eventRejected = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 76L));
        long lobeAttempt = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 80L));
        long lobeInvalid = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 84L));
        long lobePdfLow = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 88L));
        long lobeWeightLow = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 92L));
        float lobePdfMax = Float.intBitsToFloat(MemoryUtil.memGetInt(src.mapped + 96L));
        float lobeWeightMax = Float.intBitsToFloat(MemoryUtil.memGetInt(src.mapped + 100L));
        long packedAttempt = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 104L));
        long packedInvalid = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 108L));
        long packedLow = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 112L));
        long packedMedium = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 116L));
        long packedHigh = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 120L));
        float packedMax = Float.intBitsToFloat(MemoryUtil.memGetInt(src.mapped + 124L));
        float packedPdfMax = Float.intBitsToFloat(MemoryUtil.memGetInt(src.mapped + 128L));
        long packedPdfHigh = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 132L));
        float compositionMax = Float.intBitsToFloat(MemoryUtil.memGetInt(src.mapped + 136L));
        long compositionAnomalous = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 140L));
        float directionMax = Float.intBitsToFloat(MemoryUtil.memGetInt(src.mapped + 144L));
        long directionHigh = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 148L));
        float incidentMax = Float.intBitsToFloat(MemoryUtil.memGetInt(src.mapped + 152L));
        long incidentHigh = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 156L));
        long directionCodecAttempt = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 160L));
        long directionCodecBitChanges = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 164L));
        float directionCodecPdfMax = Float.intBitsToFloat(MemoryUtil.memGetInt(src.mapped + 168L));
        float directionCodecWeightMax = Float.intBitsToFloat(MemoryUtil.memGetInt(src.mapped + 172L));
        long skyCandidates = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 176L));
        long emitterCandidates = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 180L));
        long skyRecords = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 184L));
        long emitterRecords = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 188L));
        long sourceReplayK2 = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 192L));
        long sourceReplayDeep = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 196L));
        long sourceReplayScope = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 200L));
        long sourceReplayMiss = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 204L));
        long sourceReplayGeometry = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 208L));
        long sourceReplayInvalid = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 212L));
        long sourceReplayK2Pass = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 216L));
        long sourceReplayDeepPass = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 220L));
        float sourceReplayScatterMax = Float.intBitsToFloat(MemoryUtil.memGetInt(src.mapped + 224L));
        float sourceReplayPdfMax = Float.intBitsToFloat(MemoryUtil.memGetInt(src.mapped + 228L));
        float sourceReplayJacMax = Float.intBitsToFloat(MemoryUtil.memGetInt(src.mapped + 232L));
        long sourceReplayNumeric = Integer.toUnsignedLong(MemoryUtil.memGetInt(src.mapped + 236L));
        allFrames.add(src.mapped);
        if (lobeInvalid != 0L || lobeAttempt != lobePdfLow || lobeAttempt != lobeWeightLow
                || sourceReplayScope + sourceReplayMiss + sourceReplayGeometry + sourceReplayInvalid + sourceReplayNumeric != 0L) {
            FluoriteMod.LOGGER.warn("RT path reuse failed readback {}: lobe {} (invalid {}, pdf<=0.1% {}, weight<=0.1% {}, "
                            + "max pdf {}, weight {}); fresh scope {}, miss {}, geometry {}, invalid {}, numeric {}",
                    allFrames.frames, lobeAttempt, lobeInvalid, lobePdfLow, lobeWeightLow,
                    relativePercentage(lobePdfMax), relativePercentage(lobeWeightMax), sourceReplayScope,
                    sourceReplayMiss, sourceReplayGeometry, sourceReplayInvalid, sourceReplayNumeric);
        }
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
                        + "geometry {}/{} ({}; missing {}, ambiguous {}, invalid {}); geometry replay {}/{} ({}); "
                        + "D footprint {}/{} ({}; first k2 {}, k3 {}); "
                        + "D events {} -> records {} (k2 {}, deep {}, rejected {}); source factors {}/{} ({}); "
                        + "lobe eval {} (invalid {}, pdf<=0.1% {}, weight<=0.1% {}, max pdf {}, weight {}); "
                        + "packed source {} (invalid {}, <=0.1% {}, <=1% {}, >1% {}, max {}, pdf>1% {}, max pdf {}); "
                        + "source composition max {} (>1% or invalid {}); packing isolation max dir {} (>1% {}), incident {} (>1% {}); "
                        + "direction codec {} (bits changed {}, max pdf {}, weight {}); "
                        + "D endpoints candidates sky {} emitter {}, records sky {} emitter {}; "
                        + "source scatter replay k2 {}/{} deep {}/{} (scope {}, miss {}, geometry {}, invalid {}, numeric {}); "
                        + "max scatter {}, pdf {}, jac {}",
                identityPass, identityAttempt, percentage(identityPass, identityAttempt),
                geometryResolved, geometryAttempt, percentage(geometryResolved, geometryAttempt),
                geometryMissing, geometryAmbiguous, geometryInvalid,
                geometryReplayPass, geometryReplayAttempt, percentage(geometryReplayPass, geometryReplayAttempt),
                footprintAccepted, footprintAttempt, percentage(footprintAccepted, footprintAttempt), footprintK2, footprintK3,
                eventCandidates, eventRecords, eventK2, eventDeep, eventRejected,
                sourceFactorPass, sourceFactorAttempt, percentage(sourceFactorPass, sourceFactorAttempt),
                lobeAttempt, lobeInvalid, lobePdfLow, lobeWeightLow, relativePercentage(lobePdfMax), relativePercentage(lobeWeightMax),
                packedAttempt, packedInvalid, packedLow, packedMedium, packedHigh, relativePercentage(packedMax),
                packedPdfHigh, relativePercentage(packedPdfMax), relativePercentage(compositionMax), compositionAnomalous,
                relativePercentage(directionMax), directionHigh, relativePercentage(incidentMax), incidentHigh,
                directionCodecAttempt, directionCodecBitChanges,
                relativePercentage(directionCodecPdfMax), relativePercentage(directionCodecWeightMax),
                skyCandidates, emitterCandidates, skyRecords, emitterRecords,
                sourceReplayK2Pass, sourceReplayK2, sourceReplayDeepPass, sourceReplayDeep,
                sourceReplayScope, sourceReplayMiss, sourceReplayGeometry, sourceReplayInvalid, sourceReplayNumeric,
                relativePercentage(sourceReplayScatterMax), relativePercentage(sourceReplayPdfMax),
                relativePercentage(sourceReplayJacMax));
        logCumulative();
    }

    private void logCumulative() {
        if (allFrames.frames == 0L) return;
        FluoriteMod.LOGGER.info("RT path reuse cumulative: readback frames {}, lobe {} (invalid {}, pdf<=0.1% {}, "
                        + "weight<=0.1% {}, max pdf {}, weight {}); fresh k2 {}/{} deep {}/{} "
                        + "(scope {}, miss {}, geometry {}, invalid {}, numeric {}); max scatter {}, pdf {}, jac {}; "
                        + "direction codec {} (bits changed {}, max pdf {}, weight {}); incident max {}",
                allFrames.frames, allFrames.count(20), allFrames.count(21), allFrames.count(22), allFrames.count(23),
                relativePercentage(allFrames.maximum(24)), relativePercentage(allFrames.maximum(25)),
                allFrames.count(54), allFrames.count(48), allFrames.count(55), allFrames.count(49),
                allFrames.count(50), allFrames.count(51), allFrames.count(52), allFrames.count(53), allFrames.count(59),
                relativePercentage(allFrames.maximum(56)), relativePercentage(allFrames.maximum(57)),
                relativePercentage(allFrames.maximum(58)), allFrames.count(40), allFrames.count(41),
                relativePercentage(allFrames.maximum(42)), relativePercentage(allFrames.maximum(43)),
                relativePercentage(allFrames.maximum(38)));
    }

    private static String percentage(long passed, long attempted) {
        return attempted == 0L ? "n/a" : String.format(Locale.ROOT, "%.3f%%", 100.0 * passed / attempted);
    }

    private static String relativePercentage(float error) {
        return String.format(Locale.ROOT, "%.6g%%", error * 100.0);
    }

    public void destroy() {
        logCumulative();
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
        allFrames.clear();
    }
}
