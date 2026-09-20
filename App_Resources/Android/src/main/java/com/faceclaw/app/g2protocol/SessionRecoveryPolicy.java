package com.faceclaw.app;

/**
 * Small, Android-free state machine for recovering an EvenHub task that the
 * glasses firmware exited while the BLE transport remained connected.
 *
 * The firmware commonly reports one exit as an ABNORMAL_EXIT/SYSTEM_EXIT pair.
 * Those reports have no task generation, so a short fixed window coalesces the
 * pair. Any later exit before a recovered frame is visible requires the normal
 * full transport reconnect.
 */
public final class SessionRecoveryPolicy {
    public enum ExitAction { START_RELAUNCH, COALESCED, RECONNECT }

    private enum Phase { ACTIVE, RELAUNCH_PENDING, RELAUNCHING, AWAITING_FRAME, RECONNECT_REQUIRED }

    private static final long EXIT_CLUSTER_MS = 750;
    // G2 Sys_ItemEvent values. Keep this helper Android-free and independently
    // compilable, so it does not depend on the much larger BleProtocol class.
    private static final int ABNORMAL_EXIT = 6;
    private static final int SYSTEM_EXIT = 7;

    private int generation = 1;
    private int attempts;
    private long exitClusterStartedAtMs = -1;
    private int firstExitType = -1;
    private boolean exitPairCoalesced;
    private Phase phase = Phase.ACTIVE;

    public int generation() {
        return generation;
    }

    public String phaseName() {
        return phase.name().toLowerCase(java.util.Locale.US);
    }

    public boolean layoutCreateAllowed() {
        return phase == Phase.ACTIVE || phase == Phase.AWAITING_FRAME;
    }

    public boolean recoveryActive() {
        return phase != Phase.ACTIVE && phase != Phase.RECONNECT_REQUIRED;
    }

    public int attempts() {
        return attempts;
    }

    public ExitAction onUnexpectedExit(long nowMs, int eventType) {
        if (phase != Phase.ACTIVE
                && phase != Phase.RECONNECT_REQUIRED
                && !exitPairCoalesced
                && exitClusterStartedAtMs >= 0
                && nowMs - exitClusterStartedAtMs <= EXIT_CLUSTER_MS
                && isAbnormalSystemPair(firstExitType, eventType)) {
            exitPairCoalesced = true;
            return ExitAction.COALESCED;
        }
        if (attempts >= 1 || phase == Phase.RECONNECT_REQUIRED) {
            generation++;
            phase = Phase.RECONNECT_REQUIRED;
            return ExitAction.RECONNECT;
        }
        generation++;
        attempts = 1;
        exitClusterStartedAtMs = nowMs;
        firstExitType = eventType;
        exitPairCoalesced = false;
        phase = Phase.RELAUNCH_PENDING;
        return ExitAction.START_RELAUNCH;
    }

    private static boolean isAbnormalSystemPair(int first, int second) {
        return (first == ABNORMAL_EXIT && second == SYSTEM_EXIT)
            || (first == SYSTEM_EXIT && second == ABNORMAL_EXIT);
    }

    /** Return the generation claimed by the worker, or zero when none is due. */
    public int claimRelaunch() {
        if (phase != Phase.RELAUNCH_PENDING) return 0;
        phase = Phase.RELAUNCHING;
        return generation;
    }

    public boolean completeRelaunch(int claimedGeneration) {
        if (generation != claimedGeneration || phase != Phase.RELAUNCHING) return false;
        phase = Phase.AWAITING_FRAME;
        return true;
    }

    /** Reset the one-attempt budget only after the recovered frame is visible. */
    public boolean completeFrame(int frameGeneration) {
        if (generation != frameGeneration || phase != Phase.AWAITING_FRAME) return false;
        attempts = 0;
        exitClusterStartedAtMs = -1;
        firstExitType = -1;
        exitPairCoalesced = false;
        phase = Phase.ACTIVE;
        return true;
    }

    /** A fresh transport or intentional lifecycle transition starts a new task. */
    public void resetForNewSession() {
        generation++;
        attempts = 0;
        exitClusterStartedAtMs = -1;
        firstExitType = -1;
        exitPairCoalesced = false;
        phase = Phase.ACTIVE;
    }
}
