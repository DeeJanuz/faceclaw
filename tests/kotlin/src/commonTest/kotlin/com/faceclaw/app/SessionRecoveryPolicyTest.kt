package com.faceclaw.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SessionRecoveryPolicyTest {
    @Test
    fun firmwareExitRecoveryIsGenerationBoundAndLimitedToOneRelaunch() {
        val policy = SessionRecoveryPolicy()
        val initial = policy.generation()
        assertTrue(policy.layoutCreateAllowed(), "fresh session should create layout")
        assertEquals(SessionRecoveryPolicy.ExitAction.START_RELAUNCH, policy.onUnexpectedExit(1_000, 6))
        assertNotEquals(initial, policy.generation(), "exit must retire the old layout generation")
        assertFalse(policy.layoutCreateAllowed())
        var recovery = policy.claimRelaunch()
        assertNotEquals(0, recovery, "worker did not claim relaunch")
        assertEquals(SessionRecoveryPolicy.ExitAction.COALESCED, policy.onUnexpectedExit(1_200, 7),
            "abnormal/system exit pair was not coalesced")
        assertTrue(policy.completeRelaunch(recovery) && policy.layoutCreateAllowed(),
            "successful prelude should permit one new layout")
        assertEquals(SessionRecoveryPolicy.ExitAction.RECONNECT, policy.onUnexpectedExit(2_000, 6),
            "second independent exit must reconnect before a visible frame")

        policy.resetForNewSession()
        assertEquals(SessionRecoveryPolicy.ExitAction.START_RELAUNCH, policy.onUnexpectedExit(3_000, 6),
            "fresh transport should restore the recovery budget")
        recovery = policy.claimRelaunch()
        assertTrue(policy.completeRelaunch(recovery), "second relaunch did not complete")
        assertTrue(policy.completeFrame(recovery), "visible frame did not complete recovery")
        assertEquals(SessionRecoveryPolicy.ExitAction.START_RELAUNCH, policy.onUnexpectedExit(4_000, 6),
            "visible recovery frame should reset the one-attempt budget")

        policy.resetForNewSession()
        assertEquals(SessionRecoveryPolicy.ExitAction.START_RELAUNCH, policy.onUnexpectedExit(5_000, 6))
        assertEquals(SessionRecoveryPolicy.ExitAction.RECONNECT, policy.onUnexpectedExit(5_200, 6),
            "a rapid second abnormal exit must not be mistaken for the paired system exit")

        policy.resetForNewSession()
        val old = policy.generation()
        assertFalse(policy.completeRelaunch(old - 1), "retired relaunch completed a new generation")
        assertFalse(policy.completeFrame(old - 1), "retired frame completed a new generation")
    }
}
