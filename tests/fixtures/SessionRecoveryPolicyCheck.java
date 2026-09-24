package com.faceclaw.app;

public final class SessionRecoveryPolicyCheck {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        SessionRecoveryPolicy policy = new SessionRecoveryPolicy();
        int initial = policy.generation();
        require(policy.layoutCreateAllowed(), "fresh session should create layout");
        require(
                policy.onUnexpectedExit(1_000, 6)
                        == SessionRecoveryPolicy.ExitAction.START_RELAUNCH,
                "first exit should relaunch in place");
        require(
                policy.generation() != initial && !policy.layoutCreateAllowed(),
                "exit must retire the old layout generation");
        int recovery = policy.claimRelaunch();
        require(recovery != 0, "worker did not claim relaunch");
        require(
                policy.onUnexpectedExit(1_200, 7) == SessionRecoveryPolicy.ExitAction.COALESCED,
                "abnormal/system exit pair was not coalesced");
        require(
                policy.completeRelaunch(recovery) && policy.layoutCreateAllowed(),
                "successful prelude should permit one new layout");
        require(
                policy.onUnexpectedExit(2_000, 6) == SessionRecoveryPolicy.ExitAction.RECONNECT,
                "second independent exit must reconnect before a visible frame");

        policy.resetForNewSession();
        require(
                policy.onUnexpectedExit(3_000, 6)
                        == SessionRecoveryPolicy.ExitAction.START_RELAUNCH,
                "fresh transport should restore the recovery budget");
        recovery = policy.claimRelaunch();
        require(policy.completeRelaunch(recovery), "second relaunch did not complete");
        require(policy.completeFrame(recovery), "visible frame did not complete recovery");
        require(
                policy.onUnexpectedExit(4_000, 6)
                        == SessionRecoveryPolicy.ExitAction.START_RELAUNCH,
                "visible recovery frame should reset the one-attempt budget");

        policy.resetForNewSession();
        require(
                policy.onUnexpectedExit(5_000, 6)
                        == SessionRecoveryPolicy.ExitAction.START_RELAUNCH,
                "same-type test did not start recovery");
        require(
                policy.onUnexpectedExit(5_200, 6) == SessionRecoveryPolicy.ExitAction.RECONNECT,
                "a rapid second abnormal exit must not be mistaken for the paired system exit");

        policy.resetForNewSession();
        int old = policy.generation();
        require(!policy.completeRelaunch(old - 1), "retired relaunch completed a new generation");
        require(!policy.completeFrame(old - 1), "retired frame completed a new generation");
    }
}
