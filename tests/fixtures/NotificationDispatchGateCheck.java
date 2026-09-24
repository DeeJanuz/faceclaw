package com.faceclaw.app;

public final class NotificationDispatchGateCheck {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        require(NotificationDispatchGate.acceptableReplyText("On my way"), "plain reply rejected");
        require(!NotificationDispatchGate.acceptableReplyText(null), "null reply accepted");
        require(!NotificationDispatchGate.acceptableReplyText(" \n "), "blank reply accepted");
        require(NotificationDispatchGate.acceptableReplyText("x".repeat(NotificationDispatchGate.MAX_REPLY_CHARS)), "reply at limit rejected");
        require(!NotificationDispatchGate.acceptableReplyText("x".repeat(NotificationDispatchGate.MAX_REPLY_CHARS + 1)), "oversized reply accepted");

        require(NotificationDispatchGate.trustedIntent("org.signal", "org.signal"), "poster's own intent rejected");
        require(!NotificationDispatchGate.trustedIntent("org.signal", "com.attacker"), "foreign intent creator accepted");
        require(!NotificationDispatchGate.trustedIntent("org.signal", null), "unknown intent creator accepted");
        require(!NotificationDispatchGate.trustedIntent(null, null), "missing package accepted");

        require(NotificationDispatchGate.mirrorsPackage("com.faceclaw.app", "org.signal"), "ordinary app not mirrored");
        require(!NotificationDispatchGate.mirrorsPackage("com.faceclaw.app", "com.faceclaw.app"), "own notification mirrored");
        require(!NotificationDispatchGate.mirrorsPackage("com.faceclaw.app", "com.faceclaw.diagnostics"), "diagnostics notification mirrored");

        require(!NotificationDispatchGate.replyReceipt("k", 1, 0).equals(NotificationDispatchGate.replyReceipt("k", 2, 0)),
                "a newer post must not share a receipt");
        require(!NotificationDispatchGate.replyReceipt("k", 1, 0).equals(NotificationDispatchGate.actionReceipt("k", 1, 0)),
                "reply and action receipts must not collide");

        NotificationDispatchGate gate = new NotificationDispatchGate();
        int[] sends = {0};
        require(gate.dispatchOnce("r", () -> { sends[0]++; return true; }), "first dispatch rejected");
        require(!gate.dispatchOnce("r", () -> { sends[0]++; return true; }), "duplicate dispatch accepted");
        require(sends[0] == 1, "duplicate reached the sender");

        require(!gate.dispatchOnce("uncertain", () -> { sends[0]++; throw new Exception("binder died"); }), "failed send reported success");
        require(!gate.dispatchOnce("uncertain", () -> { sends[0]++; return true; }), "uncertain send was replayed");
        require(!gate.dispatchOnce("declined", () -> false), "declined send reported success");
        require(!gate.dispatchOnce("declined", () -> true), "declined send was replayed");
        require(sends[0] == 2, "unexpected sender calls: " + sends[0]);

        NotificationDispatchGate full = new NotificationDispatchGate();
        for (int i = 0; i < NotificationDispatchGate.MAX_RECEIPTS; i++) full.dispatchOnce("r" + i, () -> true);
        require(!full.dispatchOnce("overflow", () -> { throw new AssertionError("full ledger dispatched"); }), "full ledger accepted a send");
    }
}
