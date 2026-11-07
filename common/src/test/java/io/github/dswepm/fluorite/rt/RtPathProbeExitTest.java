package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class RtPathProbeExitTest {
    @Test void ordinaryClientsNeverExitEvenOnDiagnosticsFailure() {
        var disabled = new RtPathProbeExit(0, true);
        assertEquals(RtPathProbeExit.Reason.NONE, disabled.poll(Long.MAX_VALUE, true));
        assertEquals(RtPathProbeExit.Reason.NONE, new RtPathProbeExit(-1, true).poll(100, true));
    }

    @Test void theReadbackLimitRequestsExactlyOneExit() {
        var probe = new RtPathProbeExit(512, true);
        assertEquals(RtPathProbeExit.Reason.NONE, probe.poll(511, false));
        assertEquals(RtPathProbeExit.Reason.LIMIT, probe.poll(512, false));
        assertEquals(RtPathProbeExit.Reason.NONE, probe.poll(513, true));
    }

    @Test void aFailedReadbackStopsBeforeTheLimitWithoutWaitingForTheLogInterval() {
        var probe = new RtPathProbeExit(200_000, true);
        assertEquals(RtPathProbeExit.Reason.FAILURE, probe.poll(1, true));
        assertEquals(RtPathProbeExit.Reason.NONE, probe.poll(2, true));
    }

    @Test void anExplicitlyContinuousProbeStillHonoursItsReadbackLimit() {
        var probe = new RtPathProbeExit(2, false);
        assertEquals(RtPathProbeExit.Reason.NONE, probe.poll(1, true));
        assertEquals(RtPathProbeExit.Reason.LIMIT, probe.poll(2, true));
    }
}
