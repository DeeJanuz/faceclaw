package com.faceclaw.app

/**
 * Small state machine for recovering an EvenHub task that the glasses firmware
 * exited while the BLE transport remained connected.
 *
 * The firmware commonly reports one exit as an ABNORMAL_EXIT/SYSTEM_EXIT pair.
 * Those reports have no task generation, so a short fixed window coalesces the
 * pair. Any later exit before a recovered frame is visible requires the normal
 * full transport reconnect.
 */
class SessionRecoveryPolicy {
    enum class ExitAction { START_RELAUNCH, COALESCED, RECONNECT }

    private enum class Phase { ACTIVE, RELAUNCH_PENDING, RELAUNCHING, AWAITING_FRAME, RECONNECT_REQUIRED }

    private companion object {
        const val EXIT_CLUSTER_MS = 750L
        // G2 Sys_ItemEvent values, kept local so this policy does not depend on
        // the much larger BleProtocol.
        const val ABNORMAL_EXIT = 6
        const val SYSTEM_EXIT = 7

        fun isAbnormalSystemPair(first: Int, second: Int): Boolean =
            (first == ABNORMAL_EXIT && second == SYSTEM_EXIT) || (first == SYSTEM_EXIT && second == ABNORMAL_EXIT)
    }

    private var generation = 1
    private var attempts = 0
    private var exitClusterStartedAtMs = -1L
    private var firstExitType = -1
    private var exitPairCoalesced = false
    private var phase = Phase.ACTIVE

    fun generation(): Int = generation

    fun phaseName(): String = phase.name.lowercase()

    fun layoutCreateAllowed(): Boolean = phase == Phase.ACTIVE || phase == Phase.AWAITING_FRAME

    fun recoveryActive(): Boolean = phase != Phase.ACTIVE && phase != Phase.RECONNECT_REQUIRED

    fun attempts(): Int = attempts

    fun onUnexpectedExit(nowMs: Long, eventType: Int): ExitAction {
        if (phase != Phase.ACTIVE && phase != Phase.RECONNECT_REQUIRED && !exitPairCoalesced &&
            exitClusterStartedAtMs >= 0 && nowMs - exitClusterStartedAtMs <= EXIT_CLUSTER_MS &&
            isAbnormalSystemPair(firstExitType, eventType)) {
            exitPairCoalesced = true
            return ExitAction.COALESCED
        }
        if (attempts >= 1 || phase == Phase.RECONNECT_REQUIRED) {
            generation++
            phase = Phase.RECONNECT_REQUIRED
            return ExitAction.RECONNECT
        }
        generation++
        attempts = 1
        exitClusterStartedAtMs = nowMs
        firstExitType = eventType
        exitPairCoalesced = false
        phase = Phase.RELAUNCH_PENDING
        return ExitAction.START_RELAUNCH
    }

    /** The generation claimed by the worker, or zero when none is due. */
    fun claimRelaunch(): Int {
        if (phase != Phase.RELAUNCH_PENDING) return 0
        phase = Phase.RELAUNCHING
        return generation
    }

    fun completeRelaunch(claimedGeneration: Int): Boolean {
        if (generation != claimedGeneration || phase != Phase.RELAUNCHING) return false
        phase = Phase.AWAITING_FRAME
        return true
    }

    /** Reset the one-attempt budget only after the recovered frame is visible. */
    fun completeFrame(frameGeneration: Int): Boolean {
        if (generation != frameGeneration || phase != Phase.AWAITING_FRAME) return false
        attempts = 0
        exitClusterStartedAtMs = -1
        firstExitType = -1
        exitPairCoalesced = false
        phase = Phase.ACTIVE
        return true
    }

    /** A fresh transport or intentional lifecycle transition starts a new task. */
    fun resetForNewSession() {
        generation++
        attempts = 0
        exitClusterStartedAtMs = -1
        firstExitType = -1
        exitPairCoalesced = false
        phase = Phase.ACTIVE
    }
}
