package com.faceclaw.sdk;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.*;

public class ComposerLifecycleTest {
    @Test
    public void confirmationIsSingleUseAndLateResultsAreIgnored() {
        List<JSONObject> events = new ArrayList<>();
        ComposerSession session =
                new ComposerSession("composer", (type, data) -> true, events::add);
        session.event(
                Protocol.object("composerId", "other", "status", "confirmed", "text", "wrong"));
        session.event(
                Protocol.object(
                        "composerId", "composer", "status", "confirmed", "text", "Exact draft"));
        session.event(
                Protocol.object("composerId", "composer", "status", "confirmed", "text", "Replay"));
        assertEquals(1, events.size());
        assertEquals("Exact draft", events.get(0).optString("text"));
        assertTrue(session.terminal());
    }

    @Test
    public void localCancelReleasesTheSlotBeforeHostAcknowledgement() throws Exception {
        List<JSONObject> sent = new ArrayList<>();
        AppControls client =
                new AppControls(
                        (type, data) -> {
                            sent.add(Protocol.object("type", type, "data", data));
                            return true;
                        },
                        () -> 100,
                        (delay, task) -> {});
        JSONObject feature =
                Protocol.object("id", "composer.session", "version", 1, "limits", new JSONObject());
        AppIndependenceCatalog catalog =
                AppIndependenceCatalog.fromCapabilities(
                        Protocol.object(
                                "appIndependence",
                                Protocol.object(
                                        "contractVersion",
                                        1,
                                        "epoch",
                                        1,
                                        "features",
                                        new JSONArray().put(feature))));
        client.snapshot(catalog, 3);
        JSONObject declaration = sent.get(0).getJSONObject("data");
        client.receive(
                "contract-result",
                IndependenceProtocol.negotiate(
                        declaration, Collections.singleton("composer.session"), 1, 0));
        ComposerSession first =
                client.composer(
                        ComposerSession.Purpose.MESSAGE,
                        "thread",
                        "Signal message",
                        "",
                        8000,
                        event -> {});
        first.cancel();
        ComposerSession second =
                client.composer(
                        ComposerSession.Purpose.MESSAGE,
                        "thread",
                        "Signal message",
                        "saved",
                        8000,
                        event -> {});
        assertTrue(first.terminal());
        assertFalse(second.terminal());
        assertNotEquals(first.id, second.id);
    }

    @Test
    public void invalidOrOversizedRequestsNeverReachTheHost() {
        List<JSONObject> events = new ArrayList<>();
        AppControls client = new AppControls((type, data) -> true, () -> 100, (delay, task) -> {});
        ComposerSession session =
                client.composer(
                        ComposerSession.Purpose.MESSAGE,
                        "thread",
                        "Signal message",
                        "x",
                        8000,
                        events::add);
        assertTrue(session.terminal());
        assertEquals("unsupported", events.get(0).optString("reason"));
    }
}
