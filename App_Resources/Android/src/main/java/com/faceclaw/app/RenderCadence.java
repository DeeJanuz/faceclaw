package com.faceclaw.app;

/** Transport pacing is a grant-to-grant interval, not a post-render sleep. */
final class RenderCadence {
    /** Optional app cadence; transport backlog/unavailability still wins. */
    static long requestedPeriod(long transportPeriod, int requestedMs) {
        if (requestedMs <= 0) return transportPeriod;
        long requested = Math.max(17, Math.min(1000, requestedMs));
        return transportPeriod >= 48 ? Math.max(transportPeriod, requested) : requested;
    }

    static long remainingDelay(long now, long lastGrant, long period) {
        if (lastGrant == 0) return 0;
        return Math.max(0, period - Math.max(0, now - lastGrant));
    }
}
