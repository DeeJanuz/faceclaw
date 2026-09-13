package com.faceclaw.sdk.ipc;

import android.os.Bundle;

/** Host-to-app half of one authenticated Faceclaw session. */
interface IFaceclawAppSession {
    oneway void applyHostSnapshot(in Bundle snapshot);
    oneway void grantRenderCredit(in Bundle credit);
    oneway void onBufferReleased(String surfaceId, long generation, int slotId, long sequence);
    oneway void onFrameOutcome(in Bundle outcome);
    oneway void sendControl(in Bundle event);
    oneway void close(in Bundle reason);
}
