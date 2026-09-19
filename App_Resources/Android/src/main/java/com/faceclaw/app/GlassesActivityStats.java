package com.faceclaw.app;

import java.util.Map;
import java.util.TreeMap;

/** Bounded, content-free counters used to measure glasses battery experiments. */
public final class GlassesActivityStats {
    private static final class Counter {
        long messages;
        long payloadBytes;
    }

    private static final class State {
        boolean known;
        boolean active;
        long activeSinceMs;
        long activeMs;
    }

    private final long startedAtMs;
    private final Map<String, Counter> routes = new TreeMap<>();
    private final Map<String, State> states = new TreeMap<>();
    private long logicalMessages;
    private long logicalPayloadBytes;
    private long ackTimeouts;
    private long transportFailures;

    public GlassesActivityStats(long startedAtMs) {
        this.startedAtMs = Math.max(0, startedAtMs);
    }

    public synchronized void recordLogicalWrite(String kind, String arm, int payloadBytes) {
        String safeKind = normalized(kind, "unknown");
        String safeArm = normalized(arm, "unknown");
        String key = safeKind + "\u0000" + safeArm;
        Counter counter = routes.get(key);
        if (counter == null) {
            counter = new Counter();
            routes.put(key, counter);
        }
        int safeBytes = Math.max(0, payloadBytes);
        counter.messages += 1;
        counter.payloadBytes += safeBytes;
        logicalMessages += 1;
        logicalPayloadBytes += safeBytes;
    }

    public synchronized void recordAckTimeout() {
        ackTimeouts += 1;
    }

    public synchronized void recordTransportFailure() {
        transportFailures += 1;
    }

    /** Record a state edge. Repeating the current value does not alter elapsed time. */
    public synchronized void transition(String name, boolean active, long atMs) {
        String safeName = normalized(name, "unknown");
        long now = Math.max(startedAtMs, atMs);
        State state = states.get(safeName);
        if (state == null) {
            state = new State();
            states.put(safeName, state);
        }
        if (state.known && state.active == active) return;
        if (state.known && state.active) {
            state.activeMs += Math.max(0, now - state.activeSinceMs);
        }
        state.known = true;
        state.active = active;
        state.activeSinceMs = active ? now : 0;
    }

    /**
     * Produce a bounded JSON snapshot. Physical counters are supplied by the
     * process-wide BLE manager; logical counters belong to this communicator.
     */
    public synchronized String snapshotJson(long atMs, long[] physical) {
        long now = Math.max(startedAtMs, atMs);
        StringBuilder out = new StringBuilder(1024);
        out.append('{')
            .append("\"version\":1,")
            .append("\"elapsedMs\":").append(now - startedAtMs).append(',')
            .append("\"physical\":{")
            .append("\"messages\":").append(valueAt(physical, 0)).append(',')
            .append("\"bytes\":").append(valueAt(physical, 1)).append(',')
            .append("\"displayFrames\":").append(valueAt(physical, 2)).append(',')
            .append("\"writeRetries\":").append(valueAt(physical, 3)).append(',')
            .append("\"priorityRequests\":").append(valueAt(physical, 4)).append(',')
            .append("\"priorityAccepted\":").append(valueAt(physical, 5)).append("},")
            .append("\"logical\":{")
            .append("\"messages\":").append(logicalMessages).append(',')
            .append("\"payloadBytes\":").append(logicalPayloadBytes).append(',')
            .append("\"byRoute\":[");
        boolean first = true;
        for (Map.Entry<String, Counter> entry : routes.entrySet()) {
            if (!first) out.append(',');
            first = false;
            int separator = entry.getKey().indexOf('\u0000');
            String kind = entry.getKey().substring(0, separator);
            String arm = entry.getKey().substring(separator + 1);
            Counter counter = entry.getValue();
            out.append('{')
                .append("\"kind\":\"").append(json(kind)).append("\",")
                .append("\"arm\":\"").append(json(arm)).append("\",")
                .append("\"messages\":").append(counter.messages).append(',')
                .append("\"payloadBytes\":").append(counter.payloadBytes)
                .append('}');
        }
        out.append("]},\"events\":{")
            .append("\"ackTimeouts\":").append(ackTimeouts).append(',')
            .append("\"transportFailures\":").append(transportFailures)
            .append("},\"states\":{");
        first = true;
        for (Map.Entry<String, State> entry : states.entrySet()) {
            if (!first) out.append(',');
            first = false;
            State state = entry.getValue();
            long activeMs = state.activeMs;
            if (state.known && state.active) activeMs += Math.max(0, now - state.activeSinceMs);
            out.append('\"').append(json(entry.getKey())).append("\":{")
                .append("\"known\":").append(state.known).append(',')
                .append("\"active\":").append(state.active).append(',')
                .append("\"activeMs\":").append(activeMs)
                .append('}');
        }
        return out.append("}}").toString();
    }

    private static long valueAt(long[] values, int index) {
        return values != null && index >= 0 && index < values.length ? Math.max(0, values[index]) : 0;
    }

    private static String normalized(String value, String fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static String json(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\\' || ch == '\"') out.append('\\').append(ch);
            else if (ch < 0x20) out.append(String.format("\\u%04x", (int) ch));
            else out.append(ch);
        }
        return out.toString();
    }
}
