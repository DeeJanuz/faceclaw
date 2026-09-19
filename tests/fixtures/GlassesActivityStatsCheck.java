package com.faceclaw.app;

public final class GlassesActivityStatsCheck {
    public static void main(String[] args) {
        GlassesActivityStats stats = new GlassesActivityStats(1_000);
        stats.transition("displayOn", false, 1_000);
        stats.transition("displayOn", true, 1_100);
        stats.transition("displayOn", true, 1_150);
        stats.transition("displayOn", false, 1_350);
        stats.recordLogicalWrite("image", "right", 40);
        stats.recordLogicalWrite("image", "right", 20);
        stats.recordLogicalWrite("wake-lease-control", "left", 8);
        stats.recordAckTimeout();
        stats.recordTransportFailure();

        String json = stats.snapshotJson(1_500, new long[] {7, 800, 2, 3, 4, 3});
        require(json.contains("\"elapsedMs\":500"), "elapsed time");
        require(json.contains("\"writeRetries\":3"), "physical retries");
        require(json.contains("\"priorityAccepted\":3"), "priority results");
        require(json.contains("\"messages\":3,\"payloadBytes\":68"), "logical totals");
        require(json.contains("\"kind\":\"image\",\"arm\":\"right\",\"messages\":2,\"payloadBytes\":60"), "route totals");
        require(json.contains("\"ackTimeouts\":1,\"transportFailures\":1"), "events");
        require(json.contains("\"displayOn\":{\"known\":true,\"active\":false,\"activeMs\":250}"), "state duration");

        stats.transition("capture", true, 1_400);
        String active = stats.snapshotJson(1_650, null);
        require(active.contains("\"capture\":{\"known\":true,\"active\":true,\"activeMs\":250}"), "live state duration");
        require(active.contains("\"bytes\":0"), "short physical input");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
