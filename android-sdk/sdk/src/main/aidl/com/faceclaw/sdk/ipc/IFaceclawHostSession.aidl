package com.faceclaw.sdk.ipc;

import com.faceclaw.sdk.ConsentRequest;
import com.faceclaw.sdk.ControlEvent;
import com.faceclaw.sdk.DisconnectInfo;
import com.faceclaw.sdk.FrameSubmission;
import com.faceclaw.sdk.ResourceRegistration;
import com.faceclaw.sdk.SceneSubmission;
import com.faceclaw.sdk.SessionHello;
import com.faceclaw.sdk.SurfaceRegistration;
import com.faceclaw.sdk.ipc.IFaceclawAppSession;

/** App-to-host half of one authenticated Faceclaw session. */
interface IFaceclawHostSession {
    oneway void onReady(in SessionHello appHello, IFaceclawAppSession app);
    oneway void onConsentRequired(in ConsentRequest consent);
    oneway void registerSurface(in SurfaceRegistration registration);
    oneway void unregisterSurface(String surfaceId, long generation);
    oneway void requestRender(String surfaceId, long generation, int reason);
    oneway void submitFrame(in FrameSubmission submission);
    oneway void registerResource(in ResourceRegistration resource);
    oneway void commitScene(in SceneSubmission transaction);
    oneway void sendControl(in ControlEvent event);
    oneway void close(in DisconnectInfo reason);
}
