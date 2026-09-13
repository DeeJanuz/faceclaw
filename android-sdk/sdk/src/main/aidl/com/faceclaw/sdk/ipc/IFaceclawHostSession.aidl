package com.faceclaw.sdk.ipc;

import android.os.Bundle;
import com.faceclaw.sdk.ipc.IFaceclawAppSession;

/** App-to-host half of one authenticated Faceclaw session. */
interface IFaceclawHostSession {
    oneway void onReady(in Bundle appHello, IFaceclawAppSession app);
    oneway void onConsentRequired(in Bundle consent);
    oneway void registerSurface(in Bundle registration);
    oneway void unregisterSurface(String surfaceId, long generation);
    oneway void requestRender(String surfaceId, long generation, int reason);
    oneway void submitFrame(in Bundle submission);
    oneway void registerResource(in Bundle resource);
    oneway void commitScene(in Bundle transaction);
    oneway void sendControl(in Bundle event);
    oneway void close(in Bundle reason);
}
