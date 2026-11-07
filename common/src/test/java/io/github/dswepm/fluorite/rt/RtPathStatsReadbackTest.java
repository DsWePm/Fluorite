package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import java.nio.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

final class RtPathStatsReadbackTest {
    @Test void failedFrameAndMaximaSurviveAHealthyPeriodicLogFrame() {
        ByteBuffer frame = ByteBuffer.allocateDirect((int) RtPathReservoirStats.BYTE_SIZE).order(ByteOrder.nativeOrder());
        var totals = new RtPathReservoirStats.FrameTotals();
        frame.putInt(20 * 4, 1).putInt(22 * 4, 1).putInt(23 * 4, 0).putFloat(25 * 4, .0014885f);
        frame.putFloat(72 * 4, .003f).putFloat(73 * 4, .004f);
        frame.putFloat(103 * 4, 21f).putInt(102 * 4, 1);
        frame.putFloat(82 * 4, .005f).putFloat(83 * 4, .006f).putInt(81 * 4, 2);
        totals.add(MemoryUtil.memAddress(frame));
        frame.putInt(20 * 4, 3).putInt(22 * 4, 3).putInt(23 * 4, 3).putFloat(25 * 4, 1e-8f);
        frame.putFloat(72 * 4, .001f).putFloat(73 * 4, .002f);
        frame.putFloat(103 * 4, 3f).putInt(102 * 4, 0);
        frame.putFloat(82 * 4, .001f).putFloat(83 * 4, .002f).putInt(81 * 4, 0);
        totals.add(MemoryUtil.memAddress(frame));
        assertEquals(2, totals.frames);
        assertEquals(4, totals.count(20));
        assertEquals(3, totals.count(23));
        assertEquals(.0014885f, totals.maximum(25));
        assertEquals(.003f, totals.maximum(72));
        assertEquals(.004f, totals.maximum(73));
        assertEquals(.005f, totals.maximum(82));
        assertEquals(.006f, totals.maximum(83));
        assertEquals(2, totals.count(81));
        assertEquals(21f, totals.maximum(103));
        assertEquals(1, totals.count(102));
    }

    @Test void countersAccumulateAsUnsignedAndResetWithTheBufferLifetime() {
        ByteBuffer frame = ByteBuffer.allocateDirect((int) RtPathReservoirStats.BYTE_SIZE).order(ByteOrder.nativeOrder());
        var totals = new RtPathReservoirStats.FrameTotals();
        frame.putInt(20 * 4, -1).putFloat(25 * 4, .01f).putInt(59 * 4, 1);
        totals.add(MemoryUtil.memAddress(frame));
        totals.add(MemoryUtil.memAddress(frame));
        assertEquals(8_589_934_590L, totals.count(20));
        assertEquals(2, totals.count(59));
        assertEquals(.01f, totals.maximum(25));
        totals.clear();
        assertEquals(0, totals.frames);
        assertEquals(0, totals.count(20));
        assertEquals(0, totals.maximum(25));
    }

    @Test void temporalCountersRejectNumericFailuresAndLostOrDuplicatedOutcomes() {
        var bytes=ByteBuffer.allocateDirect((int)RtPathReservoirStats.BYTE_SIZE).order(ByteOrder.nativeOrder());
        long address=MemoryUtil.memAddress(bytes);
        assertFalse(RtPathReservoirStats.temporalReadbackFailed(address));
        bytes.putInt(88*4,3).putInt(89*4,1).putInt(90*4,1).putInt(91*4,1);
        bytes.putInt(94*4,2).putInt(95*4,1).putInt(98*4,1);
        assertFalse(RtPathReservoirStats.temporalReadbackFailed(address));
        bytes.putInt(89*4,2); assertTrue(RtPathReservoirStats.temporalReadbackFailed(address));
        bytes.putInt(89*4,0); assertTrue(RtPathReservoirStats.temporalReadbackFailed(address));
        bytes.putInt(89*4,1).putInt(102*4,1); assertTrue(RtPathReservoirStats.temporalReadbackFailed(address));
        bytes.putInt(102*4,0).putInt(98*4,0).putInt(99*4,1);
        assertTrue(RtPathReservoirStats.temporalReadbackFailed(address));
    }

    @Test void readbackAccumulationAndFailureReportingPrecedeTheLogThrottle() throws Exception {
        Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (p != null && !Files.isRegularFile(p.resolve("settings.gradle"))) p = p.getParent();
        assertNotNull(p);
        String source = Files.readString(p.resolve("common/src/main/java/io/github/dswepm/fluorite/rt/RtPathReservoirStats.java"));
        int accumulate = source.indexOf("allFrames.add(src.mapped);");
        int failure = source.indexOf("RT path reuse failed readback");
        int throttle = source.indexOf("long now = System.nanoTime();");
        assertTrue(accumulate > 0 && accumulate < failure && failure < throttle);
        assertTrue(source.substring(source.indexOf("public void destroy()")).contains("allFrames.clear();"));
        assertEquals(110, RtPathReservoirStats.LANES);
        assertEquals(440, RtPathReservoirStats.BYTE_SIZE);
    }
}
