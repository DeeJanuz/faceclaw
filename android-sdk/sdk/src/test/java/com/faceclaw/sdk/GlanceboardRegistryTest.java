package com.faceclaw.sdk;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
public class GlanceboardRegistryTest {
 @Test public void sharedContractVectors() throws Exception {
  JSONObject fixture=new JSONObject(new String(getClass().getResourceAsStream("/glanceboard.json").readAllBytes(),StandardCharsets.UTF_8));
  JSONObject registry=GlanceboardContract.registry(fixture.getJSONObject("registry"));
  JSONArray cases=fixture.getJSONArray("cases");
  for(int i=0;i<cases.length();i++){
   JSONObject item=cases.getJSONObject(i);boolean accepted=true;
   try{if(item.getString("kind").equals("registry"))GlanceboardContract.registry(item.getJSONObject("value"));else GlanceboardContract.content(item.getJSONObject("value"),registry,fixture.getLong("now"));}catch(IllegalArgumentException rejected){accepted=false;}
   assertEquals(item.getString("name"),item.getBoolean("valid"),accepted);
  }
 }
 @Test public void portableCanvasCopiesCommandsAndPacksPixels() throws Exception {
  GlanceCanvas canvas=new GlanceCanvas("home",61000).bitmap(0,0,2,1,new byte[]{(byte)255,0},190);
  JSONObject frame=canvas.build();assertEquals("gA==",frame.getJSONArray("commands").getJSONObject(0).getString("bits"));
  canvas.rect(0,1,10,10,255);assertEquals(1,frame.getJSONArray("commands").length());
 }
 @Test public void registryOwnershipAndUnregisterAreExplicit() {
  JSONObject empty=GlanceboardContract.registry(Protocol.object("version",1,"widgets",new JSONArray()));
  assertNull(GlanceboardContract.widget(empty,"home"));
  assertThrows(IllegalArgumentException.class,()->GlanceboardContract.content(new GlanceCanvas("home",2000).build(),empty,1000));
 }
}
