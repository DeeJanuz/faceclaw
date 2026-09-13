package com.faceclaw.sdk;

public final class RenderRequest {
 public final RenderSurface surface; public final RenderCredit credit; public final InvalidateReason reason;
 private boolean nextFrame;
 RenderRequest(RenderSurface surface,RenderCredit credit,InvalidateReason reason) { this.surface=surface;this.credit=credit;this.reason=reason; }
 /** Ask the host scheduler for the next animation sample after this frame. */
 public void requestNextFrame() { nextFrame=true; }
 boolean wantsNextFrame() { return nextFrame; }
}
