package com.faceclaw.sdk;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class CapabilityBridgeTest {
 @Test public void declarationUsesSeparatelyGrantedDeviceToolsFeature() throws Exception {
  JSONObject declaration=CapabilityBridge.extensionDeclaration("Example capability bridge").getJSONObject(0);
  assertEquals("device-tools",declaration.getString("feature"));
  assertEquals("Example capability bridge",declaration.getJSONObject("configuration").getString("label"));
 }

 @Test public void catalogAcceptsOnlyBoundedDeviceToolEvents() throws Exception {
  JSONObject tool=Protocol.object("name","com.example.echo","description","Echo a value",
   "inputSchema",Protocol.object("type","object","properties",new JSONObject(),"additionalProperties",false),
   "_meta",Protocol.object("org.faceclaw/capability",Protocol.object("protocolVersion",1)));
  JSONObject envelope=Protocol.object("feature","device-tools","generation",7,"type","event","data",
   Protocol.object("event","tool-catalog","tools",new JSONArray().put(tool)));
  JSONObject catalog=CapabilityBridge.catalog("extension-event",envelope);
  assertEquals(7,catalog.getLong("generation"));
  assertEquals("com.example.echo",catalog.getJSONArray("tools").getJSONObject(0).getString("name"));
  assertEquals(1,catalog.getJSONArray("tools").getJSONObject(0).getJSONObject("_meta").getJSONObject("org.faceclaw/capability").getInt("protocolVersion"));
  envelope.getJSONObject("data").getJSONArray("tools").getJSONObject(0).put("name","bad name");
  assertNull(CapabilityBridge.catalog("extension-event",envelope));
 }

 @Test public void resultMustMatchTheConsumedCall() throws Exception {
  JSONObject envelope=Protocol.object("feature","device-tools","generation",7,"type","event","data",
   Protocol.object("event","tool-result","callId","call-1","result",Protocol.object("ok",true)));
  assertEquals("call-1",CapabilityBridge.result("extension-event",envelope,7,"call-1").getString("callId"));
  assertNull(CapabilityBridge.result("extension-event",envelope,8,"call-1"));
  assertNull(CapabilityBridge.result("extension-event",envelope,7,"call-2"));
 }
}
