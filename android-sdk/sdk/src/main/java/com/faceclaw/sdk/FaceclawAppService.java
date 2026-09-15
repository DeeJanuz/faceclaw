package com.faceclaw.sdk;

import android.app.*;
import android.content.*;
import android.os.*;
import com.faceclaw.sdk.ipc.*;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.UUID;

/** Stable SDK 1.0 app endpoint. Application callbacks run on the main looper. */
public abstract class FaceclawAppService extends Service {
 /**
  * Legacy window policy state. A successful Binder send only proves transport
  * delivery, so sent values are scoped to the host window generation and are
  * replayed when that generation changes. Protection is transient and is
  * discarded when the window/session ends.
  */
 static final class WindowStateCache {
  static final long UNKNOWN_GENERATION=Long.MIN_VALUE;
  interface Sender { boolean send(String type,JSONObject data); }
  private final Sender sender;
  private boolean open;
  private long generation=UNKNOWN_GENERATION,nextLocalGeneration;
  private Boolean desiredMenu,desiredProtection;
  private Boolean sentMenu,sentProtection;
  private long sentMenuGeneration=UNKNOWN_GENERATION,sentProtectionGeneration=UNKNOWN_GENERATION;
  private Boolean sentMenuWhileClosed,sentProtectionWhileClosed;

  WindowStateCache(Sender sender){this.sender=sender;}

  synchronized void observeSnapshot(boolean windowOpen,long hostGeneration){
   if(windowOpen)observeOpen(hostGeneration);else observeClose();
  }

  synchronized void observeOpen(long hostGeneration){
   long next=hostGeneration>0?hostGeneration:(open?generation:++nextLocalGeneration);
   boolean changed=!open||generation!=next;
   open=true;generation=next;
   if(changed)resetSentState();
   // A newly observed window is the first lifecycle point at which the host
   // accepts these legacy controls. Reconcile here so snapshot and control
   // paths converge even when the caller does not make a second replay call.
   replay();
  }

  synchronized void observeClose(){
   open=false;generation=UNKNOWN_GENERATION;resetSentState();
   // Protection is a claim over one visible flow, never a persistent app
   // preference. An app must assert it again for a later window.
   desiredProtection=null;
  }

  synchronized void observeDisconnect(){observeClose();}

  synchronized void clearProtection(){desiredProtection=null;sentProtection=null;sentProtectionWhileClosed=null;}

  synchronized boolean setMenuAvailable(boolean available){
   desiredMenu=available;
   if(!open){
    if(Boolean.valueOf(available).equals(sentMenuWhileClosed))return true;
    boolean sent=send("window-menu-state",Protocol.object("available",available));
    if(sent)sentMenuWhileClosed=available;
    return sent;
   }
   return replayMenu();
  }

  synchronized boolean setProtected(boolean protectedState){
   desiredProtection=protectedState;
   if(!open){
    if(Boolean.valueOf(protectedState).equals(sentProtectionWhileClosed))return true;
    boolean sent=send("window-protection",Protocol.object("protected",protectedState));
    if(sent)sentProtectionWhileClosed=protectedState;
    return sent;
   }
   return replayProtection();
  }

  synchronized void replay(){
   if(!open)return;
   replayMenu();replayProtection();
  }

  private boolean replayMenu(){
   if(desiredMenu==null)return true;
   if(sentMenuGeneration==generation&&desiredMenu.equals(sentMenu))return true;
   boolean sent=send("window-menu-state",Protocol.object("available",desiredMenu));
   if(sent){sentMenu=desiredMenu;sentMenuGeneration=generation;}
   return sent;
  }

  private boolean replayProtection(){
   if(desiredProtection==null)return true;
   if(sentProtectionGeneration==generation&&desiredProtection.equals(sentProtection))return true;
   boolean sent=send("window-protection",Protocol.object("protected",desiredProtection));
   if(sent){sentProtection=desiredProtection;sentProtectionGeneration=generation;}
   return sent;
  }

  private boolean send(String type,JSONObject data){return sender!=null&&sender.send(type,data);}

