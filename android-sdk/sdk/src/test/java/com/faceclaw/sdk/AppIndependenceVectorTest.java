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
 @Test public void requestVectorsStayBoundedAndStructurallyComplete() throws Exception {
  JSONArray requests=vectors().getJSONArray("requestCases");assertTrue(requests.length()>=4);for(int i=0;i<requests.length();i++){JSONObject request=requests.getJSONObject(i);assertTrue(request.getString("requestId").matches("[A-Za-z0-9_-]{1,128}"));assertTrue(request.has("expected"));}
 }
}
