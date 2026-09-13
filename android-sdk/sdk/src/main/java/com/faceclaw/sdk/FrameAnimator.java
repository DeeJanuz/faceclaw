package com.faceclaw.sdk;

/** Presentation-time animation math. Scheduling belongs to RenderCredit. */
public final class FrameAnimator {
    private FrameAnimator() {}

    public static float easedProgress(long elapsedMs, long durationMs) {
        if (durationMs <= 0 || elapsedMs >= durationMs) return 1f;
        if (elapsedMs <= 0) return 0f;
        float t = (float) elapsedMs / durationMs;
        return t * t * (3f - 2f * t);
    }
}
