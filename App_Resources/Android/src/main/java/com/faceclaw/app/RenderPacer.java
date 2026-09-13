package com.faceclaw.app;

/** Predict the next transport opportunity without adding a delay after rendering. */
final class RenderPacer {
    private double transferMs = 16;
    private long nextOpportunity;

    void acknowledged(long durationMs) {
        // Recovery/keyframe outliers must not stall subsequent small animations.
        transferMs += (Math.max(1, Math.min(100, durationMs)) - transferMs) * 0.25;
    }

    long delay(long now, boolean desiredFramePending) {
        // One desired frame can be prepared while the current transfer is in flight.
        if (!desiredFramePending) {
            nextOpportunity = now + Math.round(transferMs);
            return 0;
        }
        long remaining = Math.max(0, Math.min(100, nextOpportunity - now));
        nextOpportunity = now + remaining + Math.round(transferMs);
        return remaining;
    }
}
