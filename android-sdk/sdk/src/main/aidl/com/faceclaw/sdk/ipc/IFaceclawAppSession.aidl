package com.faceclaw.sdk.ipc;

import com.faceclaw.sdk.ControlEvent;
import com.faceclaw.sdk.DisconnectInfo;
import com.faceclaw.sdk.FaceclawInputEvent;
import com.faceclaw.sdk.FrameOutcome;
import com.faceclaw.sdk.HostSnapshot;
import com.faceclaw.sdk.RenderCredit;

/** Host-to-app half of one authenticated Faceclaw session. */
interface IFaceclawAppSession {
    oneway void applyHostSnapshot(in HostSnapshot snapshot);
    oneway void grantRenderCredit(in RenderCredit credit);
    oneway void onBufferReleased(String surfaceId, long generation, int slotId, long sequence);
    oneway void onFrameOutcome(in FrameOutcome outcome);
    oneway void onInput(String surfaceId, in FaceclawInputEvent event);
    oneway void sendControl(in ControlEvent event);
    oneway void close(in DisconnectInfo reason);
}
