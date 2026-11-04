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
        totals.add(MemoryUtil.memAddress(frame));
        frame.putInt(20 * 4, 3).putInt(22 * 4, 3).putInt(23 * 4, 3).putFloat(25 * 4, 1e-8f);
        totals.add(MemoryUtil.memAddress(frame));
        assertEquals(2, totals.frames);
        assertEquals(4, totals.count(20));
        assertEquals(3, totals.count(23));
        assertEquals(.0014885f, totals.maximum(25));
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
        assertEquals(64, RtPathReservoirStats.LANES);
        assertEquals(256, RtPathReservoirStats.BYTE_SIZE);
    }
}
