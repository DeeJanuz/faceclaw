package com.faceclaw.app;

import java.util.HashSet;
import java.util.Set;

/**
 * Android-free checks for dispatching into another app's notification.
 *
 * <p>The listener service reads the live notification and passes plain values
 * here, so the rules can run under a plain JVM.</p>
 */
final class NotificationDispatchGate {
    static final String DIAGNOSTICS_PACKAGE = "com.faceclaw.diagnostics";
    static final int MAX_REPLY_CHARS = 20000;
    static final int MAX_RECEIPTS = 4096;

    interface Dispatch {
        boolean send() throws Exception;
    }

    private final Set<String> receipts = new HashSet<>();

    static boolean acceptableReplyText(String text) {
        return text != null && !text.trim().isEmpty() && text.length() <= MAX_REPLY_CHARS;
    }

    /** Only the posting app may own the intent a reply or action fires. */
    static boolean trustedIntent(String packageName, String creatorPackage) {
        return packageName != null && packageName.equals(creatorPackage);
    }

    /**
     * Faceclaw's own notifications and the diagnostics foreground notification
     * stay out of the mirror. Mirroring diagnostics creates a feedback loop in
     * which an invisible shell overlay consumes glasses input.
     */
    static boolean mirrorsPackage(String ownPackage, String packageName) {
        return !DIAGNOSTICS_PACKAGE.equals(packageName) && !ownPackage.equals(packageName);
    }

    static String replyReceipt(String key, long postTime, int actionIndex) {
        return key + ":" + postTime + ":" + actionIndex;
    }

    static String actionReceipt(String key, long postTime, int actionIndex) {
        return key + ":" + postTime + ":action:" + actionIndex;
    }

    /**
     * Claims the receipt before dispatch, so a failed or uncertain send is never
     * replayed automatically. Returns false for duplicates or a full ledger.
     */
    synchronized boolean dispatchOnce(String receipt, Dispatch dispatch) {
        if (receipts.contains(receipt) || receipts.size() >= MAX_RECEIPTS) return false;
        receipts.add(receipt);
        try {
            return dispatch.send();
        } catch (Exception e) {
            return false;
        }
    }
}
