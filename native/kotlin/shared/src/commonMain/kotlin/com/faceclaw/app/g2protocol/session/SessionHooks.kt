package com.faceclaw.app

/**
 * Optional platform hooks for the Android SDK host: battery-activity counters,
 * a short transition wake lease, display-ownership checks and per-frame
 * outcomes for external producers. Every method defaults to a no-op so other
 * hosts can pass [NONE].
 */
interface SessionHooks {
    /** A named activity state (display, capture, session, sensors) changed at [atMs]. */
    fun transition(name: String, active: Boolean, atMs: Long) {}

    fun recordLogicalWrite(kind: String, arm: String, payloadBytes: Int) {}

    fun recordAckTimeout() {}

    fun recordTransportFailure() {}

    /** Keep the CPU awake briefly while a wake/sleep or input crosses into the app. */
    fun acquireTransitionWake(reason: String) {}

    fun releaseTransitionWake(reason: String) {}

    /** Whether this session still owns the glasses; a replaced duplicate ignores BLE callbacks. */
    fun isActiveOwner(): Boolean = true

    /** A frame reached its final outcome (sent, superseded, discarded, ...). */
    fun frameFinished(frameId: Int, outcome: String) {}

    companion object {
        val NONE: SessionHooks = object : SessionHooks {}
    }
}
