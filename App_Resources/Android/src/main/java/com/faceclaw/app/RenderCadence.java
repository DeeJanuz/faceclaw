package com.faceclaw.app;

/** Transport pacing is a grant-to-grant interval, not a post-render sleep. */
final class RenderCadence {
    static long remainingDelay(long now, long lastGrant, long period) {
        if (lastGrant == 0) return 0;
        return Math.max(0, period - Math.max(0, now - lastGrant));
    }
}
