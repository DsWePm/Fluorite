package io.github.dswepm.fluorite.rt;

/** Opt-in benchmark stop policy. Normal clients never request an exit from path diagnostics. */
final class RtPathProbeExit {
    enum Reason { NONE, FAILURE, LIMIT }

    private final long limit;
    private final boolean stopOnFailure;
    private boolean issued;

    RtPathProbeExit(long limit, boolean stopOnFailure) {
        this.limit = Math.max(0L, limit);
        this.stopOnFailure = stopOnFailure;
    }

    static RtPathProbeExit fromSystemProperties() {
        return new RtPathProbeExit(Long.getLong("fluorite.rt.pathProbeReadbacks", 0L),
                Boolean.parseBoolean(System.getProperty("fluorite.rt.pathProbeStopOnFailure", "true")));
    }

    Reason poll(long readbacks, boolean failed) {
        if (issued || limit == 0L) return Reason.NONE;
        Reason reason = failed && stopOnFailure ? Reason.FAILURE
                : readbacks >= limit ? Reason.LIMIT : Reason.NONE;
        if (reason != Reason.NONE) issued = true;
        return reason;
    }
}
