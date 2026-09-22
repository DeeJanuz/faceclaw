package com.faceclaw.sdk;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class GlanceboardContractTest {
 private JSONObject content() { return Protocol.object("version",1,"title","Messages","emptyText","Caught up","expiresAt",61000,"entries",new JSONArray().put(Protocol.object("id","one","title","Signal","detail","Hello","expiresAt",2000))); }
 @Test public void acceptsCopiesAndLimitsSourceLifetimes() throws Exception {
  JSONObject input=content(),clean=GlanceboardContract.validate(input,1000);
  input.getJSONArray("entries").getJSONObject(0).put("title","Changed");
  assertEquals("Signal",clean.getJSONArray("entries").getJSONObject(0).getString("title"));
  assertEquals(2000,clean.getJSONArray("entries").getJSONObject(0).getLong("expiresAt"));
  assertEquals(0,GlanceboardContract.validate(input,2000).getJSONArray("entries").length());
 }
 @Test public void rejectsMalformedStaleOversizedAndDuplicateContent() throws Exception {
  JSONObject input=content(); input.put("expiresAt","61000"); assertThrows(IllegalArgumentException.class,()->GlanceboardContract.validate(input,1000));
  input.put("expiresAt",61001); assertThrows(IllegalArgumentException.class,()->GlanceboardContract.validate(input,1000));
  input.put("expiresAt",1000); assertThrows(IllegalArgumentException.class,()->GlanceboardContract.validate(input,1000));
  JSONObject duplicate=content(); duplicate.getJSONArray("entries").put(duplicate.getJSONArray("entries").get(0));
  assertThrows(IllegalArgumentException.class,()->GlanceboardContract.validate(duplicate,1000));
  JSONObject large=content(); large.getJSONArray("entries").getJSONObject(0).put("detail",new String(new char[257]));
  assertThrows(IllegalArgumentException.class,()->GlanceboardContract.validate(large,1000));
 }
}
