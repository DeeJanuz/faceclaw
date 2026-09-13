package com.faceclaw.sdk.ipc;

import android.os.Bundle;
import com.faceclaw.sdk.ipc.IFaceclawHostSession;

/** Discovery binder. A successful handshake replaces this with a session-scoped binder. */
interface IFaceclawAppEndpoint {
    oneway void connect(in Bundle hello, IFaceclawHostSession host);
}