  private void resetSentState(){
   sentMenu=null;sentProtection=null;
   sentMenuGeneration=UNKNOWN_GENERATION;sentProtectionGeneration=UNKNOWN_GENERATION;
   sentMenuWhileClosed=null;sentProtectionWhileClosed=null;
  }
 }
 static FaceclawAppService active;
 private final Handler handler=new Handler(Looper.getMainLooper());
 private IFaceclawHostSession host,pendingHost;
 private volatile FaceclawSession faceclawSession;
 private android.content.SharedPreferences approvals;
 private String hostIdentity="",pendingToken="",pendingPin="",pendingSession="";
 private volatile String wireSession="";
 private volatile int hostUid=-1;
 private int pendingUid=-1;
 private long pendingUntil;
 private IBinder.DeathRecipient death;
 private JSONObject extensionSnapshot=new JSONObject(),sharedStyle=new JSONObject();
 private JSONArray toolCapabilities=new JSONArray();
 private final java.util.Map<String,Long> capabilityRequests=new java.util.concurrent.ConcurrentHashMap<>();
 private final WindowStateCache windowState=new WindowStateCache((type,data)->send(type,data));
 private volatile ConnectionState connectionState=ConnectionState.DISCOVERED;
 private final IFaceclawAppEndpoint.Stub endpoint=new IFaceclawAppEndpoint.Stub(){
  @Override public void connect(SessionHello hello,IFaceclawHostSession remote){int uid=Binder.getCallingUid();handler.post(()->receiveHello(uid,hello,remote));}
 };
 private IFaceclawAppSession.Stub appSession(String boundSession,FaceclawSession boundFaceclawSession){return new IFaceclawAppSession.Stub(){
  private boolean current(){return boundSession.equals(wireSession)&&boundFaceclawSession==faceclawSession;}
  private boolean authorized(){return Binder.getCallingUid()==hostUid&&current();}
  private void post(Runnable action){handler.post(()->{if(current())action.run();});}
  @Override public void applyHostSnapshot(HostSnapshot value){if(authorized())post(()->boundFaceclawSession.applySnapshot(value));}
  @Override public void grantRenderCredit(RenderCredit value){if(authorized())post(()->boundFaceclawSession.grant(value));}
  @Override public void onBufferReleased(String id,long generation,int slot,long sequence){if(authorized())post(()->boundFaceclawSession.released(id,generation,slot,sequence));}
  @Override public void onFrameOutcome(FrameOutcome value){if(authorized())post(()->boundFaceclawSession.outcome(value));}
  @Override public void onInput(String surfaceId,FaceclawInputEvent value){if(authorized())post(()->boundFaceclawSession.applyInput(surfaceId,value));}
  @Override public void sendControl(ControlEvent value){if(authorized())post(()->boundFaceclawSession.applyControl(value));}
  @Override public void close(DisconnectInfo value){if(authorized())post(()->disconnect(value==null?new DisconnectInfo(DisconnectInfo.Reason.UNKNOWN,false,""):value,false));}
 };}
 public final ConnectionState connectionState(){return connectionState;}
 public final JSONObject sharedStyle() { try { return new JSONObject(sharedStyle.toString()); } catch(Exception ignored) { return new JSONObject(); } }
 public final JSONObject extensions() { try { return new JSONObject(extensionSnapshot.toString()); } catch(Exception ignored) { return new JSONObject(); } }
 /** Explicit compatibility fallback: legacy hosts retain host-owned assistant invocation. */
 public final boolean publishCompatibleExtensions(JSONArray declarations){
  try{JSONArray copy=new JSONArray(declarations.toString());if(controls()==null||!controls().supports("invocation.lifecycle"))for(int i=0;i<copy.length();i++){JSONObject d=copy.getJSONObject(i);if(d.optString("feature").equals("assistant")&&d.optJSONObject("configuration")!=null)d.getJSONObject("configuration").remove("invocation");}return publishExtensions(copy);}catch(Exception invalid){return false;}
 }
 public final boolean publishExtensions(org.json.JSONArray declarations) {
  try { return send("publish-extensions",Protocol.object("declarations",ExtensionContract.declarations(declarations))); } catch(Exception ignored) { return false; }
 }
 /** Publishes app-owned operations and interface entry points. The host assigns
  * routing identity and catalog generation; declarations contain no authority. */
 public final boolean publishToolCapabilities(JSONArray declarations) {
  try {
   JSONArray clean=CapabilityContract.declarations(declarations); toolCapabilities=clean;
   return send("publish-tool-capabilities",Protocol.object("version",CapabilityContract.VERSION,"capabilities",clean));
  } catch(Exception ignored) { return false; }
 }
 public final boolean capabilityRequestCurrent(String requestId) {
  Long expiry=capabilityRequests.get(requestId);
  return expiry!=null&&expiry>System.currentTimeMillis();
 }
 public final boolean reportCapabilityProgress(String requestId,JSONObject result) {
  try {
   if(!capabilityRequestCurrent(requestId)) return false;
   return send("capability-progress",Protocol.object("requestId",requestId,"result",CapabilityContract.result(result,true)));
  } catch(Exception ignored) { return false; }
 }
 public final boolean reportCapabilityResult(String requestId,JSONObject result) {
  try {
   if(!capabilityRequestCurrent(requestId)) return false;
   JSONObject clean=CapabilityContract.result(result,false); capabilityRequests.remove(requestId);
   return send("capability-result",Protocol.object("requestId",requestId,"result",clean));
  } catch(Exception ignored) { return false; }
 }
 public final boolean respondExtension(String feature,long generation,String requestId,JSONObject data) {
  if(!ExtensionContract.known(feature)||!ExtensionContract.token(requestId)||data==null||data.toString().length()>Protocol.MAX_JSON/2) return false;
  return send("extension-result",Protocol.object("feature",feature,"generation",generation,"requestId",requestId,"data",data));
 }
 public final boolean reportExtensionProgress(String feature,long generation,String requestId,JSONObject data) {
  if(!ExtensionContract.known(feature)||!ExtensionContract.token(requestId)||data==null||data.toString().length()>Protocol.MAX_JSON/2) return false;
  return send("extension-progress",Protocol.object("feature",feature,"generation",generation,"requestId",requestId,"data",data));
 }
 public final boolean invokeExtensionAction(String feature,long generation,String action,JSONObject data) {
  if(!ExtensionContract.action(feature,action)||data==null||data.toString().length()>Protocol.MAX_JSON/2) return false;
  return send("extension-action",Protocol.object("feature",feature,"generation",generation,"action",action,"actionId",UUID.randomUUID().toString(),"data",data));
 }
 private volatile boolean messagingAllowed;
 private final java.util.Map<String,Long> messagingRequests=new java.util.concurrent.ConcurrentHashMap<>();
 public final boolean messagingRequestCurrent(String requestId) {
  Long expiry=messagingRequests.get(requestId);
  return messagingAllowed&&expiry!=null&&expiry>System.currentTimeMillis();
 }
 public final boolean reportMessagingResult(String requestId,JSONObject result) {
  if(!messagingRequestCurrent(requestId)||result==null||result.toString().length()>24000) return false;
  messagingRequests.remove(requestId);
  return send("messaging-result",Protocol.object("requestId",requestId,"result",result));
 }
 private boolean notificationReplyAllowed;
 private final NotificationReplies notificationReplies=new NotificationReplies();
 @Override public void onCreate() { super.onCreate(); approvals=ApprovalStore.open(this,"faceclaw-host"); active=this; }
 @Override public IBinder onBind(Intent intent) { connectionState=ConnectionState.BINDING;return endpoint; }
 @Override public void onDestroy() { disconnect(new DisconnectInfo(DisconnectInfo.Reason.HOST_STOPPED,false,"Service destroyed"),true); if(active==this) active=null; super.onDestroy(); }
 protected void onSessionReady(FaceclawSession session) {}
 protected void onHostSnapshot(HostSnapshot snapshot) {}
 protected void onInput(RenderSurface surface,FaceclawInputEvent event) {}
 protected abstract void onControlEvent(ControlEvent event);
 protected void onSessionLost(DisconnectInfo info) {}
 protected void onFrameOutcome(FrameOutcome outcome) {}
 /** Content-free local SDK failures; application logs must not add pixels or user text. */
 protected void onSdkDiagnostic(SdkDiagnostic diagnostic) {}
 public final FaceclawSession session(){return faceclawSession;}
 public final AppControls controls(){return faceclawSession==null?null:faceclawSession.controls();}
 private void applyHostSnapshotState(HostSnapshot snapshot){
  HostSnapshotState state=HostSnapshotState.from(snapshot);sharedStyle=state.sharedStyle;extensionSnapshot=state.extensions;messagingAllowed=state.messagingAllowed;notificationReplyAllowed=state.notificationReplyAllowed;
  if(!messagingAllowed)messagingRequests.clear();if(!notificationReplyAllowed)notificationReplies.clear();
  Ui.applySharedStyle(this,sharedStyle);
 }
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
 private void receiveHello(int uid,SessionHello hello,IFaceclawHostSession remote) {
  try {
   connectionState=ConnectionState.AUTHENTICATING;
   if(remote==null||hello==null)return;String supplied=hello.sessionId;int version=hello.protocolMajor;
   if(version!=Protocol.VERSION){connectionState=ConnectionState.PERMANENTLY_REJECTED;remote.close(new DisconnectInfo(DisconnectInfo.Reason.UPDATE_REQUIRED,false,"Faceclaw protocol "+Protocol.VERSION+" required"));return;}
   if(supplied.length()<20||supplied.length()>80)return;String identity=PackageIdentity.forUid(this,uid),pin=approvals.getString("identity","");
   if(!identity.equals(pin)){
    if(SystemClock.elapsedRealtime()<pendingUntil&&!identity.equals(pendingPin))return;
    pendingPin=identity;pendingSession=supplied;pendingHost=remote;pendingUid=uid;pendingToken=UUID.randomUUID().toString();pendingUntil=SystemClock.elapsedRealtime()+120000;
    Intent intent=new Intent(this,HostApprovalActivity.class).putExtra("token",pendingToken).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    PendingIntent consent=PendingIntent.getActivity(this,0,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);remote.onConsentRequired(new ConsentRequest(consent,supplied));return;
   }
   connect(remote,identity,supplied,uid);
  }catch(Exception ignored){}
 }
 private void receiveControl(ControlEvent wire){if(faceclawSession!=null)faceclawSession.applyControl(wire);}
 private void handleControl(ControlEvent event){
  String type=event.type;JSONObject data=event.data;
  try{
   if(type.equals("invocation-event")&&(controls()==null||!controls().acceptInvocation(data)))return;
   if(type.equals("open")||type.equals("resize"))windowState.observeOpen(data.optLong("generation"));
   else if(type.equals("close"))windowState.observeClose();
   else if(type.equals("visibility")&&(!data.optBoolean("visible")||!data.optBoolean("screenOn")))windowState.clearProtection();
   if(type.equals("shared-style")){sharedStyle=ExtensionContract.configuration("ui.typography",data);Ui.applySharedStyle(this,sharedStyle);}
   if(type.equals("extensions"))extensionSnapshot=new JSONObject(data.toString());
   if(type.equals("capabilities")){messagingAllowed=Boolean.TRUE.equals(data.opt("messaging"));if(!messagingAllowed)messagingRequests.clear();notificationReplyAllowed=Boolean.TRUE.equals(data.opt("notifications"))&&Boolean.TRUE.equals(data.opt("dictation"))&&Boolean.TRUE.equals(data.opt("notificationReplies"));if(!notificationReplyAllowed)notificationReplies.clear();}
   if(type.equals("notification-reply")&&(!notificationReplyAllowed||!Boolean.TRUE.equals(data.opt("confirmed"))||!(data.opt("id") instanceof String)||!(data.opt("target") instanceof String)||!(data.opt("replyToken") instanceof String)||!(data.opt("text") instanceof String)||data.getString("text").trim().isEmpty()||data.getString("text").length()>8000||!notificationReplies.consume(data.optString("id"),data.optString("target"),data.optString("replyToken"),System.currentTimeMillis())))return;
   if(type.equals("messaging-cancel")){messagingRequests.remove(data.optString("requestId"));return;}
   if(type.equals("capability-cancel")){capabilityRequests.remove(data.optString("requestId"));onControlEvent(event);return;}
   if(type.equals("capability-request")){long now=System.currentTimeMillis(),expiry=data.optLong("expiresAt");capabilityRequests.entrySet().removeIf(entry->entry.getValue()<=now);String requestId=data.optString("requestId"),capabilityId=data.optString("capabilityId");int version=data.optInt("capabilityVersion");JSONObject published=null;for(int i=0;i<toolCapabilities.length();i++){JSONObject capability=toolCapabilities.optJSONObject(i);if(capability!=null&&capabilityId.equals(capability.optString("id"))&&version==capability.optInt("version")){published=capability;break;}}if(published==null||!ExtensionContract.token(requestId)||capabilityRequests.containsKey(requestId)||capabilityRequests.size()>=32||expiry<=now||expiry>now+30000||data.optJSONObject("arguments")==null||data.optJSONObject("caller")==null||data.toString().length()>Protocol.MAX_JSON/2)return;try{data.put("arguments",CapabilityContract.arguments(published.getJSONObject("inputSchema"),data.getJSONObject("arguments")));}catch(Exception invalid){return;}capabilityRequests.put(requestId,expiry);}
   if(type.equals("messaging-request")){long now=System.currentTimeMillis();messagingRequests.entrySet().removeIf(entry->entry.getValue()<=now);String requestId=data.optString("requestId"),method=data.optString("method");long expiry=data.optLong("expiresAt");if(!messagingAllowed||!ExtensionContract.token(requestId)||messagingRequests.containsKey(requestId)||messagingRequests.size()>=32||expiry<=now||expiry>now+30000||!java.util.Arrays.asList("status","search","resolve","history","send","operation").contains(method)||data.optJSONObject("params")==null)return;messagingRequests.put(requestId,expiry);}
   onControlEvent(event);
   if(type.equals("open")||type.equals("resize"))windowState.replay();
  }catch(Exception ignored){}
 }
 String pendingIdentity(String token) {
  return token!=null && token.equals(pendingToken) && SystemClock.elapsedRealtime()<pendingUntil?pendingPin:null;
 }
 void approveHost(String token) {
  try {
   if(pendingIdentity(token)==null || !PackageIdentity.forPackage(this,PackageIdentity.packageName(pendingPin)).equals(pendingPin)) return;
   String pin=pendingPin,s=pendingSession; IFaceclawHostSession remote=pendingHost;int uid=pendingUid;
   pendingUntil=0; pendingToken=""; pendingHost=null;pendingUid=-1;
   approvals.edit().putString("identity",pin).commit();
   connect(remote,pin,s,uid);
  } catch(Exception ignored) {}
 }
 private void connect(IFaceclawHostSession remote,String identity,String newSession,int uid) throws RemoteException {
  disconnect(new DisconnectInfo(DisconnectInfo.Reason.HOST_STOPPED,true,"Host replaced"),false);host=remote;hostIdentity=identity;wireSession=newSession;hostUid=uid;
  if(faceclawSession==null)faceclawSession=new FaceclawSession(remote,new FaceclawSession.Callback(){public void onSnapshot(HostSnapshot snapshot){applyHostSnapshotState(snapshot);windowState.observeSnapshot(snapshot!=null&&snapshot.windowOpen,snapshot==null?0:snapshot.windowGeneration);onHostSnapshot(snapshot);windowState.replay();}public void onControl(ControlEvent event){handleControl(event);}public void onInput(RenderSurface surface,FaceclawInputEvent event){FaceclawAppService.this.onInput(surface,event);}public void onCreditWithoutRenderer(RenderSurface surface,RenderCredit credit){long targetMs=System.currentTimeMillis()+Math.max(0,(credit.targetPresentationTimeNanos-SystemClock.elapsedRealtimeNanos())/1_000_000L);onControlEvent(new ControlEvent("render",Protocol.object("surfaceId",surface.id(),"targetPresentationTimeNanos",credit.targetPresentationTimeNanos,"targetPresentationTimeMs",targetMs,"traceId",credit.traceId)));}public void onOutcome(FrameOutcome outcome){onFrameOutcome(outcome);}public void onDiagnostic(SdkDiagnostic diagnostic){onSdkDiagnostic(diagnostic);}public void onTransportLost(IFaceclawHostSession failedHost){if(host==failedHost)disconnect(new DisconnectInfo(DisconnectInfo.Reason.BINDER_DIED,true,"Host transport failed"),false);}});else faceclawSession.attach(remote);
  final String connectedSession=wireSession;death=()->handler.post(()->{if(wireSession.equals(connectedSession))disconnect(new DisconnectInfo(DisconnectInfo.Reason.BINDER_DIED,true,"Host binder died"),false);});remote.asBinder().linkToDeath(death,0);
  remote.onReady(new SessionHello(Protocol.VERSION,Protocol.SDK_VERSION,wireSession),appSession(connectedSession,faceclawSession));connectionState=ConnectionState.READY;onSessionReady(faceclawSession);
 }
 private void disconnect(DisconnectInfo info,boolean notifyHost) {
  windowState.observeDisconnect();
  messagingAllowed=false; messagingRequests.clear(); capabilityRequests.clear();
  extensionSnapshot=new JSONObject(); sharedStyle=new JSONObject(); Ui.resetSharedStyle();
  notificationReplyAllowed=false; notificationReplies.clear();
  if(host!=null) {
   IFaceclawHostSession previous=host;if(notifyHost)try{previous.close(info);}catch(Exception ignored){}
   if(death!=null)previous.asBinder().unlinkToDeath(death,0);host=null;hostIdentity="";wireSession="";hostUid=-1;if(faceclawSession!=null){if(info.recoverable)faceclawSession.detach();else{faceclawSession.closeSilently();faceclawSession=null;}}boolean permanent=info.reason==DisconnectInfo.Reason.IDENTITY_CHANGED||info.reason==DisconnectInfo.Reason.REVOKED||info.reason==DisconnectInfo.Reason.UPDATE_REQUIRED||info.reason==DisconnectInfo.Reason.PROTOCOL_ABUSE;connectionState=info.recoverable?ConnectionState.RECOVERING:permanent?ConnectionState.PERMANENTLY_REJECTED:ConnectionState.DISCOVERED;onSessionLost(info);
  }
 }
 private boolean send(String type,JSONObject data) {
  if(data.toString().length()>Protocol.MAX_JSON) return false;
  FaceclawSession current=faceclawSession;return current!=null&&current.sendControl(type,data);
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
 public final boolean requestCaptureDictation(String requestId,String label) {
  if(!ExtensionContract.token(requestId)||label==null||label.length()>100) return false;
  return requestCaptureDictation(requestId,label,false);
 }
 public final boolean requestCaptureDictation(String requestId,String label,boolean ownTranscription) {
  if(!ExtensionContract.token(requestId)||label==null||label.length()>100) return false;
  return send("capture-dictation",Protocol.object("requestId",requestId,"label",label,"ownTranscription",ownTranscription));
 }
 public final void finishCaptureDictation(String requestId) { if(ExtensionContract.token(requestId)) send("finish-capture-dictation",Protocol.object("requestId",requestId)); }
 public final void cancelCaptureDictation(String requestId) { if(ExtensionContract.token(requestId)) send("cancel-capture-dictation",Protocol.object("requestId",requestId)); }
 /** Explicit own-editor fallback; host retains its key and requires dictation consent and user action. */
 public final boolean requestHostRefinement(String requestId,String original,String followup) {
  if(!ExtensionContract.token(requestId)||original==null||original.length()>8000||followup==null||followup.trim().isEmpty()||followup.length()>8000) return false;
  return send("host-refinement",Protocol.object("requestId",requestId,"original",original,"followup",followup));
 }
 public final void cancelHostRefinement(String requestId) { if(ExtensionContract.token(requestId)) send("cancel-host-refinement",Protocol.object("requestId",requestId)); }
 public final boolean setWindowMenuAvailable(boolean available) { return windowState.setMenuAvailable(available); }
 public final boolean setWindowProtected(boolean protectedState) { return windowState.setProtected(protectedState); }
 /** Focus this approved app's own host window while the unlocked display is already active. */
 public final boolean requestOwnNotifications() { return send("own-notifications",new JSONObject()); }
 public final boolean invokeOwnNotification(String action,String key,long postTime,String callId) {
  if(!java.util.Arrays.asList("open","dismiss").contains(action)||key==null||key.length()>512||!ExtensionContract.token(callId)||postTime<0) return false;
  return send("own-notification-action",Protocol.object("action",action,"key",key,"postTime",postTime,"callId",callId));
 }
 public final boolean dismissOwnNotificationGroup(JSONArray items,String callId) {
  if(items==null||items.length()<1||items.length()>50||items.toString().length()>16000||!ExtensionContract.token(callId)) return false;
  return send("own-notification-action",Protocol.object("action","dismiss-group","items",items,"callId",callId));
 }
 public final boolean requestOpenWindow() { return requestOpenWindow(""); }
 public final boolean requestOpenWindow(String target) {
  if(target==null||target.length()>512) return false;
  return send("request-open-window",Protocol.object("target",target));
 }
 public final boolean requestSleep() { return send("sleep",new JSONObject()); }
 /** Ask the host to show its system menu for this app's foreground window after a user gesture. */
 public final boolean requestSystemMenu() { return send("request-system-menu",new JSONObject()); }
 public final void cancelDictation(String requestId) { send("cancel-dictation",Protocol.object("requestId",requestId)); }
}
