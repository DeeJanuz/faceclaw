package com.faceclaw.sdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class CapabilityContractTest {
    @Test
    public void argumentsAreCopiedAndValidatedAgainstPublishedSchema() throws Exception {
        JSONObject schema =
                Protocol.object(
                        "type",
                        "object",
                        "properties",
                        Protocol.object(
                                "text",
                                        Protocol.object(
                                                "type", "string", "minLength", 1, "maxLength", 20),
                                "count",
                                        Protocol.object(
                                                "type", "integer", "minimum", 1, "maximum", 3)),
                        "required",
                        new JSONArray().put("text"),
                        "additionalProperties",
                        false);
        JSONObject clean =
                CapabilityContract.arguments(
                        schema, Protocol.object("text", "hello\nworld", "count", 2));
        assertEquals("hello\nworld", clean.getString("text"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CapabilityContract.arguments(
                                schema, Protocol.object("text", "hello", "extra", true)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CapabilityContract.arguments(
                                schema, Protocol.object("text", "hello", "count", 4)));
    }

    @Test
    public void resultProgressCannotClaimTerminalState() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CapabilityContract.result(Protocol.object("state", "completed"), true));
    }
}
