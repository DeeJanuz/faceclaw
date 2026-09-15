package com.faceclaw.sdk;
import org.json.JSONObject;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class CaptureLifecycleTest {
 @Test public void cancelWinsAgainstLateFinalAndCannotCancelTwice(){
  List<String> sent=new ArrayList<>();List<JSONObject> events=new ArrayList<>();CaptureSession capture=new CaptureSession("c",(type,data)->{sent.add(type);return true;},events::add);
  capture.cancel();capture.event("capture-transcript",Protocol.object("captureId","c","text","late","isFinal",true));capture.cancel();assertEquals(Collections.singletonList("capture-cancel"),sent);assertEquals(1,events.size());assertTrue(capture.terminal());assertEquals("cancelled",events.get(0).optString("status"));
 }
 @Test public void completionIsOneTerminalDraftEventAndForeignCaptureIsIgnored(){
  List<JSONObject> events=new ArrayList<>();CaptureSession capture=new CaptureSession("c",(type,data)->true,events::add);
  capture.event("capture-transcript",Protocol.object("captureId","foreign","text","wrong"));assertTrue(events.isEmpty());
  capture.event("capture-transcript",Protocol.object("captureId","c","text","draft","isFinal",true));capture.event("capture-status",Protocol.object("captureId","c","status","complete"));capture.event("capture-status",Protocol.object("captureId","c","status","complete"));assertEquals(2,events.size());assertFalse(events.get(0).has("confirmed"));
 }
 @Test public void localCancellationAllowsNextCaptureWithoutWaitingForHostAck(){
  List<JSONObject> sent=new ArrayList<>();AppControls client=new AppControls((type,data)->{sent.add(data);return true;},()->100,(delay,task)->{});
  AppIndependenceCatalog catalog=AppIndependenceCatalog.fromCapabilities(Protocol.object("appIndependence",Protocol.object("contractVersion",1,"epoch",1,"features",new org.json.JSONArray().put(Protocol.object("id","capture.session","version",1,"limits",new JSONObject())))));
  client.snapshot(catalog,3);client.receive("contract-result",IndependenceProtocol.negotiate(sent.get(0),Collections.singleton("capture.session"),1,0));
  CaptureSession first=client.capture(CaptureSession.Purpose.GENERIC,"Draft",0,event->{});first.cancel();
  CaptureSession second=client.capture(CaptureSession.Purpose.GENERIC,"Draft",0,event->{});assertFalse(second.terminal());assertNotEquals(first.id,second.id);
 }
}
