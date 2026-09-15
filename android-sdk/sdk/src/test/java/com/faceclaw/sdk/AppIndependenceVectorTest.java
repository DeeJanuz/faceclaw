package com.faceclaw.sdk;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class AppIndependenceVectorTest {
 private static JSONObject vectors() throws Exception {try(InputStream input=AppIndependenceVectorTest.class.getResourceAsStream("/app-independence-v1.json")){assertNotNull("shared vector fixture missing",input);byte[] bytes=new byte[65536];int length=input.read(bytes);return new JSONObject(new String(bytes,0,length,StandardCharsets.UTF_8));}}
 @Test public void catalogVectorsMatchSdkParser() throws Exception {
  JSONObject root=vectors();assertEquals(1,root.getInt("version"));JSONArray cases=root.getJSONArray("catalogCases");
  for(int i=0;i<cases.length();i++){JSONObject vector=cases.getJSONObject(i);AppIndependenceCatalog catalog=AppIndependenceCatalog.fromCapabilities(vector.getJSONObject("capabilities"));assertEquals(vector.getString("name"),vector.getString("state"),catalog.state.name());JSONArray supports=vector.getJSONArray("supports");for(int n=0;n<supports.length();n++){JSONObject requirement=supports.getJSONObject(n);assertTrue(vector.getString("name"),catalog.supports(requirement.getString("id"),requirement.getInt("minimumVersion")));}}
 }
 @Test public void requestsExecuteAgainstProductionHostLedger() throws Exception {
  JSONArray requests=vectors().getJSONArray("requestCases");
  java.util.Set<String> supported=new java.util.HashSet<>(java.util.Arrays.asList("control.result"));
  for(int i=0;i<requests.length();i++){
   JSONObject vector=requests.getJSONObject(i),request=vector.getJSONObject("request");ControlLedger ledger=new ControlLedger(1);
   if(vector.getBoolean("seedDuplicate")){JSONObject seed=IndependenceProtocol.copy(request);seed.getJSONObject("payload").put("available",true);assertTrue(ledger.admit(seed,100,5,supported).execute);ledger.complete(seed.getString("requestId"),"applied","");}
   ControlLedger.Admission result=ledger.admit(request,vector.getLong("nowElapsedMs"),vector.getLong("currentWindowGeneration"),supported);
   assertFalse(vector.getString("name"),result.execute);String expected=vector.getString("expected");assertEquals(vector.getString("name"),expected.equals("idempotent")?"applied":expected,result.result.getString(expected.equals("idempotent")?"state":"reason"));
  }
 }
 @Test public void exactAndOverUtf8BoundariesUseSharedVectors() throws Exception {
  JSONArray cases=vectors().getJSONArray("boundaryCases");for(int i=0;i<cases.length();i++){JSONObject v=cases.getJSONObject(i);try{IndependenceProtocol.bytes(new JSONObject().put("text",v.getString("text")),v.getInt("maxBytes"));assertTrue(v.getBoolean("valid"));}catch(IllegalArgumentException e){assertFalse(v.getBoolean("valid"));}}
 }
}
