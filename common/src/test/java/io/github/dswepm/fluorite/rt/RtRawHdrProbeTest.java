package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

final class RtRawHdrProbeTest {
    private static ByteBuffer pixels(float[]... values) {
        ByteBuffer bytes = ByteBuffer.allocate(values.length * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (float[] rgb : values) {
            for (float value : rgb) bytes.putShort(Float.floatToFloat16(value));
            bytes.putShort(Float.floatToFloat16(1));
        }
        return bytes;
    }

    @Test void linearRgbMomentsPreserveSignedValuesAndHalfSubnormals() {
        var m = RtRawHdrProbe.moments(pixels(new float[]{1, 2, 3}, new float[]{0, .5f, 4}, new float[]{-1, 0, 2}), 3);
        assertEquals(0, m.r()); assertEquals(2.5, m.g()); assertEquals(9, m.b());
        assertEquals(2, m.rr()); assertEquals(4.25, m.gg()); assertEquals(29, m.bb());
        assertEquals(0, m.invalid());
        ByteBuffer subnormal = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        subnormal.putShort(0, (short) 1);
        var tiny = RtRawHdrProbe.moments(subnormal, 1);
        assertEquals(Math.scalb(1.0, -24), tiny.r());
        assertEquals(Math.scalb(1.0, -48), tiny.rr());
    }

    @Test void nonfinitePixelsAreReportedRatherThanMadeIntoAHealthyMeasurement() {
        var m = RtRawHdrProbe.moments(pixels(new float[]{1, 2, 3}, new float[]{Float.POSITIVE_INFINITY, 0, 0},
                new float[]{0, Float.NaN, 0}), 3);
        assertEquals(2, m.invalid());
        assertEquals(1, m.r()); assertEquals(2, m.g()); assertEquals(3, m.b());
    }

    @Test void fixedPixelGridRetainsOriginalRgbHalfBitsAndHandlesSmallImages() {
        ByteBuffer bytes = ByteBuffer.allocate(16 * 10 * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 160; i++) {
            bytes.putShort(i * 8, Float.floatToFloat16(i));
            bytes.putShort(i * 8 + 2, Float.floatToFloat16(-i));
            bytes.putShort(i * 8 + 4, Float.floatToFloat16(.25f));
        }
        ByteBuffer grid = ByteBuffer.wrap(RtRawHdrProbe.grid(bytes, 16, 10)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(12, grid.capacity());
        assertEquals(68, Float.float16ToFloat(grid.getShort(0)));
        assertEquals(-68, Float.float16ToFloat(grid.getShort(2)));
        assertEquals(.25f, Float.float16ToFloat(grid.getShort(4)));
        assertEquals(76, Float.float16ToFloat(grid.getShort(6)));
        assertArrayEquals(new byte[]{0, 60, 0, 64, 0, 66}, RtRawHdrProbe.grid(pixels(new float[]{1, 2, 3}), 1, 1));
    }

    @Test void abaWindowsKeepWarmupOutOfTheCapturedSamplesAndRestoreAfterThreeBlocks() {
        var plan = new RtRawHdrProbe.Plan(8, 4, 2);
        assertEquals(0, plan.phase(0)); assertEquals(-1, plan.sample(7));
        assertEquals(0, plan.sample(8)); assertEquals(-1, plan.sample(9)); assertEquals(1, plan.sample(12));
        assertEquals(1, plan.phase(16)); assertEquals(-1, plan.sample(23)); assertEquals(0, plan.sample(24));
        assertEquals(2, plan.phase(32)); assertEquals(1, plan.sample(44));
        assertEquals(-1, plan.phase(48)); assertEquals(-1, plan.sample(48));
    }

    @Test void captureReadsTheNoisyCompositeBeforeRayReconstructionAndUsesTheExistingFence() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("settings.gradle"))) root = root.getParent();
        assertNotNull(root);
        String source = Files.readString(root.resolve("common/src/main/java/io/github/dswepm/fluorite/rt/RtComposite.java"));
        int copy = source.indexOf("rawHdrProbe.record(ctx, cmd, stack, pushSlot, output,");
        assertTrue(copy > source.indexOf("pathStats.recordCopy(cmd, stack, pushSlot);"));
        assertTrue(copy < source.indexOf("RtDlssRr.INSTANCE.evaluate(cmd.address(), output,"));
        assertTrue(source.indexOf("rawHdrProbe.reportRecycledSlot(pushSlot);")
                > source.indexOf("graphicsUseWaiter.await(selectedPushSlot.graphicsUse);"));
    }
}
