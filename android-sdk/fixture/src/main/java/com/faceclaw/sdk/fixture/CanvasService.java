package com.faceclaw.sdk.fixture;
import com.faceclaw.sdk.*;
import android.graphics.*;
import android.os.SystemClock;
import org.json.JSONObject;
import org.json.JSONArray;
/** Test APK only: pins its synthetic host without a user setup prompt. Never ship this subclass. */
public class CanvasService extends FaceclawAppService {
 int width=32,height=16,clicks; private String messagingRequest="";private FaceclawSession current;
 private final java.util.concurrent.ExecutorService renderer=java.util.concurrent.Executors.newSingleThreadExecutor();
 @Override protected void onSessionReady(FaceclawSession session) {current=session;session.windowSurface().setCanvasRenderer(renderer,(canvas,request)->draw(canvas,false));publishToolCapabilities(capabilities());}
 @Override public void onDestroy(){renderer.shutdownNow();super.onDestroy();}
 private JSONArray capabilities() {
  JSONObject schema=Protocol.object("type","object","properties",Protocol.object("text",Protocol.object("type","string","maxLength",100)),"required",new JSONArray().put("text"),"additionalProperties",false);
  return new JSONArray().put(Protocol.object("id","com.faceclaw.fixture.echo","version",1,"kind","operation","title","Echo fixture",
   "description","Return bounded synthetic text for capability conformance tests.","inputSchema",schema,"resultVisibility","agent",
   "durability","transient","operationClass","read","profiles",new JSONArray().put(Protocol.object("id","org.faceclaw.profile.echo","version",1))));
 }
 @Override protected void onHostSnapshot(HostSnapshot snapshot){width=snapshot.windowWidth;height=snapshot.windowHeight;}
 @Override protected void onInput(RenderSurface surface,FaceclawInputEvent event){if("click".equals(event.type))clicks++;surface.invalidate(InvalidateReason.INPUT);}
 @Override protected void onControlEvent(ControlEvent event) {String type=event.type;JSONObject data=event.data;
  if(type.equals("capability-request")) {
   String requestId=data.optString("requestId"),text=data.optJSONObject("arguments").optString("text");
   reportCapabilityResult(requestId,Protocol.object("state","completed","operationId",requestId,"message","Fixture completed","content",Protocol.object("text",text)));
  }
  if(type.equals("messaging-request")) {
   messagingRequest=data.optString("requestId");
   if(!data.optJSONObject("params").optBoolean("delay")) reportMessagingResult(messagingRequest,Protocol.object("status","fixture"));
  }
  if(type.equals("test-messaging-complete")) reportMessagingResult(messagingRequest,Protocol.object("status","fixture"));
  if(type.equals("test-publish-extensions")) publishExtensions(data.optJSONArray("declarations"));
  if(type.equals("extension-event")&&data.optString("type").equals("request")) {
   JSONObject request=data.optJSONObject("data"); respondExtension(data.optString("feature"),data.optLong("generation"),request.optString("requestId"),Protocol.object("ok",true));
   respondExtension(data.optString("feature"),data.optLong("generation"),request.optString("requestId"),Protocol.object("ok",true));
  }
  if(type.equals("extension-surface")&&(data.optString("type").equals("open")||data.optString("type").equals("resize"))&&current!=null){RenderSurface surface=current.extensionSurface(data.optString("feature"));surface.setCanvasRenderer(renderer,(canvas,request)->draw(canvas,true));}
  if(type.equals("test-own-notifications")) requestOwnNotifications();
  if(type.equals("test-system-menu")) requestSystemMenu();
  if(type.equals("test-host-refinement")) requestHostRefinement("synthetic-refine","Original","Followup");
  if(type.equals("test-search-request")) requestSearchDictation("synthetic-search", "synthetic-directory", "Search by name");
  if(type.equals("test-publish-reply")) postNotification("fixture-message","fixture-target","Synthetic sender","Synthetic body",System.currentTimeMillis()+60000,data.optString("token","fixture-token"));
  if(type.equals("notification-reply")) reportNotificationReplyResult(data.optString("id"),data.optString("replyToken"),"draft-saved");
  if(type.equals("open")||type.equals("resize")) { width=data.optInt("width"); height=data.optInt("height"); }
  if((type.equals("visibility")||type.equals("burst"))&&current!=null)current.windowSurface().invalidate(InvalidateReason.STATE);
 }
 private void draw(Canvas canvas,boolean extension){int w=canvas.getWidth(),h=canvas.getHeight();canvas.drawColor(extension?Color.WHITE:Color.BLACK);if(extension){Paint black=new Paint();black.setColor(Color.BLACK);canvas.drawPoint(1,0,black);return;}Ui.card(canvas,0,0,w,h,0,Color.WHITE);Ui.text(canvas,"Clicks: "+clicks,4,Math.min(h-2,14),10,Color.WHITE);long timestamp=SystemClock.elapsedRealtimeNanos();Paint paint=new Paint();for(int i=0;i<8&&i<w;i++){int digit=(int)(timestamp%255)+1;timestamp/=255;paint.setColor(Color.rgb(digit,digit,digit));canvas.drawPoint(i,0,paint);}}
}
