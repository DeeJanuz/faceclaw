package com.faceclaw.sdk;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.system.OsConstants;
import org.json.JSONObject;
import java.nio.ByteBuffer;
import java.util.UUID;

/** App-owned service. All callbacks run on the main looper. The host never loads app code. */
public abstract class FaceclawAppService extends Service {
 static FaceclawAppService active;
 private final Handler handler=new Handler(Looper.getMainLooper());
 private final Messenger incoming=new Messenger(new Handler(Looper.getMainLooper(),m->{ receive(m); return true; }));
 private Messenger host, pendingHost;
 private android.content.SharedPreferences approvals;
 private String hostIdentity="", session="", pendingToken="", pendingPin="", pendingSession="";
 private long pendingUntil, sequence, inFlight, generation;
 private int width,height; private boolean visible,screenOn=true;
 private byte[] latestPixels;
 private IBinder.DeathRecipient death;
 private boolean notificationReplyAllowed;
 private final NotificationReplies notificationReplies=new NotificationReplies();
 @Override public void onCreate() { super.onCreate(); approvals=ApprovalStore.open(this,"faceclaw-host"); active=this; }
 @Override public IBinder onBind(Intent intent) { return incoming.getBinder(); }
 @Override public void onDestroy() { disconnect(); if(active==this) active=null; super.onDestroy(); }
 protected void onHostConnected() {}
 protected void onHostDisconnected() {}
 protected abstract void onHostEvent(String type,JSONObject data);
 public final String selectedHostPackage() {
  String pin=approvals.getString("identity","");
  return pin.isEmpty()?"":PackageIdentity.packageName(pin);
 }
 public final boolean selectedHostIsInstalled() {
  String pin=approvals.getString("identity","");
  try { return !pin.isEmpty() && pin.equals(PackageIdentity.forPackage(this,PackageIdentity.packageName(pin))); }
  catch(Exception ignored) { return false; }
 }
 public final String selectedHostLabel() {
  if(!selectedHostIsInstalled()) return "";
  try { return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(selectedHostPackage(),0)).toString(); }
  catch(Exception ignored) { return ""; }
 }
 private void receive(Message m) {
  try {
   String identity=PackageIdentity.forUid(this,m.sendingUid);
   Bundle b=m.getData(); String suppliedSession=b.getString("session","");
   if(m.what==Protocol.HELLO) {
    if(m.replyTo==null || Protocol.json(b).optInt("version")!=Protocol.VERSION || suppliedSession.length()<20 || suppliedSession.length()>80) return;
    String pin=approvals.getString("identity","");
    if(!identity.equals(pin)) {
     // An unselected host cannot displace a selected connection without a user gesture.
     if(SystemClock.elapsedRealtime()<pendingUntil && !identity.equals(pendingPin)) return;
     pendingPin=identity; pendingSession=suppliedSession; pendingHost=m.replyTo;
     pendingToken=UUID.randomUUID().toString(); pendingUntil=SystemClock.elapsedRealtime()+120000;
     Intent intent=new Intent(this,HostApprovalActivity.class).putExtra("token",pendingToken).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
     PendingIntent consent=PendingIntent.getActivity(this,0,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
     Message reply=Protocol.message(Protocol.CONSENT,suppliedSession,"consent",null); reply.getData().putParcelable("consent",consent); m.replyTo.send(reply); return;
    }
    connect(m.replyTo,identity,suppliedSession); return;
   }
   if(host==null || !identity.equals(hostIdentity) || !session.equals(suppliedSession)) return;
   if(m.what==Protocol.ACK) { if(b.getLong("sequence")==inFlight) { inFlight=0; flushFrame(); } return; }
   if(m.what!=Protocol.EVENT) return;
   String type=b.getString("type",""); JSONObject data=Protocol.json(b);
   if(type.equals("revoke")) { disconnect(); return; }
   if(type.equals("open") || type.equals("resize")) {
    int w=data.getInt("width"), h=data.getInt("height"); Protocol.frameSize(w,h);
    width=w; height=h; generation=data.getLong("generation"); latestPixels=null; inFlight=0;
   }
   if(type.equals("close")) { width=height=0; visible=false; latestPixels=null; inFlight=0; }
   if(type.equals("visibility")) { visible=data.optBoolean("visible"); screenOn=data.optBoolean("screenOn"); if(!visible||!screenOn) latestPixels=null; }
   if(type.equals("capabilities")) {
    notificationReplyAllowed=Boolean.TRUE.equals(data.opt("notifications"))&&Boolean.TRUE.equals(data.opt("dictation"))&&Boolean.TRUE.equals(data.opt("notificationReplies"));
    if(!notificationReplyAllowed) notificationReplies.clear();
   }
   if(type.equals("notification-reply")) {
    if(!notificationReplyAllowed || !Boolean.TRUE.equals(data.opt("confirmed")) || !(data.opt("id") instanceof String) || !(data.opt("target") instanceof String) || !(data.opt("replyToken") instanceof String) || !(data.opt("text") instanceof String) || data.getString("text").trim().isEmpty() || data.getString("text").length()>8000 ||
      !notificationReplies.consume(data.optString("id"),data.optString("target"),data.optString("replyToken"),System.currentTimeMillis())) return;
   }
   onHostEvent(type,data);
  } catch(Exception ignored) { /* Malformed or unauthorized IPC never reaches app callbacks. */ }
 }
 String pendingIdentity(String token) {
  return token!=null && token.equals(pendingToken) && SystemClock.elapsedRealtime()<pendingUntil?pendingPin:null;
 }
 void approveHost(String token) {
  try {
   if(pendingIdentity(token)==null || !PackageIdentity.forPackage(this,PackageIdentity.packageName(pendingPin)).equals(pendingPin)) return;
   String pin=pendingPin,s=pendingSession; Messenger remote=pendingHost;
   pendingUntil=0; pendingToken=""; pendingHost=null;
   approvals.edit().putString("identity",pin).commit();
   connect(remote,pin,s);
  } catch(Exception ignored) {}
 }
 private void connect(Messenger remote,String identity,String newSession) throws RemoteException {
  if(host!=null && !hostIdentity.equals(identity)) {
   try { host.send(Protocol.message(Protocol.EVENT,session,"host-switched",null)); } catch(Exception ignored) {}
  }
  disconnect(); host=remote; hostIdentity=identity; session=newSession; sequence=0;
  final String connectedSession=session;
  death=()->handler.post(()->{ if(session.equals(connectedSession)) disconnect(); });
  host.getBinder().linkToDeath(death,0);
  host.send(Protocol.message(Protocol.READY,session,"ready",Protocol.object("version",Protocol.VERSION)));
  onHostConnected();
 }
 private void disconnect() {
  notificationReplyAllowed=false; notificationReplies.clear();
  if(host!=null) {
   try { host.send(Protocol.message(Protocol.EVENT,session,"disconnected",null)); } catch(Exception ignored) {}
   if(death!=null) host.getBinder().unlinkToDeath(death,0);
   host=null; hostIdentity=""; session=""; width=height=0; visible=false; inFlight=0; latestPixels=null;
   onHostDisconnected();
  }
 }
 /** Coalesces to one pending frame plus one IPC frame, preventing animation queue growth. */
 public final void submitBitmap(Bitmap bitmap) {
  if(Looper.myLooper()!=Looper.getMainLooper()) throw new IllegalStateException("Submit on main thread");
  if(host==null || !visible || !screenOn || bitmap.getWidth()!=width || bitmap.getHeight()!=height) return;
  int count=Protocol.frameSize(width,height); int[] argb=new int[count]; bitmap.getPixels(argb,0,width,0,0,width,height);
  byte[] pixels=new byte[count];
  for(int i=0;i<count;i++) {
   int c=argb[i], alpha=c>>>24; int luminance=(((c>>16)&255)*54+((c>>8)&255)*183+(c&255)*19)>>8;
   // Final app frame is opaque; Canvas layers resolve alpha against black first.
   pixels[i]=(byte)Math.max(1,luminance*alpha/255);
  }
  latestPixels=pixels; flushFrame();
 }
 private void flushFrame() {
  if(host==null || inFlight!=0 || latestPixels==null) return;
  byte[] pixels=latestPixels; latestPixels=null;
  Message m=Protocol.message(Protocol.FRAME,session,"frame",null); Bundle b=m.getData();
  b.putLong("generation",generation); b.putInt("width",width); b.putInt("height",height); b.putLong("sequence",++sequence); inFlight=sequence;
  try {
   if(Build.VERSION.SDK_INT>=27) {
    SharedMemory memory=SharedMemory.create("faceclaw-frame",pixels.length);
    try {
     ByteBuffer mapping=memory.mapReadWrite(); mapping.put(pixels); SharedMemory.unmap(mapping);
     if(!memory.setProtect(OsConstants.PROT_READ)) throw new IllegalStateException("Frame seal failed");
     b.putParcelable("memory",memory); host.send(m);
    } finally { memory.close(); }
   } else { b.putByteArray("pixels",pixels); host.send(m); }
  } catch(Exception ignored) { disconnect(); }
 }
 private boolean send(String type,JSONObject data) {
  if(data.toString().length()>Protocol.MAX_JSON) return false;
  final Messenger destination=host; final String sendingSession=session;
  if(destination==null) return false;
  handler.post(()->{
   if(host!=destination || !session.equals(sendingSession)) return;
   try { destination.send(Protocol.message(Protocol.EVENT,sendingSession,type,data)); } catch(Exception ignored) { disconnect(); }
  });
  return true;
 }
 public final void postNotification(String id,String target,String title,String text,long expiresAtMs) {
  postNotification(id,target,title,text,expiresAtMs,"");
 }
 /** A fresh opaque token binds one explicit host-reviewed reply to this publication. */
 public final void postNotification(String id,String target,String title,String text,long expiresAtMs,String replyToken) {
  if(id==null||id.isEmpty()||id.length()>128||target==null||target.isEmpty()||target.length()>512||title==null||title.length()>160||text==null||text.length()>4096||replyToken==null||replyToken.length()>128) return;
  notificationReplies.publish(id,target,replyToken,expiresAtMs,System.currentTimeMillis());
  send("notification",Protocol.object("id",id,"target",target,"title",title,"text",text,"expiresAt",expiresAtMs,"replyToken",replyToken));
 }
 public final void removeNotification(String id) { notificationReplies.remove(id); send("remove-notification",Protocol.object("id",id)); }
 /** Report one bounded, content-free outcome for a consumed action in this session. */
 public final void reportNotificationReplyResult(String id,String replyToken,String status) {
  if(status!=null&&notificationReplies.report(id,replyToken,status,System.currentTimeMillis()))
   send("notification-reply-result",Protocol.object("id",id,"replyToken",replyToken,"status",status));
 }
 public final boolean requestDictation(String requestId,String target,String label) {
  return requestDictation(requestId,target,label,"");
 }
 public final boolean requestDictation(String requestId,String target,String label,String initialText) {
  if(initialText==null || initialText.length()>8000 || host==null) return false;
  return send("dictation",Protocol.object("requestId",requestId,"target",target,"label",label,"initialText",initialText));
 }
 /** Search confirmation is a distinct protocol purpose, never a message-send review. */
 public final boolean requestSearchDictation(String requestId,String target,String label) {
  if(requestId==null||requestId.isEmpty()||requestId.length()>128||target==null||target.isEmpty()||target.length()>512||label==null||label.length()>100||host==null) return false;
  return send("search-dictation",Protocol.object("requestId",requestId,"target",target,"label",label));
 }
 public final void cancelSearchDictation(String requestId) { send("cancel-search-dictation",Protocol.object("requestId",requestId)); }
 public final void cancelDictation(String requestId) { send("cancel-dictation",Protocol.object("requestId",requestId)); }
}
