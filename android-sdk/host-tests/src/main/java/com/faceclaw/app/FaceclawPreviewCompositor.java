package com.faceclaw.app;

import java.nio.ByteBuffer;

/** No-phone-preview fixture implementation. */
public final class FaceclawPreviewCompositor {
    public static FaceclawPreviewCompositor getActive() {
        return null;
    }

    public void submitSurfaceFrame(
            ByteBuffer pixels,
            String id,
            int x,
            int y,
            int width,
            int height,
            String fingerprint,
            int paintMs,
            int frameId) {}

    public void submitSurfaceFrame(
            ByteBuffer pixels,
            String id,
            int x,
            int y,
            int width,
            int height,
            String fingerprint,
            int paintMs,
            int frameId,
            ByteBuffer draws) {}
}
