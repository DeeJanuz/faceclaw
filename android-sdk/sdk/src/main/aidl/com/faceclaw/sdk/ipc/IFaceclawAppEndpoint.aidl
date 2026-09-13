package com.faceclaw.sdk.ipc;

import com.faceclaw.sdk.SessionHello;
import com.faceclaw.sdk.ipc.IFaceclawHostSession;

/** Discovery binder. A successful handshake replaces this with a session-scoped binder. */
interface IFaceclawAppEndpoint {
    oneway void connect(in SessionHello hello, IFaceclawHostSession host);
}
