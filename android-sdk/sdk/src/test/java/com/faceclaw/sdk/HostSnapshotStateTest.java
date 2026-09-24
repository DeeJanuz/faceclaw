package com.faceclaw.sdk;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

public class HostSnapshotStateTest {
    @Test
    public void completeSnapshotDerivesOnlyExplicitAuthorities() throws Exception {
        JSONObject grants =
                new JSONObject()
                        .put("messaging", true)
                        .put("notifications", true)
                        .put("dictation", true);
        JSONObject style = new JSONObject().put("font", "Roboto-Regular.ttf");
        JSONObject extensions = new JSONObject().put("generation", 4);
        JSONObject capabilities = new JSONObject().put("notificationReplies", true);
        HostSnapshotState state = HostSnapshotState.from(grants, style, extensions, capabilities);
        assertTrue(state.messagingAllowed);
        assertTrue(state.notificationReplyAllowed);
        grants.put("messaging", false);
        style.put("font", "bad");
        assertTrue(state.messagingAllowed);
        assertEquals("Roboto-Regular.ttf", state.sharedStyle.getString("font"));
    }

    @Test
    public void missingOptionalFieldsDoNotCreatePermission() {
        HostSnapshotState state =
                HostSnapshotState.from(
                        new JSONObject(), new JSONObject(), new JSONObject(), new JSONObject());
        assertFalse(state.messagingAllowed);
        assertFalse(state.notificationReplyAllowed);
    }
}
