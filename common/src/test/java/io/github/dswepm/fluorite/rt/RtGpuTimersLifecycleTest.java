package io.github.dswepm.fluorite.rt;

import io.github.dswepm.fluorite.FluoriteConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class RtGpuTimersLifecycleTest {
    private static RtGpuTimers freshPool() throws Exception {
        var constructor = RtGpuTimers.class.getDeclaredConstructor(
                long.class, String[].class, int.class, double.class, long.class);
        constructor.setAccessible(true);
        // No native pool is allocated. A null context in resolve detects reaching the native-read seam
        // for a slot that cannot have GPU results, rather than substituting a mocked state machine.
        return constructor.newInstance(0L, new String[] {"gpu.tracePrimary"}, 3, 1.0, -1L);
    }

    @Test
    void resolveBeforeTheFirstResetDoesNotAccessTheDevice() throws Exception {
        boolean restore = FluoriteConfig.Rt.FrameStats.ENABLED.value();
        try {
            FluoriteConfig.Rt.FrameStats.ENABLED.set(true);
            RtGpuTimers timer = freshPool();
            assertDoesNotThrow(() -> timer.resolve(null, 0),
                    "RtComposite resolves a fresh slot before recording its first reset");
        } finally {
            FluoriteConfig.Rt.FrameStats.ENABLED.set(restore);
        }
    }

    @Test
    void aQueuedWholePoolResetDoesNotMakeUnusedRingSlotsReadable() throws Exception {
        boolean restore = FluoriteConfig.Rt.FrameStats.ENABLED.value();
        try {
            FluoriteConfig.Rt.FrameStats.ENABLED.set(true);
            RtGpuTimers timer = freshPool();
            var reset = RtGpuTimers.class.getDeclaredField("poolNeedsReset");
            reset.setAccessible(true);
            reset.setBoolean(timer, false); // another slot has queued the reset; it need not have executed
            for (int slot = 1; slot < 3; slot++) {
                int unusedSlot = slot;
                assertDoesNotThrow(() -> timer.resolve(null, unusedSlot),
                        "an unused slot has no completed graphics-use token to await");
            }
        } finally {
            FluoriteConfig.Rt.FrameStats.ENABLED.set(restore);
        }
    }
}
