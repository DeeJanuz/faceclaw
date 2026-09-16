package com.faceclaw.sdk;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

public final class ExtensionContractTest {
 private static JSONObject notification(String seconds) throws Exception {
  JSONObject config=Protocol.object("label","T3 notifications");
  if(seconds!=null)config.put("previewSeconds",seconds);
  return Protocol.object("feature","ui.notifications","enabled",true,"configuration",config);
 }

 @Test public void notificationPreviewSecondsAcceptsExactlyTheSupportedWireTokens() throws Exception {
  for(String seconds:new String[]{"3","5","7","10"}) {
   JSONObject clean=ExtensionContract.configuration("ui.notifications",notification(seconds).getJSONObject("configuration"));
   assertEquals(seconds,clean.getString("previewSeconds"));
  }
 }

 @Test public void notificationPreviewSecondsRejectsWrongTypesUnknownValuesAndMalformedDeclarations() throws Exception {
  for(Object value:new Object[]{Integer.valueOf(5),JSONObject.NULL,new JSONObject(),"4","05","3.0",""}) {
   JSONObject config=Protocol.object("label","T3 notifications","previewSeconds",value);
   assertThrows(IllegalArgumentException.class,()->ExtensionContract.configuration("ui.notifications",config));
  }
  JSONObject unknown=Protocol.object("label","T3 notifications","previewWindowSeconds","5");
  assertThrows(IllegalArgumentException.class,()->ExtensionContract.configuration("ui.notifications",unknown));
  JSONObject malformed=Protocol.object("feature","ui.notifications","enabled",true,"configuration",new JSONArray());
  assertThrows(Exception.class,()->ExtensionContract.declarations(new JSONArray().put(malformed)));
 }

 @Test public void legacyNotificationDeclarationsRemainValidWithoutPreviewConfiguration() throws Exception {
  JSONArray declarations=new JSONArray().put(notification(null));
  assertEquals(1,ExtensionContract.declarations(declarations).length());
 }

 @Test public void compatibilityFilteringOnlyRemovesFieldsWithoutNegotiatedSupport() throws Exception {
  JSONArray supplied=new JSONArray().put(notification("7")).put(Protocol.object(
    "feature","assistant","enabled",true,"configuration",Protocol.object("label","T3","invocation","app")));
  JSONArray legacy=ExtensionContract.compatibleDeclarations(supplied,false,false);
  assertFalse(legacy.getJSONObject(0).getJSONObject("configuration").has("previewSeconds"));
  assertFalse(legacy.getJSONObject(1).getJSONObject("configuration").has("invocation"));
  assertTrue(supplied.getJSONObject(0).getJSONObject("configuration").has("previewSeconds"));
  JSONArray current=ExtensionContract.compatibleDeclarations(supplied,true,true);
  assertTrue(current.getJSONObject(0).getJSONObject("configuration").has("previewSeconds"));
  assertTrue(current.getJSONObject(1).getJSONObject("configuration").has("invocation"));
  ExtensionContract.declarations(legacy);
  ExtensionContract.declarations(current);
 }
}
