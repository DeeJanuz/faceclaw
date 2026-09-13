package com.faceclaw.app;

/** Consumes already composed packed frames and exposes transport backpressure. */
interface DisplayTransport {
    void submitComposedFrame(SurfaceCompositor.Composite composite, byte[] packed, int paintMs, int frameId);
    boolean isDisplayAvailable();
    long renderCreditDelayMs();
}
