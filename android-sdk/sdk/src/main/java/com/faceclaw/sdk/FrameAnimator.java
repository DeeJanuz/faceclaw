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

    /**
     * Moves an integer translation toward its elapsed-time target without
     * exposing more than {@code maxStep} new pixel columns or rows at once.
     * Keep requesting render credits until the returned value reaches the
     * final target, even if the nominal animation duration has elapsed.
     */
    public static int limitTranslationStep(int previous, int desired, int maxStep) {
        if (maxStep <= 0) throw new IllegalArgumentException("maxStep must be positive");
        long delta = (long) desired - previous;
        if (delta > maxStep) return previous + maxStep;
        if (delta < -maxStep) return previous - maxStep;
        return desired;
    }
}
