package com.faceclaw.app;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.os.*;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.*;
import com.faceclaw.sdk.*;
import com.faceclaw.sdk.ipc.*;
import org.json.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;

/** Host-side policy owner. Everything from an external UID is untrusted. */
public final class FaceclawExternalApps {
 private static FaceclawExternalApps instance;
 public static synchronized FaceclawExternalApps get(Context c) { if(instance==null) instance=new FaceclawExternalApps(c.getApplicationContext()); return instance; }
 private final Context context; private final Handler main=new Handler(Looper.getMainLooper());
 private final Map<String,Connection> connections=new java.util.concurrent.ConcurrentHashMap<>();
 private final Map<String,Integer> retryAttempts=new ConcurrentHashMap<>();
 private final Map<String,byte[]> restorationTokens=new ConcurrentHashMap<>();
 private final Map<String,RecoveryContext> recoveryContexts=new ConcurrentHashMap<>();
 private volatile FaceclawExternalAppListener listener;
 private final SharedPreferences prefs;
 private final FaceclawExtensions extensions;
 private final Map<String,Long> publishedFeatureGenerations=new HashMap<>();
 private final DisplayScheduler scheduler=new DisplayScheduler(main);
 private String publishedExtensionSnapshot="";
 private JSONObject sharedStyle=new JSONObject();
 private static final String[] APPROVAL_CAPABILITIES={"notifications","dictation","previews","suppress","messaging"};
 private static final long[] RETRY_DELAYS_MS={0,250,1000,2000,5000,10000};
 private static final int MAX_RENDER_QUEUE=64,MAX_PENDING_FRAMES=32;
 private static final long MAX_RENDER_QUEUE_BYTES=8L*1024*1024;
 private static final long FRAME_OUTCOME_TIMEOUT_MS=15000;
 private FaceclawExternalApps(Context c) {
  context=c; prefs=ApprovalStore.open(c,"faceclaw-external-apps");
  extensions=new FaceclawExtensions(prefs,new FaceclawExtensions.Owners() {
   public Map<String,String> approved() { Map<String,String> owners=new TreeMap<>(); for(ResolveInfo r:discover()) if(FaceclawExternalApps.this.approved(r.serviceInfo)) owners.put(key(r.serviceInfo),prefs.getString(key(r.serviceInfo)+":pin","")); return owners; }
   public boolean connected(String component) { Connection current=connections.get(component); return current!=null&&current.ready; }
  });
  IntentFilter filter=new IntentFilter(); filter.addAction(Intent.ACTION_PACKAGE_ADDED); filter.addAction(Intent.ACTION_PACKAGE_REMOVED); filter.addAction(Intent.ACTION_PACKAGE_REPLACED); filter.addAction(Intent.ACTION_PACKAGE_CHANGED); filter.addDataScheme("package");
  c.registerReceiver(new BroadcastReceiver() { public void onReceive(Context ignored,Intent intent) {
   if(Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction()) && !intent.getBooleanExtra(Intent.EXTRA_REPLACING,false) && intent.getData()!=null) {
    String pkg=intent.getData().getSchemeSpecificPart(); SharedPreferences.Editor edit=prefs.edit();
    for(String key:prefs.getAll().keySet()) if(key.startsWith(pkg+"/")) edit.remove(key); edit.apply();
    restorationTokens.keySet().removeIf(key->key.startsWith(pkg+"/"));retryAttempts.keySet().removeIf(key->key.startsWith(pkg+"/"));recoveryContexts.keySet().removeIf(key->key.startsWith(pkg+"/"));
   }
   refresh();
  } },filter);
 }
 public void setListener(FaceclawExternalAppListener value) { listener=value; refresh(); }
 private List<ResolveInfo> discover() { return context.getPackageManager().queryIntentServices(new Intent(Protocol.ACTION),PackageManager.GET_META_DATA); }
 private String key(ServiceInfo s) { return new ComponentName(s.packageName,s.name).flattenToString(); }
 private boolean validService(ServiceInfo s) { return key(s).length()<=512 && s.exported && s.enabled && s.applicationInfo.enabled && s.metaData!=null && s.metaData.getInt("com.faceclaw.PROTOCOL_MAJOR",0)==Protocol.VERSION; }
 private boolean approved(ServiceInfo s) {
  return validService(s)&&identityApproved(s);
 }
 private boolean identityApproved(ServiceInfo s) {
  try { return PackageIdentity.forPackage(context,s.packageName).equals(prefs.getString(key(s)+":pin","")) && PackageIdentity.forUid(context,s.applicationInfo.uid).equals(prefs.getString(key(s)+":pin","")); } catch(Exception e) { return false; }
 }
 public String installedJson() {
  JSONArray result=new JSONArray();
  for(ResolveInfo r:discover()) if(identityApproved(r.serviceInfo)) {
   Connection c=connections.get(key(r.serviceInfo));
   boolean compatible=validService(r.serviceInfo);result.put(Protocol.object("component",key(r.serviceInfo),"name",r.loadLabel(context.getPackageManager()).toString(),"connected",compatible&&c!=null&&c.ready,"state",compatible?(c!=null&&c.ready?"READY":"DISCOVERED"):"UPDATE_REQUIRED","messaging",allows(key(r.serviceInfo),"messaging")?r.serviceInfo.metaData.getString("com.faceclaw.MESSAGING",""):""));
  }
  return result.toString();
 }
 public void refresh() {
  Set<String> present=new HashSet<>();
  for(ResolveInfo r:discover()) if(approved(r.serviceInfo)) { String k=key(r.serviceInfo); present.add(k); if(!connections.containsKey(k)&&connections.size()<8) bind(r.serviceInfo); }
  for(String k:new ArrayList<>(connections.keySet())) if(!present.contains(k)) disconnect(k,false);
  emit("","changed",new JSONObject()); publishGlanceRegistry(); extensionsChanged();
 }
 private void bind(ServiceInfo s) {
  Connection c=new Connection(s); connections.put(c.component,c);
  try { if(!context.bindService(new Intent(Protocol.ACTION).setComponent(new ComponentName(s.packageName,s.name)),c,Context.BIND_AUTO_CREATE)) disconnect(c.component,true); }
  catch(Exception e) { disconnect(c.component,true); }
 }
 private void emit(String component,String type,JSONObject data) { if(listener!=null) listener.onEvent(component,type,data.toString()); }
 public boolean isConnected(String component) { Connection c=connections.get(component); return c!=null&&c.ready&&approved(c.service); }
 public String messagingIdentity(String component) {
  if(!isConnected(component)) return "";
  try {
   byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(prefs.getString(component+":pin","").getBytes(java.nio.charset.StandardCharsets.UTF_8));
   StringBuilder hex=new StringBuilder(); for(byte value:digest) hex.append(String.format("%02x",value)); return hex.toString();
  } catch(Exception error) { return ""; }
 }
 public boolean allows(String component,String capability) { return Arrays.asList(APPROVAL_CAPABILITIES).contains(capability)&&isConnected(component)&&prefs.getBoolean(component+":"+capability,"previews".equals(capability)); }
 private boolean hasVisibleSurface(Connection c,String feature) {
  if(c==null) return false;
  Surface surface=c.surfaces.get(feature);
  return surface!=null&&surface.visible&&surface.screenOn;
 }
 public boolean replyToNotification(String component,String json) {
  if(json==null||json.length()>Protocol.MAX_JSON||!allows(component,"notifications")||!allows(component,"dictation")) return false;
  Connection c=connections.get(component);
  try {
   JSONObject data=new JSONObject(json);
   if(!Boolean.TRUE.equals(data.opt("confirmed"))||!(data.opt("text") instanceof String)||data.getString("text").trim().isEmpty()||data.getString("text").length()>8000) return false;
   for(String field:new String[]{"id","target","replyToken"}) if(!(data.opt(field) instanceof String)||data.getString(field).isEmpty()||data.getString(field).length()>(field.equals("target")?512:128)) return false;
   c.sendControl("notification-reply",data); return true;
  } catch(Exception ignored) { return false; }
 }
 public void send(String component,String type,String json) {
  if(type.equals("notification-reply")) { replyToNotification(component,json); return; }
  Connection c=connections.get(component); if(c==null||!c.ready||!approved(c.service)) return;
  try {
   JSONObject data=new JSONObject(json);
   if(type.equals("host-state")){c.hostState=new JSONObject(data.toString());c.hostState.put("available",true);}
   String inputTrace="";if(type.equals("input")){inputTrace=data.optString("traceId");if(inputTrace.isEmpty()){inputTrace=UUID.randomUUID().toString();data.put("traceId",inputTrace);}}
   if(type.equals("messaging-request")&&!allows(component,"messaging")) return;
   if(type.equals("capability-request")) {
    String requestId=data.optString("requestId"),capabilityId=data.optString("capabilityId"); int version=data.optInt("capabilityVersion"); long now=System.currentTimeMillis(),expiry=data.optLong("expiresAt");
    JSONObject published=null; for(int i=0;i<c.capabilities.length();i++) { JSONObject capability=c.capabilities.optJSONObject(i); if(capability!=null&&capabilityId.equals(capability.optString("id"))&&version==capability.optInt("version")) { published=capability; break; } }
    if(published==null||!ExtensionContract.token(requestId)||c.capabilityRequests.containsKey(requestId)||c.capabilityRequests.size()>=32||data.optLong("catalogGeneration")!=c.capabilityGeneration||
       expiry<=now||expiry>now+30000||data.optJSONObject("arguments")==null||data.optJSONObject("caller")==null||data.toString().length()>Protocol.MAX_JSON/2) return;
    try { data.put("arguments",CapabilityContract.arguments(published.getJSONObject("inputSchema"),data.getJSONObject("arguments"))); }
    catch(Exception invalidArguments) { return; }
    c.capabilityRequests.put(requestId,capabilityId);
   }
   if(type.equals("capability-cancel")) c.capabilityRequests.remove(data.optString("requestId"));
   if(type.equals("open")||type.equals("resize")) {
    c.width=data.getInt("width"); c.height=data.getInt("height"); Protocol.frameSize(c.width,c.height); data.put("generation",++c.generation); c.open=true;
    c.windowSurface().reset(c.width,c.height,c.generation);
   }
   if(type.equals("close")) { c.open=false; c.visible=false; c.generation++; c.windowSurface().setState(false,false,c.generation);c.restorationToken=null;restorationTokens.remove(component); }
   if(type.equals("visibility")) { c.visible=data.optBoolean("visible"); c.screenOn=data.optBoolean("screenOn"); c.windowSurface().setState(c.visible,c.screenOn,c.generation); }
   if(type.equals("close")||type.equals("resize"))c.enqueueRender(128,()->sweepResources(c));
   if(type.equals("input"))c.sendInput("window",new FaceclawInputEvent(data));else c.sendControl(type,data);
   if(type.equals("render")||type.equals("input")||type.equals("open")||type.equals("resize")||(type.equals("visibility")&&c.visible&&c.screenOn))scheduleCredit(c,c.windowSurface(),type.equals("input")?DisplayScheduler.Priority.DIRECT_INPUT:DisplayScheduler.Priority.FOCUSED_ANIMATION,inputTrace);
  } catch(Exception e) { android.util.Log.w("FaceclawApps","control send failed type="+type+" category="+e.getClass().getSimpleName());disconnect(component,true); }
 }
 private void publishCapabilities(String component) {
  Connection current=connections.get(component);if(current!=null)invalidateContract(current);
  send(component,"capabilities",Protocol.object("notifications",allows(component,"notifications"),"dictation",allows(component,"dictation"),"previews",allows(component,"previews"),"maxWidth",Protocol.MAX_WIDTH,"maxHeight",Protocol.MAX_HEIGHT,"maxText",8000,"maxNotificationText",4096,"glanceboard",1,"glanceboardRegistry",1,"notificationReplies",true,"searchDictation",true,"extensions",ExtensionContract.VERSION,"windowMenus",true,"messaging",allows(component,"messaging")).toString());
 }
 private void disconnect(String component,boolean retry) {
  Connection c=connections.remove(component); if(c==null) return;
  if(retry)showReconnecting(c);
  if(retry)recoveryContexts.put(component,new RecoveryContext(c));else recoveryContexts.remove(component);
  c.ready=false;for(PendingFrame frame:new ArrayList<>(c.pendingFrames.values()))complete(c,frame.surfaceId,frame.clientFrameId,frame.contentVersion,FrameOutcome.Status.SESSION_LOST,frame.traceId,"Application Binder session lost",frame.metadataDropped);c.generation++;for(Surface surface:c.surfaces.values()){scheduler.cancel(c.component+":"+surface.id);surface.close();}c.renderExecutor.shutdownNow();for(HostResource resource:c.resources.values())releaseResourceAtlas(resource);c.resources.clear();
  try { if(c.remote!=null)c.remote.close(new DisconnectInfo(retry?DisconnectInfo.Reason.BINDER_DIED:DisconnectInfo.Reason.HOST_STOPPED,retry,retry?"Host reconnecting":"Host closed session")); } catch(Exception ignored) {}
  try { context.unbindService(c); } catch(Exception ignored) {}
  emit(component,retry?"recovering":"disconnected",Protocol.object("category",retry?"binder":"host-policy")); publishGlanceRegistry(); extensionsChanged();
  if(retry && approved(c.service)) {int attempt=retryAttempts.merge(component,1,Integer::sum)-1;long delay=RETRY_DELAYS_MS[Math.min(attempt,RETRY_DELAYS_MS.length-1)];main.postDelayed(()->{ if(!connections.containsKey(component)&&approved(c.service)) bind(c.service); },delay);}
  else retryAttempts.remove(component);
 }
 private void showReconnecting(Connection c){
  if(!c.open||c.width<1||c.height<1)return;try{Bitmap bitmap=Bitmap.createBitmap(c.width,c.height,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);canvas.drawColor(Color.BLACK);Paint border=new Paint();border.setStyle(Paint.Style.STROKE);border.setStrokeWidth(2);border.setColor(Color.rgb(150,150,150));canvas.drawRoundRect(12,12,c.width-12,c.height-12,8,8,border);Ui.text(canvas,"Reconnecting…",24,Math.max(42,c.height/2),18,Color.WHITE);int[] argb=new int[c.width*c.height];bitmap.getPixels(argb,0,c.width,0,0,c.width,c.height);bitmap.recycle();byte[] gray=new byte[argb.length];for(int i=0;i<argb.length;i++){int value=argb[i];gray[i]=(byte)((((value>>16)&255)*54+((value>>8)&255)*183+(value&255)*19)>>8);}String id="window:apk:"+c.component;FaceclawBleCommunicator display=FaceclawBleCommunicator.getActive();if(display!=null)display.submitExternalSurfaceFrame(ByteBuffer.wrap(gray),id,c.width,c.height,"reconnecting:"+c.generation,null,null);else{FaceclawPreviewCompositor preview=FaceclawPreviewCompositor.getActive();if(preview!=null)preview.submitSurfaceFrame(ByteBuffer.wrap(gray),id,0,0,c.width,c.height,"reconnecting:"+c.generation,0,0,null);}}catch(Exception ignored){}
 }
 private static final class HostResource {final int id,width,height,atlasId,encoding;final String type,sha256,atlasKey;final byte[] pixels;HostResource(int id,String type,int width,int height,String sha256,byte[] pixels,int atlasId,int encoding,String atlasKey){this.id=id;this.type=type;this.width=width;this.height=height;this.sha256=sha256;this.pixels=pixels;this.atlasId=atlasId;this.encoding=encoding;this.atlasKey=atlasKey;}}
 private static final class RecoverySurface {final String id;final int width,height;final long generation;final boolean visible,screenOn;RecoverySurface(Surface value){id=value.id;width=value.width;height=value.height;generation=value.generation+1;visible=value.visible;screenOn=value.screenOn;}}
 private static final class RecoveryContext {final int width,height;final long generation;final boolean open,visible,screenOn;final ArrayList<RecoverySurface> surfaces=new ArrayList<>();RecoveryContext(Connection value){width=value.width;height=value.height;generation=value.generation+1;open=value.open;visible=value.visible;screenOn=value.screenOn;for(Surface surface:value.surfaces.values())surfaces.add(new RecoverySurface(surface));}}
 private Surface surface(Connection c,String wireId){if("window".equals(wireId))return c.windowSurface();if(wireId!=null&&wireId.startsWith("extension:"))return c.surfaces.get(wireId.substring(10));return null;}
 private void registerSurface(Connection c,SurfaceRegistration registration){
  List<SharedMemory> memories=registration==null||registration.buffers==null?Collections.emptyList():registration.buffers;ByteBuffer[] mappings=new ByteBuffer[memories.size()];boolean accepted=false;
  try{if(connections.get(c.component)!=c||!c.ready||registration==null)return;String id=registration.surfaceId;long generation=registration.generation;int width=registration.width,height=registration.height,size=Protocol.frameSize(width,height)+Protocol.FRAME_HEADER_BYTES;Surface surface=surface(c,id);if(surface==null||surface.generation!=generation||surface.width!=width||surface.height!=height)return;
   if(memories.size()!=Protocol.BUFFER_SLOTS)return;for(int i=0;i<memories.size();i++){SharedMemory memory=memories.get(i);if(memory==null||memory.getSize()!=size)throw new IllegalArgumentException("Invalid pool slot");mappings[i]=memory.mapReadOnly();}
   synchronized(surface){if(connections.get(c.component)!=c||!c.ready||surface.generation!=generation||surface.width!=width||surface.height!=height)return;surface.closePool();surface.memories=memories.toArray(new SharedMemory[0]);surface.mappings=mappings;accepted=true;}
   main.post(()->scheduleCredit(c,surface,"window".equals(id)?DisplayScheduler.Priority.FOCUSED_ANIMATION:DisplayScheduler.Priority.VISIBLE_EXTENSION));
  }catch(Exception error){invalidFrame(c);}
  finally{if(!accepted){for(ByteBuffer mapping:mappings)if(mapping!=null)try{SharedMemory.unmap(mapping);}catch(Exception ignored){}for(SharedMemory memory:memories)if(memory!=null)try{memory.close();}catch(Exception ignored){}}}
 }
 private void closeRegistration(SurfaceRegistration registration){if(registration==null||registration.buffers==null)return;for(SharedMemory memory:registration.buffers)if(memory!=null)try{memory.close();}catch(Exception ignored){}}
 private void unregisterSurface(Connection c,String id,long generation){Surface surface=surface(c,id);if(surface!=null)synchronized(surface){if(surface.generation==generation)surface.closePool();}}
 private void requestCredit(Connection c,String id,long generation,int reason){Surface surface=surface(c,id);if(surface==null||surface.generation!=generation)return;DisplayScheduler.Priority priority=reason==InvalidateReason.INPUT.ordinal()?DisplayScheduler.Priority.DIRECT_INPUT:(id.startsWith("extension:")?DisplayScheduler.Priority.VISIBLE_EXTENSION:DisplayScheduler.Priority.FOCUSED_ANIMATION);scheduleCredit(c,surface,priority);}
 private void scheduleCredit(Connection c,Surface surface,DisplayScheduler.Priority priority){scheduleCredit(c,surface,priority,"");}
 private void scheduleCredit(Connection c,Surface surface,DisplayScheduler.Priority priority,String causeTrace){
  if(c==null||surface==null||!c.ready||c.remote==null||!surface.visible||!surface.screenOn)return;
  final long scheduleEpoch;long pacing;
  synchronized(surface){
   // A queued grant may be promoted by direct input. Once the credit has
   // crossed Binder it cannot be rewritten or duplicated.
   if(surface.creditOutstanding&&surface.creditGranted)return;
   if(!surface.creditOutstanding){surface.creditOutstanding=true;surface.creditScheduleEpoch++;}
   scheduleEpoch=surface.creditScheduleEpoch;
   FaceclawBleCommunicator display=FaceclawBleCommunicator.getActive();long period=display==null?0:display.renderCreditDelayMs();
   Object requested=c.service.metaData==null?null:c.service.metaData.get("com.faceclaw.ANIMATION_FRAME_INTERVAL_MS");
   period=RenderCadence.requestedPeriod(period,requested instanceof Integer?(Integer)requested:0);
   pacing=priority==DisplayScheduler.Priority.DIRECT_INPUT?0:RenderCadence.remainingDelay(SystemClock.elapsedRealtime(),surface.lastCreditAtMs,period);
  }
  final String trace=causeTrace!=null&&causeTrace.matches("[A-Za-z0-9_.:-]{1,128}")?causeTrace:UUID.randomUUID().toString();
  scheduler.offer(c.component+":"+surface.id,priority,pacing,()->{
   FaceclawBleCommunicator currentDisplay=FaceclawBleCommunicator.getActive();
   if(currentDisplay!=null&&!currentDisplay.isDisplayAvailable()&&FaceclawPreviewCompositor.getActive()==null){
    final long retryEpoch;synchronized(surface){if(surface.creditScheduleEpoch!=scheduleEpoch)return;surface.creditOutstanding=false;surface.creditGranted=false;retryEpoch=++surface.creditScheduleEpoch;}
    main.postDelayed(()->{synchronized(surface){if(surface.creditScheduleEpoch!=retryEpoch)return;}scheduleCredit(c,surface,priority,trace);},100);return;
   }
   long creditId,generation;
   synchronized(surface){
    if(surface.creditScheduleEpoch!=scheduleEpoch||connections.get(c.component)!=c||!c.ready||!surface.visible||!surface.screenOn){if(surface.creditScheduleEpoch==scheduleEpoch)surface.clearCreditLocked();return;}
    surface.creditId++;surface.lastCreditAtMs=SystemClock.elapsedRealtime();surface.creditGranted=true;creditId=surface.creditId;generation=surface.generation;
   }
   try{c.remote.grantRenderCredit(new RenderCredit(surface.id,generation,creditId,SystemClock.elapsedRealtimeNanos()+16_000_000L,Protocol.MAX_DAMAGE_RECTS,trace));}catch(Exception error){main.post(()->disconnect(c.component,true));}
  });
 }
 private void receiveFrame(Connection c,FrameSubmission submission){
  if(submission==null)return;String id=submission.surfaceId;long generation=submission.generation,sequence=submission.sequence,clientFrameId=submission.clientFrameId,contentVersion=submission.contentVersion;int slotId=submission.slotId;String trace=submission.traceId;Surface surface=surface(c,id);
  if(surface==null){release(c,id,generation,slotId,sequence);outcome(c,id,clientFrameId,contentVersion,FrameOutcome.Status.STALE_GENERATION,trace,"Unknown surface",false);return;}
  try{
   synchronized(surface){
    if(surface.generation!=generation){release(c,surface.id,generation,slotId,sequence);outcome(c,id,clientFrameId,contentVersion,FrameOutcome.Status.STALE_GENERATION,trace,"Stale generation",false);return;}
    if(!surface.visible||!surface.screenOn){release(c,surface.id,generation,slotId,sequence);surface.clearCreditLocked();outcome(c,id,clientFrameId,contentVersion,FrameOutcome.Status.HIDDEN,trace,"Surface hidden",false);return;}
    if(!surface.creditOutstanding||!surface.creditGranted||submission.creditId!=surface.creditId){release(c,surface.id,generation,slotId,sequence);outcome(c,id,clientFrameId,contentVersion,FrameOutcome.Status.THROTTLED,trace,"No matching render credit",false);invalidFrame(c);return;}
    surface.clearCreditLocked();if(slotId<0||slotId>=surface.mappings.length||sequence<=surface.sequence){release(c,surface.id,generation,slotId,sequence);outcome(c,id,clientFrameId,contentVersion,FrameOutcome.Status.STALE_GENERATION,trace,"Invalid slot or sequence",false);return;}
    FrameWire.Validation validation=FrameWire.validate(surface.mappings[slotId],generation,sequence,surface.width*surface.height);if(validation!=FrameWire.Validation.VALID){release(c,surface.id,generation,slotId,sequence);outcome(c,id,clientFrameId,contentVersion,validation==FrameWire.Validation.TORN?FrameOutcome.Status.TORN_WRITE:FrameOutcome.Status.STALE_GENERATION,trace,"Frame header validation failed",false);invalidFrame(c);return;}
    int[] damage=validateDamage(submission.damage,surface.width,surface.height);TranslatedDraws translated=translateDraws(c,submission.draws,surface.width,surface.height);byte[] draws=translated.bytes;boolean metadataDropped=translated.dropped;if(clientFrameId<=surface.clientFrameId){release(c,surface.id,generation,slotId,sequence);outcome(c,id,clientFrameId,contentVersion,FrameOutcome.Status.THROTTLED,trace,"Client frame ID did not increase",metadataDropped);invalidFrame(c);return;}if(!accepted(c,id,clientFrameId,contentVersion,trace,metadataDropped)){release(c,surface.id,generation,slotId,sequence);outcome(c,id,clientFrameId,contentVersion,FrameOutcome.Status.THROTTLED,trace,"Too many frames await a display outcome",metadataDropped);return;}surface.clientFrameId=clientFrameId;surface.sequence=sequence;surface.contentVersion=contentVersion;surface.draws=draws;
    deliverFrame(c,surface,FrameWire.pixels(surface.mappings[slotId],surface.width*surface.height),damage,draws,translated.copies,clientFrameId,contentVersion,trace,metadataDropped);release(c,surface.id,generation,slotId,sequence);
    if(submission.requestNextFrame)main.post(()->scheduleCredit(c,surface,DisplayScheduler.Priority.FOCUSED_ANIMATION));
   }
  }catch(Exception error){release(c,id,generation,slotId,sequence);if(!complete(c,id,clientFrameId,contentVersion,FrameOutcome.Status.TORN_WRITE,trace,"Rejected frame",false))outcome(c,id,clientFrameId,contentVersion,FrameOutcome.Status.TORN_WRITE,trace,"Rejected frame",false);invalidFrame(c);}
 }
 private int[] validateDamage(int[] value,int width,int height){if(value==null||value.length==0)return new int[]{0,0,width,height};if(value.length%4!=0||value.length/4>Protocol.MAX_DAMAGE_RECTS)throw new IllegalArgumentException("Invalid damage");for(int i=0;i<value.length;i+=4)if(value[i]<0||value[i+1]<0||value[i+2]<=0||value[i+3]<=0||value[i]+value[i+2]>width||value[i+1]+value[i+3]>height)throw new IllegalArgumentException("Damage outside surface");return value;}
 private void release(Connection c,String surfaceId,long generation,int slot,long sequence){try{if(c.remote!=null)c.remote.onBufferReleased(surfaceId,generation,slot,sequence);}catch(Exception ignored){}}
 private void outcome(Connection c,String id,long clientFrameId,long contentVersion,FrameOutcome.Status status,String trace,String diagnostic,boolean metadataDropped){try{if(c.remote!=null)c.remote.onFrameOutcome(new FrameOutcome(id,clientFrameId,contentVersion,status,trace,diagnostic,metadataDropped));}catch(Exception ignored){}}
 private static final class PendingFrame{final String surfaceId,traceId;final long clientFrameId,contentVersion;final boolean metadataDropped;PendingFrame(String surfaceId,long clientFrameId,long contentVersion,String traceId,boolean metadataDropped){this.surfaceId=surfaceId;this.clientFrameId=clientFrameId;this.contentVersion=contentVersion;this.traceId=traceId;this.metadataDropped=metadataDropped;}}
 private boolean accepted(Connection c,String id,long clientFrameId,long contentVersion,String trace,boolean metadataDropped){
  if(c.pendingFrames.size()>=MAX_PENDING_FRAMES)return false;String key=id+":"+clientFrameId;PendingFrame frame=new PendingFrame(id,clientFrameId,contentVersion,trace,metadataDropped);if(c.pendingFrames.putIfAbsent(key,frame)!=null)return false;
  main.postDelayed(()->{if(c.pendingFrames.remove(key,frame))outcome(c,id,clientFrameId,contentVersion,FrameOutcome.Status.CANCELLED,trace,"Display outcome timed out",metadataDropped);},FRAME_OUTCOME_TIMEOUT_MS);return true;
 }
 private boolean complete(Connection c,String id,long clientFrameId,long contentVersion,FrameOutcome.Status status,String trace,String diagnostic,boolean metadataDropped){if(c.pendingFrames.remove(id+":"+clientFrameId)==null)return false;outcome(c,id,clientFrameId,contentVersion,status,trace,diagnostic,metadataDropped);return true;}
 private void invalidFrame(Connection c){long now=SystemClock.elapsedRealtime();synchronized(c){if(now-c.invalidWindowStart>10000){c.invalidWindowStart=now;c.invalidFrames=0;}if(++c.invalidFrames>32)main.post(()->disconnect(c.component,false));}}
 /**
  * Extension surfaces are consumed by the shell's ExtensionLayer.  Keep this
  * route separate from the window compositor: extension surface ids are
  * private logical surfaces and are not registered compositor ids.  The
  * frame is copied before the SDK buffer is released, then revalidated on the
  * main thread immediately before invoking the shell callback.
  */
 private void deliverExtensionFrame(Connection c,Surface surface,long generation,int width,int height,ByteBuffer pixels,
                                    long clientFrameId,long contentVersion,String trace,boolean metadataDropped,boolean terminalOutcome){
  final String feature=surface.id.startsWith("extension:")?surface.id.substring(10):"";
  if(feature.isEmpty())return;
  final byte[] snapshot=new byte[width*height];
  ByteBuffer copy=pixels.duplicate(); copy.position(0); copy.get(snapshot);
  final FaceclawExternalAppListener callback=listener;
  if(callback==null){if(terminalOutcome)complete(c,surface.id,clientFrameId,contentVersion,FrameOutcome.Status.HIDDEN,trace,"Extension callback unavailable",metadataDropped);return;}
  main.post(()->{
   synchronized(surface){
    if(connections.get(c.component)!=c||!c.ready||!approved(c.service)||c.surfaces.get(feature)!=surface||
       !extensions.controls(c.component,feature)||surface.generation!=generation||surface.width!=width||surface.height!=height||
       !surface.visible||!surface.screenOn||(terminalOutcome&&(!c.pendingFrames.containsKey(surface.id+":"+clientFrameId)||
       surface.clientFrameId!=clientFrameId||surface.contentVersion!=contentVersion))){
     if(terminalOutcome)complete(c,surface.id,clientFrameId,contentVersion,FrameOutcome.Status.HIDDEN,trace,"Extension surface no longer visible",metadataDropped);
     return;
    }
   }
   try{
    FaceclawExternalAppListener current=listener;
    if(current==null){if(terminalOutcome)complete(c,surface.id,clientFrameId,contentVersion,FrameOutcome.Status.HIDDEN,trace,"Extension callback unavailable",metadataDropped);return;}
    current.onExtensionFrame(c.component,feature,generation,width,height,ByteBuffer.wrap(snapshot));
    if(terminalOutcome)complete(c,surface.id,clientFrameId,contentVersion,FrameOutcome.Status.PREVIEW_COMMITTED,trace,"Extension frame delivered",metadataDropped);
   }catch(Exception error){
    if(terminalOutcome)complete(c,surface.id,clientFrameId,contentVersion,FrameOutcome.Status.CANCELLED,trace,"Extension callback failed",metadataDropped);
    disconnect(c.component,false);
   }
  });
 }
 private void deliverFrame(Connection c,Surface surface,ByteBuffer pixels,int[] damage,byte[] draws,int[] retainedCopies,long clientFrameId,long contentVersion,String trace,boolean metadataDropped){
  surface.rasterSinceScene=true;
  if(connections.get(c.component)!=c||!c.ready||!surface.visible||!surface.screenOn){complete(c,surface.id,clientFrameId,contentVersion,FrameOutcome.Status.HIDDEN,trace,"Surface no longer visible",metadataDropped);return;}
  if(surface.id.startsWith("extension:")){deliverExtensionFrame(c,surface,surface.generation,surface.width,surface.height,pixels,clientFrameId,contentVersion,trace,metadataDropped,true);return;}
  String compositorId="window".equals(surface.id)?"window:apk:"+c.component:"extension:"+c.component+":"+surface.id.substring(10);FaceclawBleCommunicator display=FaceclawBleCommunicator.getActive();
  if(display!=null){display.submitExternalSurfaceFrame(pixels,compositorId,surface.width,surface.height,damage,"apk:"+c.component+":"+contentVersion,draws==null?null:ByteBuffer.wrap(draws),retainedCopies,(status,detail)->complete(c,surface.id,clientFrameId,contentVersion,status,trace,detail,metadataDropped),trace);return;}
  FaceclawPreviewCompositor preview=FaceclawPreviewCompositor.getActive();if(preview!=null){try{preview.submitSurfaceFrame(pixels,compositorId,0,0,surface.width,surface.height,"apk:"+contentVersion,0,0,draws==null?null:ByteBuffer.wrap(draws));complete(c,surface.id,clientFrameId,contentVersion,FrameOutcome.Status.PREVIEW_COMMITTED,trace,"Preview compositor committed",metadataDropped);}catch(Exception error){complete(c,surface.id,clientFrameId,contentVersion,FrameOutcome.Status.CANCELLED,trace,"Preview surface unavailable",metadataDropped);}return;}
  complete(c,surface.id,clientFrameId,contentVersion,FrameOutcome.Status.HIDDEN,trace,"No display transport",metadataDropped);
 }
 private static final class TranslatedDraws{final byte[] bytes;final int[] copies;final boolean dropped;TranslatedDraws(byte[] bytes,int[] copies,boolean dropped){this.bytes=bytes;this.copies=copies;this.dropped=dropped;}}
 private TranslatedDraws translateDraws(Connection c,byte[] wire,int surfaceWidth,int surfaceHeight){
  if(wire==null||wire.length==0)return new TranslatedDraws(null,null,false);
  if(wire.length>Protocol.MAX_COMMAND_BYTES||wire.length%DrawBatch.RECORD_BYTES!=0)return new TranslatedDraws(null,null,true);
  int records=wire.length/DrawBatch.RECORD_BYTES,copyCount=0;int[] copies=new int[Math.min(records,Protocol.MAX_RETAINED_COPIES)*6];
  ByteBuffer in=ByteBuffer.wrap(wire).order(java.nio.ByteOrder.LITTLE_ENDIAN),out=ByteBuffer.allocate(records*12).order(java.nio.ByteOrder.LITTLE_ENDIAN);boolean dropped=false;
  while(in.remaining()>=DrawBatch.RECORD_BYTES){
   int kind=in.get()&255;
   if(kind==2){int sx=in.getShort(),sy=in.getShort(),width=in.getShort()&0xffff,height=in.getShort()&0xffff,dx=in.getShort(),dy=in.getShort(),reserved=in.get()&255;if(reserved!=0||width==0||height==0||sx<0||sy<0||dx<0||dy<0||sx>surfaceWidth-width||dx>surfaceWidth-width||sy>surfaceHeight-height||dy>surfaceHeight-height||copyCount>=Protocol.MAX_RETAINED_COPIES){dropped=true;continue;}int at=copyCount++*6;copies[at]=sx;copies[at+1]=sy;copies[at+2]=width;copies[at+3]=height;copies[at+4]=dx;copies[at+5]=dy;continue;}
   int id=in.getInt(),x=in.getShort(),y=in.getShort(),brightness=in.get()&255,reserved=in.getInt();HostResource resource=c.resources.get(id);if(resource==null||resource.atlasId<0||kind!=("GLYPH".equals(resource.type)?1:0)||reserved!=0){dropped=true;continue;}if("GLYPH".equals(resource.type))out.put((byte)0).putShort((short)resource.atlasId).putInt(resource.encoding).putShort((short)x).putShort((short)y).put((byte)brightness);else if(brightness==255)out.put((byte)1).putInt(resource.atlasId).putShort((short)x).putShort((short)y);else dropped=true;
  }
  return new TranslatedDraws(out.position()==0?null:Arrays.copyOf(out.array(),out.position()),copyCount==0?null:Arrays.copyOf(copies,copyCount*6),dropped);
 }
 private void receiveResource(Connection c,ResourceRegistration resource){try{if(connections.get(c.component)!=c||!c.ready||resource==null)return;int id=resource.id,width=resource.width,height=resource.height;String wireType=resource.type,type=wireType,hash=resource.sha256;byte[] pixels=resource.pixels;int encoding=32;String fontKey="";if(wireType!=null&&wireType.startsWith("GLYPH/")){String[] parts=wireType.split("/",3);if(parts.length!=3||!parts[1].matches("[0-9]{2,3}")||!parts[2].matches("[A-Za-z0-9_.:-]{1,64}"))return;encoding=Integer.parseInt(parts[1]);if(encoding<32||encoding>127)return;type="GLYPH";fontKey=parts[2];}if(id<=0||(id>Protocol.MAX_RESOURCES&&!c.resourceReleaseEver)||!(type.equals("IMAGE")||type.equals("GLYPH"))||pixels==null||pixels.length!=Protocol.frameSize(width,height)||width>255||height>255||!hash.equals(resourceHash(type,width,height,pixels)))return;HostResource existing=c.resources.get(id);if(existing!=null){if(existing.type.equals(type)&&existing.sha256.equals(hash))return;else return;}if(id<=c.highestResourceId)return;int total=c.resources.values().stream().mapToInt(value->value.pixels.length).sum();if(c.resources.size()>=Protocol.MAX_RESOURCES||total+pixels.length>Protocol.MAX_RESOURCE_BYTES)return;String atlasKey=c.component+":"+(fontKey.isEmpty()?hash:fontKey);AndroidByteReader reader=new AndroidByteReader(ByteBuffer.wrap(pixels));int atlasId=type.equals("IMAGE")?ImageAtlas.ensure(atlasKey,width,height,reader):GlyphAtlas.ensureGray(atlasKey,encoding,width,height,reader);c.highestResourceId=id;c.resources.put(id,new HostResource(id,type,width,height,hash,pixels.clone(),atlasId,encoding,atlasKey));}catch(Exception ignored){}}
 private void releaseResourceAtlas(HostResource resource){if(resource==null||resource.atlasId<=0)return;if("IMAGE".equals(resource.type))ImageAtlas.forget(resource.atlasKey,resource.atlasId);else if("GLYPH".equals(resource.type))GlyphAtlas.forgetGray(resource.atlasKey,resource.atlasId,resource.encoding);}
 private void sweepResources(Connection c){
  Set<Integer> retained=new HashSet<>();for(Surface surface:c.surfaces.values())synchronized(surface){for(Bundle node:surface.nodes.values())if(node.containsKey("resourceId"))retained.add(node.getInt("resourceId"));}
  for(int id:new ArrayList<>(c.releasedResources))if(!retained.contains(id)){c.releasedResources.remove(id);HostResource removed=c.resources.remove(id);releaseResourceAtlas(removed);try{c.sendControl("resource-result",Protocol.object("resourceId",id,"status","released","residentBytes",c.resources.values().stream().mapToInt(value->value.pixels.length).sum(),"residentCount",c.resources.size()));}catch(RemoteException ignored){}}
 }
 private String resourceHash(String type,int width,int height,byte[] pixels)throws Exception{java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");digest.update((byte)("GLYPH".equals(type)?1:0));digest.update((byte)(width>>8));digest.update((byte)width);digest.update((byte)(height>>8));digest.update((byte)height);byte[] value=digest.digest(pixels);StringBuilder out=new StringBuilder();for(byte item:value)out.append(String.format(Locale.US,"%02x",item));return out.toString();}
 private void receiveScene(Connection c,SceneSubmission transaction){
  if(transaction==null)return;Surface surface=surface(c,transaction.surfaceId);
  boolean consumedCredit=false;byte[] deliveredPixels=null;int[] deliveredDamage=null;String deliveredId=null;long deliveredGeneration=0,deliveredVersion=0;int deliveredWidth=0,deliveredHeight=0;
  try{
   if(surface==null||surface.generation!=transaction.generation)throw new IllegalArgumentException("Stale scene surface");
   Parcel parcel=Parcel.obtain();int parcelBytes;try{transaction.writeToParcel(parcel,0);parcelBytes=parcel.dataSize();}finally{parcel.recycle();}if(parcelBytes>Protocol.MAX_COMMAND_BYTES)throw new IllegalArgumentException("Scene command batch too large");
   synchronized(surface){
    if(surface.generation!=transaction.generation)throw new IllegalArgumentException("Stale scene generation");
    long version=transaction.sceneVersion;if(version<=surface.sceneVersion)throw new IllegalArgumentException("Stale scene version");
    if(!surface.visible||!surface.screenOn||!surface.creditOutstanding||!surface.creditGranted)throw new IllegalArgumentException("Scene has no matching render credit");consumedCredit=true;
    deliveredId=surface.id;deliveredGeneration=surface.generation;deliveredVersion=version;deliveredWidth=surface.width;deliveredHeight=surface.height;
    // An empty scene transaction still restores retained nodes after raster
    // animation. The current displayed pixels are then not the scene pixels.
    boolean unchanged=!surface.rasterSinceScene&&!transaction.clear&&transaction.upserts.isEmpty()&&transaction.removes.length==0&&transaction.order.length==0;
    if(unchanged){surface.sceneVersion=version;surface.clearCreditLocked();}
    else{
     Map<Long,Bundle> next=transaction.clear?new HashMap<>():new HashMap<>(surface.nodes);ArrayList<Long> nextOrder=transaction.clear?new ArrayList<>():new ArrayList<>(surface.sceneOrder);
     for(long id:transaction.removes){next.remove(id);nextOrder.remove(id);}
     for(Bundle node:transaction.upserts){long id=node.getLong("id");validateSceneNode(c,node,id);next.put(id,new Bundle(node));if(!nextOrder.contains(id))nextOrder.add(id);}
     if(next.size()>Protocol.MAX_SCENE_NODES)throw new IllegalArgumentException("Scene node quota exceeded");validateSceneGraph(next);
     long[] explicit=transaction.order;if(explicit.length>0){LinkedHashSet<Long> ordered=new LinkedHashSet<>();for(long id:explicit)if(next.containsKey(id))ordered.add(id);for(long id:nextOrder)if(next.containsKey(id))ordered.add(id);nextOrder=new ArrayList<>(ordered);}
     byte[] nextPixels=renderScene(c,surface,next,nextOrder);int[] damage=surface.rasterSinceScene?new int[]{0,0,surface.width,surface.height}:changedBounds(surface.pixels,nextPixels,surface.width,surface.height);
     surface.nodes=next;surface.sceneOrder=nextOrder;surface.sceneVersion=version;surface.pixels=nextPixels;surface.draws=null;surface.clearCreditLocked();surface.rasterSinceScene=false;
     if(damage.length!=0){deliveredPixels=nextPixels;deliveredDamage=damage;}
    }
   }
   if(deliveredPixels!=null)deliverSceneFrame(c,surface,deliveredGeneration,deliveredWidth,deliveredHeight,ByteBuffer.wrap(deliveredPixels),deliveredDamage,"scene-"+deliveredVersion);
   c.sendControl("scene-result",Protocol.object("surfaceId",deliveredId,"generation",deliveredGeneration,"sceneVersion",deliveredVersion,"accepted",true));sweepResources(c);
  }catch(Exception rejected){if(consumedCredit&&surface!=null)synchronized(surface){surface.clearCreditLocked();}try{c.sendControl("scene-result",Protocol.object("surfaceId",transaction.surfaceId,"generation",transaction.generation,"sceneVersion",transaction.sceneVersion,"accepted",false));}catch(RemoteException disconnected){/* Binder recovery owns session loss. */}}
 }
 private void validateSceneNode(Connection c,Bundle node,long id){String kind=node.getString("kind","");if(id==0||node.getLong("parentId")==id||!Arrays.asList("group","rect","rounded-rect","line","glyph","image","raster-patch").contains(kind))throw new IllegalArgumentException("Invalid scene node");int brightness=node.getInt("brightness",255),opacity=node.getInt("opacity",255);if(brightness<0||brightness>255||opacity<0||opacity>255)throw new IllegalArgumentException("Invalid scene color");if(Arrays.asList("glyph","image").contains(kind)&&!c.resources.containsKey(node.getInt("resourceId")))throw new IllegalArgumentException("Unknown scene resource");if("raster-patch".equals(kind)){byte[] pixels=node.getByteArray("pixels");if(pixels==null){if(!c.resources.containsKey(node.getInt("resourceId")))throw new IllegalArgumentException("Unknown scene resource");}else if(pixels.length!=Protocol.frameSize(node.getInt("width"),node.getInt("height")))throw new IllegalArgumentException("Invalid inline raster patch");}}
 private void validateSceneGraph(Map<Long,Bundle> nodes){for(Bundle node:nodes.values()){Set<Long> seen=new HashSet<>();long parent=node.getLong("parentId");while(parent!=0){if(!seen.add(parent))throw new IllegalArgumentException("Scene group cycle");Bundle group=nodes.get(parent);if(group==null||!"group".equals(group.getString("kind")))throw new IllegalArgumentException("Invalid scene parent");parent=group.getLong("parentId");}}}
 private static final class ScenePlacement{int x,y,z,brightness=255,opacity=255,left,top,right,bottom;ScenePlacement(int width,int height){right=width;bottom=height;}}
 private ScenePlacement placeSceneNode(Bundle node,Map<Long,Bundle> nodes,int width,int height){ScenePlacement p=new ScenePlacement(width,height);ArrayList<Bundle> parents=new ArrayList<>();long parent=node.getLong("parentId");while(parent!=0){Bundle group=nodes.get(parent);parents.add(group);parent=group.getLong("parentId");}Collections.reverse(parents);for(Bundle group:parents){p.x+=group.getInt("x");p.y+=group.getInt("y");p.z+=group.getInt("z");p.brightness=p.brightness*group.getInt("brightness",255)/255;p.opacity=p.opacity*group.getInt("opacity",255)/255;int clipWidth=group.getInt("clipWidth"),clipHeight=group.getInt("clipHeight");if(clipWidth>0&&clipHeight>0){p.left=Math.max(p.left,p.x+group.getInt("clipX"));p.top=Math.max(p.top,p.y+group.getInt("clipY"));p.right=Math.min(p.right,p.x+group.getInt("clipX")+clipWidth);p.bottom=Math.min(p.bottom,p.y+group.getInt("clipY")+clipHeight);}}p.x+=node.getInt("x");p.y+=node.getInt("y");p.z+=node.getInt("z");p.opacity=p.opacity*node.getInt("opacity",255)/255;return p;}
 private byte[] renderScene(Connection c,Surface surface,Map<Long,Bundle> sceneNodes,List<Long> sceneOrder){byte[] pixels=new byte[surface.width*surface.height];Map<Long,Integer> rank=new HashMap<>();for(int i=0;i<sceneOrder.size();i++)rank.put(sceneOrder.get(i),i);List<Bundle> nodes=new ArrayList<>(sceneNodes.values());nodes.removeIf(node->"group".equals(node.getString("kind")));nodes.sort((a,b)->{int za=placeSceneNode(a,sceneNodes,surface.width,surface.height).z,zb=placeSceneNode(b,sceneNodes,surface.width,surface.height).z;if(za!=zb)return Integer.compare(za,zb);return Integer.compare(rank.getOrDefault(a.getLong("id"),Integer.MAX_VALUE),rank.getOrDefault(b.getLong("id"),Integer.MAX_VALUE));});for(Bundle node:nodes){String kind=node.getString("kind","");ScenePlacement p=placeSceneNode(node,sceneNodes,surface.width,surface.height);int value=node.getInt("brightness",255)*p.brightness/255*p.opacity/255;if(kind.equals("rect"))fillRect(pixels,surface.width,surface.height,p.x,p.y,node.getInt("width"),node.getInt("height"),(byte)value,p);else if(kind.equals("rounded-rect"))fillRoundedRect(pixels,surface.width,surface.height,p.x,p.y,node.getInt("width"),node.getInt("height"),node.getInt("radius"),(byte)value,p);else if(kind.equals("line"))drawLine(pixels,surface.width,surface.height,p.x,p.y,p.x+node.getInt("x2")-node.getInt("x"),p.y+node.getInt("y2")-node.getInt("y"),node.getInt("width"),(byte)value,p);else{HostResource resource=c.resources.get(node.getInt("resourceId"));if(resource!=null)blit(pixels,surface.width,surface.height,resource.pixels,resource.width,resource.height,p.x,p.y,value,p);else{byte[] inline=node.getByteArray("pixels");if(inline!=null)blit(pixels,surface.width,surface.height,inline,node.getInt("width"),node.getInt("height"),p.x,p.y,value,p);}}}return pixels;}
 private void fillRect(byte[] pixels,int surfaceWidth,int surfaceHeight,int x,int y,int width,int height,byte value,ScenePlacement clip){int left=Math.max(Math.max(0,x),clip.left),top=Math.max(Math.max(0,y),clip.top),right=Math.min(Math.min(surfaceWidth,x+width),clip.right),bottom=Math.min(Math.min(surfaceHeight,y+height),clip.bottom);for(int row=top;row<bottom;row++)Arrays.fill(pixels,row*surfaceWidth+left,row*surfaceWidth+right,value);}
 private void fillRoundedRect(byte[] pixels,int surfaceWidth,int surfaceHeight,int x,int y,int width,int height,int radius,byte value,ScenePlacement clip){int r=Math.max(0,Math.min(radius,Math.min(width,height)/2));if(r==0){fillRect(pixels,surfaceWidth,surfaceHeight,x,y,width,height,value,clip);return;}for(int row=0;row<height;row++){int cy=row<r?r-row-1:row>=height-r?row-(height-r):0;int inset=cy==0?0:(int)Math.ceil(r-Math.sqrt(Math.max(0,r*r-cy*cy)));fillRect(pixels,surfaceWidth,surfaceHeight,x+inset,y+row,width-inset*2,1,value,clip);}}
 private void drawLine(byte[] pixels,int surfaceWidth,int surfaceHeight,int x0,int y0,int x1,int y1,int width,byte value,ScenePlacement clip){int dx=Math.abs(x1-x0),sx=x0<x1?1:-1,dy=-Math.abs(y1-y0),sy=y0<y1?1:-1,error=dx+dy;while(true){fillRect(pixels,surfaceWidth,surfaceHeight,x0-width/2,y0-width/2,width,width,value,clip);if(x0==x1&&y0==y1)break;int e=2*error;if(e>=dy){error+=dy;x0+=sx;}if(e<=dx){error+=dx;y0+=sy;}}}
 private void blit(byte[] pixels,int surfaceWidth,int surfaceHeight,byte[] sourcePixels,int sourceWidth,int sourceHeight,int x,int y,int value,ScenePlacement clip){for(int row=0;row<sourceHeight;row++)for(int col=0;col<sourceWidth;col++){int dx=x+col,dy=y+row;if(dx<Math.max(0,clip.left)||dy<Math.max(0,clip.top)||dx>=Math.min(surfaceWidth,clip.right)||dy>=Math.min(surfaceHeight,clip.bottom))continue;int source=sourcePixels[row*sourceWidth+col]&255;if(source!=0)pixels[dy*surfaceWidth+dx]=(byte)(source*value/255);}}
 private int[] changedBounds(byte[] before,byte[] after,int width,int height){if(before==null||before.length!=after.length)return new int[]{0,0,width,height};int minX=width,minY=height,maxX=-1,maxY=-1;for(int i=0;i<after.length;i++)if(before[i]!=after[i]){int x=i%width,y=i/width;minX=Math.min(minX,x);minY=Math.min(minY,y);maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);}return maxX<minX?new int[0]:new int[]{minX,minY,maxX-minX+1,maxY-minY+1};}
 private void deliverSceneFrame(Connection c,Surface surface,long generation,int width,int height,ByteBuffer pixels,int[] damage,String fingerprint){synchronized(surface){if(surface.generation!=generation||surface.width!=width||surface.height!=height||!surface.visible||!surface.screenOn)return;}if(connections.get(c.component)!=c||!c.ready)return;if(surface.id.startsWith("extension:")){deliverExtensionFrame(c,surface,generation,width,height,pixels,0,0,"",false,false);return;}String compositorId="window".equals(surface.id)?"window:apk:"+c.component:"extension:"+c.component+":"+surface.id.substring(10);FaceclawBleCommunicator display=FaceclawBleCommunicator.getActive();if(display!=null)display.submitExternalSurfaceFrame(pixels,compositorId,width,height,damage,fingerprint,null,null);else{FaceclawPreviewCompositor preview=FaceclawPreviewCompositor.getActive();if(preview!=null)preview.submitSurfaceFrame(pixels,compositorId,0,0,width,height,fingerprint,0,0,null);}}
 private final class Connection implements ServiceConnection {
  final ServiceInfo service; final String component,session=UUID.randomUUID().toString();
  java.lang.ref.WeakReference<Activity> selectionActivity; long selectionUntil;
  final Map<String,Surface> surfaces=new ConcurrentHashMap<>(); final Map<String,String> extensionRequests=new HashMap<>(),capabilityRequests=new HashMap<>(); final Set<String> actionIds=new HashSet<>();
  final Set<Integer> releasedResources=ConcurrentHashMap.newKeySet();int highestResourceId;volatile boolean resourceReleaseEver;long lastPrefetchAt;
  final Map<Integer,HostResource> resources=new ConcurrentHashMap<>();final Map<String,PendingFrame> pendingFrames=new ConcurrentHashMap<>();final ThreadPoolExecutor renderExecutor;final java.util.concurrent.atomic.AtomicLong renderQueueBytes=new java.util.concurrent.atomic.AtomicLong();
  final Map<String,Long> invocations=new HashMap<>();
  final Set<String> negotiated=ConcurrentHashMap.newKeySet(); long catalogEpoch=1,declarationEpoch; ControlLedger controls=new ControlLedger(1);
  JSONArray capabilities=new JSONArray(); long capabilityGeneration,stateRevision; JSONObject hostState=Protocol.object("available",false);
  IFaceclawAppEndpoint endpoint;volatile IFaceclawAppSession remote; PendingIntent consent; volatile boolean ready;boolean open,visible,screenOn=true; int width,height; long generation,lastControlRefill=SystemClock.elapsedRealtime(),lastOpenRequest; double controlTokens=120; int invalidFrames;long invalidWindowStart; String lastExtensionSnapshot="";byte[] restorationToken;
  final IFaceclawHostSession.Stub hostSession=new IFaceclawHostSession.Stub(){
   private boolean authorized(){return Binder.getCallingUid()==service.applicationInfo.uid;}
   @Override public void onReady(SessionHello hello,IFaceclawAppSession app){if(!authorized())return;main.post(()->ready(hello,app));}
   @Override public void onConsentRequired(ConsentRequest value){int uid=Binder.getCallingUid();if(!authorized())return;main.post(()->consent(value,uid));}
   @Override public void registerSurface(SurfaceRegistration value){if(authorized()&&!enqueueRender(4096,()->FaceclawExternalApps.this.registerSurface(Connection.this,value))){closeRegistration(value);renderQueueOverflow();}}
   @Override public void unregisterSurface(String id,long generation){if(authorized()&&!enqueueRender(128,()->FaceclawExternalApps.this.unregisterSurface(Connection.this,id,generation)))renderQueueOverflow();}
   @Override public void requestRender(String id,long generation,int reason){if(authorized())main.post(()->requestCredit(Connection.this,id,generation,reason));}
   @Override public void submitFrame(FrameSubmission value){long bytes=value==null?128:256L+(value.draws==null?0:value.draws.length)+(value.damage==null?0:4L*value.damage.length);if(authorized()&&!enqueueRender(bytes,()->receiveFrame(Connection.this,value))&&value!=null)main.post(()->{release(Connection.this,value.surfaceId,value.generation,value.slotId,value.sequence);outcome(Connection.this,value.surfaceId,value.clientFrameId,value.contentVersion,FrameOutcome.Status.THROTTLED,value.traceId,"Host render queue full",false);});}
   @Override public void registerResource(ResourceRegistration value){long bytes=value==null||value.pixels==null?256:256L+value.pixels.length;if(authorized()&&!enqueueRender(bytes,()->receiveResource(Connection.this,value)))renderQueueOverflow();}
   @Override public void commitScene(SceneSubmission value){if(authorized()&&!enqueueRender(Protocol.MAX_COMMAND_BYTES,()->receiveScene(Connection.this,value)))renderQueueOverflow();}
   @Override public void sendControl(ControlEvent value){if(authorized())main.post(()->receiveControl(value));}
   @Override public void close(DisconnectInfo reason){if(authorized())main.post(()->disconnect(component,false));}
  };
  Connection(ServiceInfo service) { this.service=service; component=key(service);byte[] saved=restorationTokens.get(component);restorationToken=saved==null?null:saved.clone();RecoveryContext recovery=recoveryContexts.get(component);if(recovery!=null){width=recovery.width;height=recovery.height;generation=recovery.generation;open=recovery.open;visible=recovery.visible;screenOn=recovery.screenOn;for(RecoverySurface state:recovery.surfaces){Surface surface=new Surface(state.id,state.width,state.height,state.generation);surface.visible=state.visible;surface.screenOn=state.screenOn;String surfaceKey=state.id.startsWith("extension:")?state.id.substring(10):state.id;surfaces.put(surfaceKey,surface);}}renderExecutor=new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(MAX_RENDER_QUEUE),r->{Thread t=new Thread(r,"FaceclawRender-"+service.packageName);t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy()); }
  boolean enqueueRender(long retainedBytes,Runnable task){long bytes=Math.max(1,retainedBytes),current;do{current=renderQueueBytes.get();if(bytes>MAX_RENDER_QUEUE_BYTES-current)return false;}while(!renderQueueBytes.compareAndSet(current,current+bytes));try{renderExecutor.execute(()->{try{task.run();}finally{renderQueueBytes.addAndGet(-bytes);}});return true;}catch(RejectedExecutionException rejected){renderQueueBytes.addAndGet(-bytes);return false;}}
  void renderQueueOverflow(){main.post(()->{if(connections.get(component)==this){android.util.Log.w("FaceclawApps","reconnect reason=render_queue_full");disconnect(component,true);}});}
  public void onServiceConnected(ComponentName name,IBinder binder) {
   if(connections.get(component)!=this || !approved(service)) return;
   endpoint=IFaceclawAppEndpoint.Stub.asInterface(binder);
   try { endpoint.connect(new SessionHello(Protocol.VERSION,"local",session),hostSession); }
   catch(Exception e) { disconnect(component,true); }
  }
  public void onServiceDisconnected(ComponentName name) { disconnect(component,true); }
  public void onBindingDied(ComponentName name) { disconnect(component,true); }
  public void onNullBinding(ComponentName name) { disconnect(component,false); }
  void ready(SessionHello hello,IFaceclawAppSession app){
   if(connections.get(component)!=this||!approved(service)||hello==null||hello.protocolMajor!=Protocol.VERSION||!session.equals(hello.sessionId)||app==null)return;
   remote=app;ready=true;consent=null;selectionActivity=null;recoveryContexts.remove(component);
   // A handshake followed by immediate failure must retain reconnect backoff.
   main.postDelayed(()->{if(connections.get(component)==this&&ready)retryAttempts.remove(component);},30000);
   try{app.asBinder().linkToDeath(()->main.post(()->disconnect(component,true)),0);}catch(Exception error){disconnect(component,true);return;}
   try{remote.applyHostSnapshot(snapshot());}catch(Exception error){disconnect(component,true);return;}
   publishCapabilities(component);send(component,"shared-style",sharedStyle.toString());emit(component,"connected",new JSONObject());publishGlanceRegistry();extensionsChanged();
  }
  void consent(ConsentRequest value,int uid){
   PendingIntent pi=value==null?null:value.consent;
   if(pi!=null&&pi.getCreatorUid()==uid&&service.packageName.equals(pi.getCreatorPackage())&&(Build.VERSION.SDK_INT<31||pi.isActivity())){consent=pi;emit(component,"consent",new JSONObject());launchRequestedConsent(this,pi);}
  }
  Set<String> supported(){return listener==null?Collections.emptySet():new HashSet<>(Arrays.asList("control.result","window.policy","capture.session","composer.session","invocation.lifecycle","resource.release","resource.prefetch",ExtensionContract.NOTIFICATION_PREVIEW_TIMING));}
  JSONObject catalog(){JSONArray features=new JSONArray();for(String id:supported())features.put(Protocol.object("id",id,"version",1,"limits",Protocol.object("maxPendingControls",32,"maxPolicyBytes",4096)));return Protocol.object("contractVersion",1,"epoch",catalogEpoch,"features",features);}
  void negotiate(JSONObject data)throws RemoteException{
   JSONObject result;
   try{result=IndependenceProtocol.negotiate(data,supported(),catalogEpoch,declarationEpoch);if(result.optString("state").equals("applied")){Set<String> next=new HashSet<>();JSONArray features=result.optJSONArray("features");for(int i=0;i<features.length();i++){JSONObject f=features.optJSONObject(i);if(f.optString("state").equals("accepted"))next.add(f.optString("id"));}negotiated.clear();negotiated.addAll(next);resourceReleaseEver|=next.contains("resource.release");declarationEpoch=data.optLong("epoch");}}
   catch(IllegalArgumentException invalid){result=Protocol.object("epoch",data.optLong("epoch"),"catalogEpoch",catalogEpoch,"state","rejected","reason",invalid.getMessage(),"features",new JSONArray());}
   sendControl("contract-result",result);
  }
  void control(JSONObject data,long now)throws RemoteException{
   ControlLedger.Admission admission=controls.admit(data,now,generation,negotiated);sendControl("control-result",admission.result);
   if(!admission.execute)return;
   JSONObject request=IndependenceProtocol.copy(data);try{request.put("session",session);}catch(JSONException impossible){return;}
   main.postDelayed(()->{if(connections.get(component)!=this)return;for(JSONObject result:controls.expire(SystemClock.elapsedRealtime()))try{sendControl("control-result",result);}catch(RemoteException error){disconnect(component,true);}},Math.max(1,data.optLong("expiresAtElapsedMs")-now));
   emit(component,"contract-control",request);
  }
  synchronized HostSnapshot snapshot(){Bundle b=new Bundle();b.putLong("stateRevision",++stateRevision);b.putInt("protocolMajor",Protocol.VERSION);b.putInt("maxWidth",Protocol.MAX_WIDTH);b.putInt("maxHeight",Protocol.MAX_HEIGHT);b.putInt("maxDamageRects",Protocol.MAX_DAMAGE_RECTS);b.putInt("bufferSlots",Protocol.BUFFER_SLOTS);b.putBoolean("screenOn",screenOn);b.putBoolean("windowOpen",open);b.putBoolean("windowVisible",visible);b.putLong("windowGeneration",generation);b.putInt("windowWidth",width);b.putInt("windowHeight",height);b.putString("grants",Protocol.object("notifications",allows(component,"notifications"),"dictation",allows(component,"dictation"),"previews",allows(component,"previews"),"messaging",allows(component,"messaging")).toString());b.putString("sharedStyle",sharedStyle.toString());b.putString("extensions",extensionsJson());b.putString("hostState",hostState.toString());FaceclawBleCommunicator display=FaceclawBleCommunicator.getActive();b.putString("capabilities",Protocol.object("appIndependence",catalog(),"glanceboard",1,"glanceboardRegistry",1,"notificationReplies",true,"searchDictation",true,"windowMenus",true,"gray8",true,"sharedMemory",true,"damage",true,"resources",true,"retainedScenes",true,"firmwareFingerprint",display==null?"":display.getFirmwareFingerprint()).toString());ArrayList<Bundle> openSurfaces=new ArrayList<>();for(Surface surface:surfaces.values()){Bundle item=new Bundle();item.putString("id",surface.id);item.putInt("width",surface.width);item.putInt("height",surface.height);item.putLong("generation",surface.generation);item.putBoolean("visible",surface.visible);item.putBoolean("screenOn",surface.screenOn);openSurfaces.add(item);}b.putParcelableArrayList("surfaces",openSurfaces);if(restorationToken!=null)b.putByteArray("restorationToken",restorationToken.clone());return new HostSnapshot(b);}
  Surface windowSurface(){synchronized(this){return surfaces.computeIfAbsent("window",ignored->new Surface("window",width,height,generation));}}
  synchronized void sendControl(String type,JSONObject data)throws RemoteException{if(remote==null)throw new RemoteException("App session unavailable");try{JSONObject copy=new JSONObject(data.toString());copy.put("stateRevision",++stateRevision);remote.sendControl(new ControlEvent(type,copy));}catch(org.json.JSONException error){throw new RemoteException("Invalid host state");}}
  void sendInput(String surfaceId,FaceclawInputEvent event)throws RemoteException{if(remote==null)throw new RemoteException("App session unavailable");remote.onInput(surfaceId,event);}
  void receiveControl(ControlEvent event) {
   try {
    if(connections.get(component)!=this||!approved(service)||!ready)return;
    long now=SystemClock.elapsedRealtime();controlTokens=Math.min(120,controlTokens+(now-lastControlRefill)*0.06);lastControlRefill=now;if(controlTokens<1)return;controlTokens-=1;
    if(event==null)return;String type=event.type; JSONObject data=event.data;
    if(type.equals("resource-release")){
     if(!negotiated.contains("resource.release"))return;IndependenceProtocol.keys(data,"resourceId");int id=(int)IndependenceProtocol.integer(data,"resourceId",1,Integer.MAX_VALUE);
     enqueueRender(128,()->{if(connections.get(component)!=this||!ready)return;if(resources.containsKey(id)){releasedResources.add(id);sweepResources(this);}try{sendControl("resource-result",Protocol.object("resourceId",id,"status",resources.containsKey(id)?"deferred":"released","residentBytes",resources.values().stream().mapToInt(value->value.pixels.length).sum(),"residentCount",resources.size()));}catch(RemoteException ignored){}});return;
    }
    if(type.equals("resource-prefetch")){
     String requestId=data.optString("requestId");
     try{
      if(!negotiated.contains("resource.prefetch"))throw new IllegalArgumentException("unsupported");
      IndependenceProtocol.bytes(data,8192);IndependenceProtocol.keys(data,"requestId","resourceIds","replace");IndependenceProtocol.token(data,"requestId");
      Object replaceValue=data.opt("replace");if(!(replaceValue instanceof Boolean))throw new IllegalArgumentException("malformed");
      JSONArray values=data.optJSONArray("resourceIds");if(values==null||values.length()<1||values.length()>128)throw new IllegalArgumentException("malformed");
      if(now-lastPrefetchAt<250)throw new IllegalArgumentException("rate_limited");lastPrefetchAt=now;
      LinkedHashSet<Integer> ids=new LinkedHashSet<>();
      for(int i=0;i<values.length();i++){Object value=values.opt(i);if(!(value instanceof Number))throw new IllegalArgumentException("malformed");double number=((Number)value).doubleValue();if(number<1||number>Integer.MAX_VALUE||number!=Math.rint(number))throw new IllegalArgumentException("malformed");ids.add((int)number);}
      boolean replace=(Boolean)replaceValue;
      if(!enqueueRender(512L+ids.size()*4L,()->{
       if(connections.get(component)!=this||!ready)return;
       try{
        if(!open||!visible||!screenOn)throw new IllegalArgumentException("not_visible");
        int[] kinds=new int[ids.size()],atlasIds=new int[ids.size()],encodings=new int[ids.size()];int at=0;
        for(int id:ids){HostResource resource=resources.get(id);if(resource==null||resource.atlasId<=0)throw new IllegalArgumentException("unknown_resource");kinds[at]="GLYPH".equals(resource.type)?0:1;atlasIds[at]=resource.atlasId;encodings[at]=resource.encoding;at++;}
        FaceclawBleCommunicator display=FaceclawBleCommunicator.getActive();if(display==null)throw new IllegalArgumentException("display_unavailable");
        FaceclawBleCommunicator.TexturePrefetchResult result=display.prefetchTextures(kinds,atlasIds,encodings,replace);
        sendControl("resource-prefetch-result",Protocol.object("requestId",requestId,"state",result.state,"requested",result.requested,"resident",result.resident,"uploadBytes",result.uploadBytes,"cacheBytes",result.cacheBytes));
       }catch(IllegalArgumentException rejected){try{sendControl("resource-prefetch-result",Protocol.object("requestId",requestId,"state","rejected","reason",rejected.getMessage()));}catch(RemoteException ignored){}}
       catch(RemoteException ignored){}
      }))throw new IllegalArgumentException("queue_full");
     }catch(IllegalArgumentException rejected){if(requestId.matches("[A-Za-z0-9_-]{1,128}"))sendControl("resource-prefetch-result",Protocol.object("requestId",requestId,"state","rejected","reason",rejected.getMessage()));}
     return;
    }
    if(type.equals("capture-start")){
     try{IndependenceProtocol.bytes(data,8192);IndependenceProtocol.keys(data,"captureId","purpose","label","providerGeneration","windowGeneration","expiresAtElapsedMs");IndependenceProtocol.token(data,"captureId");IndependenceProtocol.text(data,"label",100);long provider=IndependenceProtocol.integer(data,"providerGeneration",0,Long.MAX_VALUE);if(provider>0&&(!extensions.controls(component,"assistant")||extensions.generation("assistant")!=provider))throw new IllegalArgumentException("not_selected");if(!Arrays.asList("generic","message","search").contains(IndependenceProtocol.text(data,"purpose",16)))throw new IllegalArgumentException("malformed");long expiry=IndependenceProtocol.integer(data,"expiresAtElapsedMs",1,Long.MAX_VALUE);if(expiry<=now||expiry-now>300000)throw new IllegalArgumentException("expired");if(IndependenceProtocol.integer(data,"windowGeneration",1,Long.MAX_VALUE)!=generation)throw new IllegalArgumentException("stale_window");if(!negotiated.contains("capture.session")||!allows(component,"dictation")||!open||!visible||!screenOn)throw new IllegalArgumentException("not_granted");emit(component,type,data);}
     catch(IllegalArgumentException invalid){String id=data.optString("captureId");if(id.matches("[A-Za-z0-9_-]{1,128}"))sendControl("capture-status",Protocol.object("captureId",id,"status","rejected","reason",invalid.getMessage()));}return;
    }
    if(type.equals("capture-finish")||type.equals("capture-cancel")){if(negotiated.contains("capture.session")){IndependenceProtocol.keys(data,"captureId");IndependenceProtocol.token(data,"captureId");emit(component,type,data);}return;}
    if(type.equals("composer-start")){
     try{IndependenceProtocol.bytes(data,24576);IndependenceProtocol.keys(data,"composerId","purpose","target","label","initialText","maxText","windowGeneration","expiresAtElapsedMs");IndependenceProtocol.token(data,"composerId");String purpose=IndependenceProtocol.text(data,"purpose",16);if(!Arrays.asList("generic","message").contains(purpose))throw new IllegalArgumentException("malformed");IndependenceProtocol.text(data,"target",512);IndependenceProtocol.text(data,"label",100);String initial=IndependenceProtocol.text(data,"initialText",20000);long maxText=IndependenceProtocol.integer(data,"maxText",1,20000);if(initial.length()>maxText)throw new IllegalArgumentException("too_large");long expiry=IndependenceProtocol.integer(data,"expiresAtElapsedMs",1,Long.MAX_VALUE);if(expiry<=now||expiry-now>300000)throw new IllegalArgumentException("expired");if(IndependenceProtocol.integer(data,"windowGeneration",1,Long.MAX_VALUE)!=generation)throw new IllegalArgumentException("stale_window");if(!negotiated.contains("composer.session")||!allows(component,"dictation")||!open||!visible||!screenOn)throw new IllegalArgumentException("not_granted");emit(component,type,data);}
     catch(IllegalArgumentException invalid){String id=data.optString("composerId");if(id.matches("[A-Za-z0-9_-]{1,128}"))sendControl("composer-status",Protocol.object("composerId",id,"status","rejected","reason",invalid.getMessage()));}return;
    }
    if(type.equals("composer-cancel")){if(negotiated.contains("composer.session")){IndependenceProtocol.keys(data,"composerId");IndependenceProtocol.token(data,"composerId");emit(component,type,data);}return;}
    if(type.equals("invocation-result")){String id=data.optString("invocationId");String result=data.optString("state");if(invocations.containsKey(id)&&Arrays.asList("accepted","rejected","completed","cancelled","unknown").contains(result)){if(!result.equals("accepted"))invocations.remove(id);emit(component,type,Protocol.object("invocationId",id,"state",result));}return;}
    if(type.equals("glanceboard-register")) {
     JSONObject registry=com.faceclaw.sdk.GlanceboardContract.registry(data);
     String saved=Protocol.object("pin",prefs.getString(component+":pin",""),"registry",registry).toString();
     if(!saved.equals(prefs.getString(component+":glance-widgets",""))){prefs.edit().putString(component+":glance-widgets",saved).apply();publishGlanceRegistry();}
     return;
    }
    if(type.equals("glanceboard-widget-content")) {
     JSONObject clean=com.faceclaw.sdk.GlanceboardContract.content(data,glanceRegistry(service),System.currentTimeMillis());
     if(!allows(component,"previews"))clean=Protocol.object("version",2,"widgetId",clean.getString("widgetId"),"expiresAt",clean.getLong("expiresAt"),"redacted",true);
     emit(component,"glanceboard-widget-content",clean);return;
    }
    if(type.equals("glanceboard-content")) {
     JSONObject clean=com.faceclaw.sdk.GlanceboardContract.validate(data,System.currentTimeMillis());
     if(glanceRegistry(service)==null){
      JSONObject registry=Protocol.object("version",1,"widgets",new JSONArray().put(Protocol.object("id","default","label",service.loadLabel(context.getPackageManager()).toString(),"kind","list","rows",1,"refreshMs",30000)));
      registry=com.faceclaw.sdk.GlanceboardContract.registry(registry);
      prefs.edit().putString(component+":glance-widgets",Protocol.object("pin",prefs.getString(component+":pin",""),"registry",registry).toString()).apply();publishGlanceRegistry();
     }
     if(!allows(component,"previews")) {
      clean.put("title",service.loadLabel(context.getPackageManager()).toString());
      clean.put("entries",new JSONArray());clean.put("emptyText","Content previews disabled");
     }
     emit(component,"glanceboard-content",clean);return;
    }
    if(type.equals("publish-contract")){negotiate(data);return;}
    if(type.equals("control-request")){control(data,now);return;}
    if(type.equals("restoration-token")){byte[] value=event.opaquePayload;if(value!=null&&value.length<=4096){restorationToken=value.clone();restorationTokens.put(component,restorationToken.clone());}return;}
    if(type.equals("surface-reset")){Surface target=surface(this,data.optString("surfaceId"));if(target!=null&&target.generation==data.optLong("generation")){synchronized(target){if(target.pixels!=null)Arrays.fill(target.pixels,(byte)0);target.draws=null;target.nodes.clear();target.sceneOrder.clear();target.sceneVersion=0;}}return;}
    if(type.equals("host-switched")) { revokeApproval(component); return; }
    if(type.equals("disconnected")) { disconnect(component,false); return; }
    if(type.equals("publish-extensions")) {
     long before=extensions.generation(); if(extensions.publish(component,data.getJSONArray("declarations"))&&before!=extensions.generation()) extensionsChanged(); return;
    }
    if(type.equals("publish-tool-capabilities")) {
     JSONArray clean=CapabilityContract.declarations(data.getJSONArray("capabilities"));
     if(data.optInt("version")!=CapabilityContract.VERSION) return;
     capabilities=clean; capabilityGeneration++;
     for(String requestId:new ArrayList<>(capabilityRequests.keySet())) emit(component,"capability-result",Protocol.object("requestId",requestId,"result",Protocol.object("state","unknown","message","Provider catalog changed; operation outcome may be unknown")));
     capabilityRequests.clear();
     emit(component,"capabilities-changed",Protocol.object("version",CapabilityContract.VERSION,"generation",capabilityGeneration,"capabilities",clean)); return;
    }
    if(type.equals("extension-result")||type.equals("extension-progress")||type.equals("extension-action")) { receiveExtensionControl(this,type,data); return; }
    if(type.equals("capability-result")||type.equals("capability-progress")) {
     String requestId=data.optString("requestId"),capabilityId=capabilityRequests.get(requestId); if(capabilityId==null) return;
     JSONObject result=CapabilityContract.result(data.getJSONObject("result"),type.equals("capability-progress"));
     if(type.equals("capability-result")) capabilityRequests.remove(requestId);
     emit(component,type,Protocol.object("requestId",requestId,"capabilityId",capabilityId,"result",result)); return;
    }
    if(type.equals("messaging-result")) { if(allows(component,"messaging")) emit(component,type,data); return; }
    if(type.equals("window-menu-state")) { if(open&&data.opt("available") instanceof Boolean) emit(component,type,Protocol.object("available",data.getBoolean("available"))); return; }
    if(type.equals("window-protection")) { if(open&&data.opt("protected") instanceof Boolean) emit(component,type,Protocol.object("protected",data.getBoolean("protected"))); return; }
    if(type.equals("own-notifications")||type.equals("own-notification-action")) {
     if(isExtensionGranted(component,"notification-content")&&open&&visible&&screenOn) emit(component,type,data); return;
    }
    if(type.equals("request-open-window")) {
     Object target=data.opt("target"); if(!(target instanceof String)||((String)target).length()>512||now-lastOpenRequest<2000) return;
     lastOpenRequest=now; emit(component,type,Protocol.object("target",target)); return;
    }
    if(type.equals("sleep")) { if(open&&visible&&screenOn) emit(component,type,new JSONObject()); return; }
    if(type.equals("request-system-menu")) { if(open&&visible&&screenOn) emit(component,type,new JSONObject()); return; }
    if(type.equals("notification")||type.equals("remove-notification")||type.equals("notification-reply-result")) { if(!allows(component,"notifications")) return; }
    else if(type.equals("dictation")||type.equals("cancel-dictation")||type.equals("search-dictation")||type.equals("cancel-search-dictation")||type.equals("capture-dictation")||type.equals("finish-capture-dictation")||type.equals("cancel-capture-dictation")||type.equals("host-refinement")||type.equals("cancel-host-refinement")) { if(!allows(component,"dictation")||!(open&&visible&&screenOn||hasVisibleSurface(this,"ui.notifications"))) {
      if(type.equals("host-refinement")||type.equals("capture-dictation")) sendControl(type.equals("host-refinement")?"host-refinement-rejected":"capture-dictation-closed",Protocol.object("requestId",data.optString("requestId","").substring(0,Math.min(128,data.optString("requestId","").length())),"reason","Permission or visible window required"));
      if(type.equals("dictation")||type.equals("search-dictation")) sendControl(type.equals("search-dictation")?"search-dictation-rejected":"dictation-rejected",Protocol.object("requestId",data.optString("requestId","").substring(0,Math.min(128,data.optString("requestId","").length())),"reason","Dictation permission or visible window required"));
      return;
     } }
    else return;
    emit(component,type,data);
   } catch(Exception ignored) { disconnect(component,false); }
  }
 }
 /** Called only by the trusted shell after its focus/lock/protected-flow checks. */
 public boolean deliverInvocation(String component,String entryPoint,long providerGeneration){
  Connection c=connections.get(component);if(c==null||!c.ready||!approved(c.service)||!c.open||!c.visible||!c.screenOn||!c.negotiated.contains("invocation.lifecycle")||!extensions.controls(component,"assistant")||extensions.generation("assistant")!=providerGeneration||!Arrays.asList("wakeword","text-entry","app-button").contains(entryPoint))return false;
  long now=SystemClock.elapsedRealtime();c.invocations.entrySet().removeIf(e->e.getValue()<=now);if(c.invocations.size()>=32)return false;String id=UUID.randomUUID().toString();c.invocations.put(id,now+30000);
  try{c.sendControl("invocation-event",Protocol.object("invocationId",id,"entryPoint",entryPoint,"providerGeneration",providerGeneration,"windowGeneration",c.generation,"expiresAtElapsedMs",now+30000,"target",""));return true;}catch(RemoteException error){disconnect(component,true);return false;}
 }
 public boolean supportsContract(String component,String feature){Connection c=connections.get(component);return c!=null&&c.ready&&c.negotiated.contains(feature);}
 private void invalidateContract(Connection c){
  if(!c.ready)return;
  try{for(JSONObject result:c.controls.cancelAll())c.sendControl("control-result",result);c.negotiated.clear();c.invocations.clear();c.controls.catalogEpoch(++c.catalogEpoch);emit(c.component,"contract-invalidated",new JSONObject());c.remote.applyHostSnapshot(c.snapshot());}catch(RemoteException error){disconnect(c.component,true);}
 }
 /** Trusted in-process shell completion. External apps cannot call this through AIDL. */
 public boolean isContractRequestCurrent(String component,String session,String id){Connection c=connections.get(component);return c!=null&&c.ready&&approved(c.service)&&c.session.equals(session)&&c.controls.current(id,SystemClock.elapsedRealtime());}
 public void completeContractControl(String component,String session,String id,String state,String reason){
  Connection c=connections.get(component);if(c==null||!c.ready||!c.session.equals(session))return;
  JSONObject result=c.controls.complete(id,state,reason);if(result!=null)try{c.sendControl("control-result",result);}catch(RemoteException error){disconnect(component,true);}
 }
 public boolean isSourceSuppressed(String packageName) {
  if(packageName==null || !packageName.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+")) return false;
  for(Connection c:connections.values()) if(c.ready && allows(c.component,"suppress") && c.service.metaData!=null && packageName.equals(c.service.metaData.getString("com.faceclaw.SUPPRESS_PACKAGE",""))) return true;
  return false;
 }
 public String suppressedPackagesJson() {
  JSONArray values=new JSONArray();
  for(Connection c:connections.values()) if(c.ready && allows(c.component,"suppress") && c.service.metaData!=null) {
   String source=c.service.metaData.getString("com.faceclaw.SUPPRESS_PACKAGE","");
   if(source.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+")) values.put(source);
  }
  return values.toString();
 }
 /** Local settings catalog. No grants or app code are activated by enumeration. */
 public String androidAppsJson() {
  JSONArray result=new JSONArray(); PackageManager pm=context.getPackageManager();
  try {
   Map<String,ApplicationInfo> compatible=new HashMap<>();
   for(ResolveInfo resolved:discover()) {
    ServiceInfo service=resolved.serviceInfo;
    if(service!=null&&validService(service)) compatible.put(service.packageName,service.applicationInfo);
   }
   List<ApplicationInfo> apps=new ArrayList<>(compatible.values());
   apps.sort((a,b)->pm.getApplicationLabel(a).toString().compareToIgnoreCase(pm.getApplicationLabel(b).toString()));
   for(ApplicationInfo app:apps) {
    if(result.length()>=512) break;
    result.put(Protocol.object("packageName",app.packageName,"name",pm.getApplicationLabel(app).toString()));
   }
  } catch(Exception ignored) {}
  return result.toString();
 }
 /** Explicit local settings navigation only; package names never confer host authority. */
 public boolean openAndroidAppSettings(Activity activity,String appPackage) {
  if(activity==null||appPackage==null||appPackage.length()>255||!appPackage.matches("[a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+")) return false;
  try {
   context.getPackageManager().getApplicationInfo(appPackage,0);
   for(ResolveInfo app:discover()) if(appPackage.equals(app.serviceInfo.packageName)) {
    ServiceInfo current=context.getPackageManager().getServiceInfo(new ComponentName(appPackage,app.serviceInfo.name),PackageManager.GET_META_DATA);
    Intent settings=validService(current)?AppSettingsIntent.resolve(context,current):null;
    if(settings!=null) { activity.startActivity(settings); return true; }
   }
   activity.startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.fromParts("package",appPackage,null)));
   return true;
  } catch(PackageManager.NameNotFoundException|ActivityNotFoundException|SecurityException ignored) { return false; }
 }
 /** Reordering existing declarations never grants a feature. Local host UI only. */
 public boolean prioritizeExtension(String feature,String component) {
  if(!ExtensionContract.known(feature)||!extensions.orderedComponents(feature).contains(component))return false;
  if(!extensions.prioritize(feature,component))return false; extensionsChanged(); return true;
 }
 public void showBehaviorSettings(Activity activity) {
  if(activity==null)return;
  JSONArray features=extensions.snapshot().optJSONArray("features"); ArrayList<String> ids=new ArrayList<>(),labels=new ArrayList<>();
  if(features!=null)for(int i=0;i<features.length();i++) {
   JSONObject feature=features.optJSONObject(i); if(feature==null)continue;
   String id=feature.optString("feature"); JSONArray contenders=feature.optJSONArray("contenders");
   if(contenders==null||contenders.length()==0)continue;
   ids.add(id);String owner=feature.optBoolean("available")?appLabel(feature.optString("component")):"Faceclaw default";
   labels.add(extensionLabel(id)+" — "+owner);
  }
  new AlertDialog.Builder(activity).setTitle("System behaviors").setItems(labels.toArray(new String[0]),(d,index)->showExtensionOrder(activity,ids.get(index))).setNegativeButton("Close",null).show();
 }
 private String appLabel(String component) {
  ComponentName name=ComponentName.unflattenFromString(component); if(name==null)return component;
  try {return context.getPackageManager().getApplicationLabel(context.getPackageManager().getApplicationInfo(name.getPackageName(),0)).toString();}catch(Exception ignored){return name.getPackageName();}
 }
 public void showManager(Activity activity) {
  if(activity==null) return; refresh(); List<ResolveInfo> apps=discover();
  String[] labels=new String[apps.size()]; for(int i=0;i<apps.size();i++) { ResolveInfo r=apps.get(i); labels[i]=r.loadLabel(context.getPackageManager())+(approved(r.serviceInfo)?" (approved)":""); }
  new AlertDialog.Builder(activity).setTitle("Installed Faceclaw apps").setItems(labels,(d,index)->openAppInterface(activity,apps.get(index)))
   .setPositiveButton("SMS setup",(d,w)->FaceclawSms.showPermissions(activity)).setNeutralButton("Permissions and priority",(d,w)->showPermissionsManager(activity)).setNegativeButton("Close",null).show();
 }
 private void showPermissionsManager(Activity activity) {
  List<ResolveInfo> apps=discover(); String[] labels=new String[apps.size()];
  for(int i=0;i<apps.size();i++) labels[i]=apps.get(i).loadLabel(context.getPackageManager()).toString();
  new AlertDialog.Builder(activity).setTitle("Faceclaw permissions and priority").setItems(labels,(d,index)->showApp(activity,apps.get(index))).setNeutralButton("System behaviors",(d,w)->showBehaviorSettings(activity)).setNegativeButton("Close",null).show();
 }
 private void openAppInterface(Activity activity,ResolveInfo app) {
  try {
   ServiceInfo current=context.getPackageManager().getServiceInfo(new ComponentName(app.serviceInfo.packageName,app.serviceInfo.name),PackageManager.GET_META_DATA);
   if(validService(current)) {
    Intent intent=AppSettingsIntent.resolve(context,current);
    if(intent!=null) { activity.startActivity(intent); return; }
   }
  } catch(PackageManager.NameNotFoundException|ActivityNotFoundException|SecurityException ignored) {}
  new AlertDialog.Builder(activity).setTitle("App settings unavailable").setMessage("This app has no available phone settings screen. You can still manage its Faceclaw permissions here.")
   .setPositiveButton("Faceclaw permissions",(d,w)->showApp(activity,app)).setNegativeButton("Close",null).show();
 }
 /** Navigation only. The package hint never supplies service metadata, identity or grants. */
 public void showAppSettings(Activity activity,String appPackage) { showAppSettings(activity,appPackage,null); }
 /** Opens the app's host settings, optionally focused on one global feature. */
 public void showAppSettings(Activity activity,String appPackage,String feature) {
  if(activity==null) return;
  // The exported settings Activity can be the first screen in a cold host process.
  // Reconnect only previously approved apps before presenting their host-selection state.
  refresh();
  if(appPackage!=null && appPackage.length()<=255 && appPackage.matches("[a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+")) {
   for(ResolveInfo app:discover()) if(appPackage.equals(app.serviceInfo.packageName)) {
    showApp(activity,app,feature); return;
   }
  }
  showPermissionsManager(activity);
 }
 private boolean canShowConsent(Activity activity) {
  return activity!=null&&!activity.isFinishing()&&!activity.isDestroyed()&&activity.getWindow()!=null&&
   activity.getWindow().getDecorView().getWindowVisibility()==android.view.View.VISIBLE;
 }
 private void selectionUnavailable(Activity activity) {
  if(canShowConsent(activity)) new AlertDialog.Builder(activity).setMessage("Could not open the app's host selection. Keep this screen open and try again.").setPositiveButton("OK",null).show();
 }
 /** The explicit user tap gets a new handshake/session, never a cached expired consent token. */
 private void requestHostSelection(Activity activity,String component) {
  if(!canShowConsent(activity)) return;
  Connection previous=connections.get(component);
  if(previous==null||!approved(previous.service)) { selectionUnavailable(activity); return; }
  if(previous.ready) return;
  ServiceInfo service=previous.service; disconnect(component,false); bind(service);
  Connection requested=connections.get(component);
  if(requested==null) { selectionUnavailable(activity); return; }
  requested.selectionActivity=new java.lang.ref.WeakReference<>(activity);
  requested.selectionUntil=SystemClock.elapsedRealtime()+5000;
  main.postDelayed(()->{
   Activity waiting=requested.selectionActivity==null?null:requested.selectionActivity.get();
   requested.selectionActivity=null;
   if(waiting!=null&&!requested.ready) selectionUnavailable(waiting);
  },5000);
 }
 /** Android 14+ needs the visible sender to opt in for this one user-requested launch. */
 private void launchRequestedConsent(Connection connection,PendingIntent consent) {
  Activity activity=connection.selectionActivity==null?null:connection.selectionActivity.get();
  connection.selectionActivity=null;
  if(!canShowConsent(activity)||SystemClock.elapsedRealtime()>connection.selectionUntil||connections.get(connection.component)!=connection||!approved(connection.service)) return;
  ActivityOptions options=ActivityOptions.makeBasic();
  if(Build.VERSION.SDK_INT>=34) options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
  try { consent.send(activity,0,null,null,null,null,options.toBundle()); }
  catch(PendingIntent.CanceledException|SecurityException error) { selectionUnavailable(activity); }
 }
 /** Fresh defaults are a proposal for explicit approval, never read-time grants. */
 private boolean[] approvalChoices(String component) {
  boolean existing=prefs.contains(component+":pin");
  for(String capability:APPROVAL_CAPABILITIES) existing|=prefs.contains(component+":"+capability);
  boolean[] choices=new boolean[APPROVAL_CAPABILITIES.length];
  for(int i=0;i<choices.length;i++) {
   String capability=APPROVAL_CAPABILITIES[i];
   choices[i]=prefs.getBoolean(component+":"+capability,!capability.equals("messaging") && (!existing || capability.equals("previews")));
  }
  return choices;
 }
 /** Called only by the approval button; identity and all choices share one durable write. */
 private boolean approveSelection(ServiceInfo requested,String pin,boolean[] choices) {
  if(choices==null || choices.length!=APPROVAL_CAPABILITIES.length) return false;
  try {
   ServiceInfo current=context.getPackageManager().getServiceInfo(new ComponentName(requested.packageName,requested.name),PackageManager.GET_META_DATA);
   if(!validService(current) || !PackageIdentity.forPackage(context,current.packageName).equals(pin)) return false;
   String component=key(current);
   SharedPreferences.Editor edit=prefs.edit().putString(component+":pin",pin);
   if(!pin.equals(prefs.getString(component+":pin",""))) for(String saved:prefs.getAll().keySet()) if(saved.startsWith(component+":extension")) edit.remove(saved);
   for(int i=0;i<choices.length;i++) edit.putBoolean(component+":"+APPROVAL_CAPABILITIES[i],choices[i]);
   return edit.commit();
  } catch(Exception ignored) { return false; }
 }
 private void revokeApproval(String component) {
  restorationTokens.remove(component);retryAttempts.remove(component);recoveryContexts.remove(component);
  SharedPreferences.Editor edit=prefs.edit().remove(component+":pin").remove(component+":glance-widgets");
  for(String capability:APPROVAL_CAPABILITIES) edit.remove(component+":"+capability);
  for(String key:prefs.getAll().keySet()) if(key.startsWith(component+":extension")) edit.remove(key);
  edit.apply(); disconnect(component,false); extensionsChanged(); emit(component,"changed",new JSONObject());
 }
 private void showApp(Activity a,ResolveInfo resolved) { showApp(a,resolved,null); }
 private void showApp(Activity a,ResolveInfo resolved,String requestedFeature) {
  ServiceInfo s=resolved.serviceInfo; String k=key(s);
  if(!validService(s)) { new AlertDialog.Builder(a).setMessage("This app requires an incompatible Faceclaw protocol or its service is disabled.").setPositiveButton("OK",null).show(); return; }
  if(!approved(s)) {
   try {
    String pin=PackageIdentity.forPackage(context,s.packageName);
    boolean[] choices=approvalChoices(k);
    int padding=(int)(20*a.getResources().getDisplayMetrics().density);
    LinearLayout content=new LinearLayout(a); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(padding,padding,padding,0);
    TextView explanation=new TextView(a); explanation.setText("Allow this independently installed app to show a glasses window, receive input, and read the battery/weather displayed by the host? Checked permissions will be enabled when you approve. You can uncheck any permission now or change it later."); content.addView(explanation);
    String source=s.metaData.getString("com.faceclaw.SUPPRESS_PACKAGE","");
    String[] labels={"Notifications on glasses","Dictation and review","Message text previews","Suppress declared source on glasses","Assistant messaging (separate history consent and reviewed sends)"};
    for(int i=0;i<choices.length;i++) {
     final int index=i; CheckBox choice=new CheckBox(a); choice.setText(labels[i]); choice.setChecked(choices[i]); choice.setFilterTouchesWhenObscured(true);
     choice.setOnCheckedChangeListener((button,checked)->choices[index]=checked); content.addView(choice);
    }
    TextView detail=new TextView(a); detail.setText("Suppression applies only to the app's declared source while connected. Phone notifications stay unchanged."+(source.isEmpty()?"\nNo source is currently declared.":"\nDeclared source: "+source)+"\n\nSigning identity: "+pin); content.addView(detail);
    ScrollView scroll=new ScrollView(a); scroll.addView(content);
    AlertDialog consent=new AlertDialog.Builder(a).setTitle("Approve "+resolved.loadLabel(context.getPackageManager()))
     .setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Approve",(d,w)->{
      if(!approveSelection(s,pin,choices)) { new AlertDialog.Builder(a).setMessage("App identity changed or approval could not be saved. Reopen app settings and try again.").setPositiveButton("OK",null).show(); return; }
      refresh(); main.postDelayed(()->showApp(a,resolved,requestedFeature),400);
     }).create();
    consent.show(); consent.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    consent.getButton(AlertDialog.BUTTON_POSITIVE).setFilterTouchesWhenObscured(true);
   } catch(Exception ignored) {} return;
  }
  if(requestedFeature!=null && Arrays.asList(ExtensionContract.FEATURES).contains(requestedFeature)) { showExtensionFeature(a,k,requestedFeature); return; }
  Connection c=connections.get(k);
  ArrayList<String> options=new ArrayList<>(); options.add(c!=null&&c.ready?"Host selected":"Select this host on app");
  options.add("Notifications: "+prefs.getBoolean(k+":notifications",false)); options.add("Dictation/review: "+prefs.getBoolean(k+":dictation",false));
  options.add("Message text previews: "+prefs.getBoolean(k+":previews",true));
  options.add("Suppress declared source on glasses: "+prefs.getBoolean(k+":suppress",false));
  options.add("Assistant messaging: "+prefs.getBoolean(k+":messaging",false));
  options.add("Configure bridge connection"); options.add("Global customizations and providers"); options.add("Revoke app approval");
  new AlertDialog.Builder(a).setTitle(resolved.loadLabel(context.getPackageManager())).setItems(options.toArray(new String[0]),(d,index)->{
   if(index==0) requestHostSelection(a,k);
   else if(index>=1&&index<=5) {
    String flag=new String[]{"","notifications","dictation","previews","suppress","messaging"}[index];
    String source=s.metaData.getString("com.faceclaw.SUPPRESS_PACKAGE","");
    if(index==4&&source.isEmpty()) return;
    boolean enabled=prefs.getBoolean(k+":"+flag,flag.equals("previews"));
    Runnable change=()->{ prefs.edit().putBoolean(k+":"+flag,!enabled).apply(); emit(k,"grants-changed",new JSONObject()); publishCapabilities(k); };
    if(!enabled) new AlertDialog.Builder(a).setMessage(index==4?"Suppress only "+source+" notifications on glasses while this app is connected? Phone notifications stay unchanged.":"Enable "+options.get(index).split(":")[0]+" for this app?").setNegativeButton("Cancel",null).setPositiveButton("Enable",(dialog,which)->change.run()).show(); else change.run();
   } else if(index==6) configure(a,k,s); else if(index==7) showExtensions(a,k); else revokeApproval(k);
  }).setNegativeButton("Close",null).show();
 }
 /** Catalog ownership is the approved service and its pinned signing identity. */
 private JSONObject glanceRegistry(ServiceInfo service) {
  try {
   String component=key(service);if(!approved(service))return null;
   JSONObject saved=new JSONObject(prefs.getString(component+":glance-widgets","{}"));
   if(!prefs.getString(component+":pin","").equals(saved.optString("pin")))return null;
   return com.faceclaw.sdk.GlanceboardContract.registry(saved.getJSONObject("registry"));
  }catch(Exception ignored){return null;}
 }
 public String glanceboardRegistryJson() {
  JSONArray providers=new JSONArray();
  for(ResolveInfo app:discover()){
   if(providers.length()>=64)break;
   JSONObject registry=glanceRegistry(app.serviceInfo);if(registry==null)continue;
   String component=key(app.serviceInfo);
   providers.put(Protocol.object("component",component,"connected",isConnected(component),"widgets",registry.optJSONArray("widgets")));
  }
  return Protocol.object("version",1,"providers",providers).toString();
 }
 private void publishGlanceRegistry(){try{emit("","glanceboard-registry",new JSONObject(glanceboardRegistryJson()));}catch(Exception ignored){}}
 public boolean requestGlanceboardWidget(String component,String widgetId,String contextJson) {
  Connection connection=connections.get(component);if(connection==null||!connection.ready||!approved(connection.service))return false;
  JSONObject widget=com.faceclaw.sdk.GlanceboardContract.widget(glanceRegistry(connection.service),widgetId);if(widget==null)return false;
  try {
   JSONObject context=new JSONObject(contextJson==null?"{}":contextJson);if(context.toString().length()>8192)return false;
   send(component,"glanceboard-request",Protocol.object("version",2,"widgetId",widgetId,"width",288,"height",widget.getInt("rows")*144,"context",context).toString());return true;
  }catch(Exception ignored){return false;}
 }
 /** Read-only request; no window is opened and no user action authority is granted. */
 public boolean requestGlanceboard(String component) {
  Connection c=connections.get(component);
  if(c==null||!c.ready||!approved(c.service))return false;
  send(component,"glanceboard-request",Protocol.object("version",1).toString());return true;
 }
 public boolean publishSharedStyle(String json) {
  if(json==null||json.length()>ExtensionContract.MAX_CONFIG) return false;
  try {
   JSONObject style=ExtensionContract.configuration("ui.typography",new JSONObject(json));
   if(style.toString().equals(sharedStyle.toString())) return true; sharedStyle=style;
   for(Connection c:connections.values()) if(c.ready) send(c.component,"shared-style",sharedStyle.toString()); return true;
  } catch(Exception ignored) { return false; }
 }
 public String extensionsJson() { return extensions.snapshot().toString(); }
 private void extensionsChanged() {
  extensions.changed();
  Set<String> changed=new HashSet<>();
  for(String feature:ExtensionContract.FEATURES) {
   long epoch=extensions.generation(feature);
   if(!Long.valueOf(epoch).equals(publishedFeatureGenerations.put(feature,epoch))) changed.add(feature);
  }
  for(Connection c:connections.values()) {
   c.extensionRequests.entrySet().removeIf(entry->changed.contains(entry.getValue()) || (entry.getValue().equals("transcription")&&!isExtensionGranted(c.component,"transcription")));
   for(String feature:changed) { Surface surface=c.surfaces.remove(feature); if(surface!=null)surface.close(); }
  }
  String snapshot=extensionsJson();
  for(Connection c:connections.values()) if(c.ready&&!snapshot.equals(c.lastExtensionSnapshot)) {
   c.lastExtensionSnapshot=snapshot;invalidateContract(c);send(c.component,"extensions",snapshot);
   // A failed send can synchronously disconnect and publish a newer snapshot.
   if(!snapshot.equals(extensionsJson()))return;
  }
  if(snapshot.equals(publishedExtensionSnapshot))return;
  publishedExtensionSnapshot=snapshot;
  FaceclawSettings.getInstance(context).setString("apps.extensions.effective",snapshot);
  emit("","extensions-changed",extensions.snapshot());
 }
 /** Local host API. This is never exposed as an external IPC setter. */
 public boolean isExtensionGranted(String component,String feature) { return extensions.granted(component,feature)&&isConnected(component); }
 public boolean sendAppProvider(String component,String feature,String type,String json) {
  if(!feature.equals("transcription")||!isExtensionGranted(component,feature)||!Arrays.asList("request","event","cancel").contains(type)) return false;
  return sendExtensionInternal(component,feature,type,json,true);
 }
 public boolean sendExtension(String component,String feature,String type,String json) { return sendExtensionInternal(component,feature,type,json,false); }
 private boolean sendExtensionInternal(String component,String feature,String type,String json,boolean ownProvider) {
  if(!isConnected(component)||(!ownProvider&&!extensions.controls(component,feature))||json==null||json.length()>Protocol.MAX_JSON/2||!Arrays.asList("request","event","cancel","input").contains(type)) return false;
  Connection c=connections.get(component); if(c==null) return false;
  try {
   JSONObject data=new JSONObject(json); long generation=extensions.generation(feature);String inputTrace="";if(type.equals("input")){inputTrace=data.optString("traceId");if(inputTrace.isEmpty())inputTrace=UUID.randomUUID().toString();data.put("traceId",inputTrace);data.put("extensionGeneration",generation);JSONObject input=data.optJSONObject("input");if(input!=null){input.put("traceId",inputTrace);input.put("extensionGeneration",generation);}}
   if(type.equals("request")) {
    String id=data.getString("requestId"); if(!ExtensionContract.token(id)||c.extensionRequests.size()>=32||c.extensionRequests.containsKey(id)) return false;
    c.extensionRequests.put(id,feature);
    main.postDelayed(()->{
     if(connections.get(component)==c&&generation==extensions.generation(feature)&&feature.equals(c.extensionRequests.remove(id)))
      emit(component,"extension-event",Protocol.object("feature",feature,"generation",generation,"type","timeout","requestId",id));
    },feature.equals("assistant")?600000:feature.equals("transcription")?390000:feature.equals("refinement")?120000:20000);
   }
   if(type.equals("cancel")) c.extensionRequests.remove(data.optString("requestId"));
   try {
    if(type.equals("input")){
     Surface surface=c.surfaces.get(feature);
     if(surface==null||!surface.visible||!surface.screenOn)return false;
     JSONObject input=data.optJSONObject("input");
     android.util.Log.i("NotificationInput","host-dispatch feature="+feature+" trace="+inputTrace);
     c.sendInput("extension:"+feature,new FaceclawInputEvent(input==null?data:input));
    }
    else c.sendControl("extension-event",Protocol.object("feature",feature,"generation",generation,"type",type,"data",data));
   }
   catch(Exception uncertain) {
    if(type.equals("input"))android.util.Log.w("NotificationInput","host-dispatch-uncertain feature="+feature+" trace="+inputTrace,uncertain);
    String id=data.optString("requestId");
    if(feature.equals(c.extensionRequests.remove(id))) emit(component,"extension-event",Protocol.object("feature",feature,"generation",generation,"type","timeout","requestId",id));
    // IPC was attempted. A transport exception cannot safely authorize backend fallback/retry.
   }
   if(type.equals("input")){Surface surface=c.surfaces.get(feature);if(surface!=null)scheduleCredit(c,surface,DisplayScheduler.Priority.DIRECT_INPUT,inputTrace);}
   return true;
  } catch(Exception ignored) { return false; }
 }
 private void receiveExtensionControl(Connection c,String type,JSONObject data) throws Exception {
  String feature=data.getString("feature"); long generation=data.getLong("generation");
  boolean pending=feature.equals(c.extensionRequests.get(data.optString("requestId")));
  if(generation!=extensions.generation(feature)||(!extensions.controls(c.component,feature)&&!(pending&&feature.equals("transcription")&&isExtensionGranted(c.component,feature)))) return;
  JSONObject payload=data.getJSONObject("data"); if(payload.toString().length()>Protocol.MAX_JSON/2) return;
  if(type.equals("extension-result")||type.equals("extension-progress")) {
   String id=data.getString("requestId"); if(!feature.equals(c.extensionRequests.get(id))) return;
   if(type.equals("extension-result")) c.extensionRequests.remove(id);
   emit(c.component,"extension-event",Protocol.object("feature",feature,"generation",generation,"type",type.equals("extension-result")?"result":"progress","requestId",id,"data",payload));
  } else {
   String action=data.getString("action"), id=data.getString("actionId");
   if(!ExtensionContract.action(feature,action)||!ExtensionContract.token(id)||c.actionIds.contains(id)) return;
   // Fail closed after the session's bounded action ledger fills; never forget a consumed authority.
   if(c.actionIds.size()>=4096) return; c.actionIds.add(id);
   emit(c.component,"extension-event",Protocol.object("feature",feature,"generation",generation,"type","action","action",action,"actionId",id,"data",payload));
  }
 }
 private final class Surface {
  final String id;
  volatile int width,height; volatile long generation; long sequence,contentVersion,clientFrameId,creditId,sceneVersion,lastCreditAtMs,creditScheduleEpoch; volatile boolean visible,screenOn; boolean creditOutstanding,creditGranted,rasterSinceScene;
  SharedMemory[] memories=new SharedMemory[0];ByteBuffer[] mappings=new ByteBuffer[0];byte[] pixels,draws;Map<Long,Bundle> nodes=new HashMap<>();ArrayList<Long> sceneOrder=new ArrayList<>();
  Surface(String id,int width,int height,long generation) { this.id=id;this.width=width;this.height=height;this.generation=generation;this.pixels=width>0&&height>0?new byte[width*height]:null; }
  synchronized void reset(int width,int height,long generation){this.width=width;this.height=height;this.generation=generation;this.sequence=0;this.contentVersion=0;this.clientFrameId=0;clearCreditLocked();this.sceneVersion=0;this.nodes.clear();this.sceneOrder.clear();this.pixels=width>0&&height>0?new byte[width*height]:null;closePool();}
  synchronized void setState(boolean visible,boolean screenOn,long generation){this.visible=visible;this.screenOn=screenOn;if(this.generation!=generation)reset(width,height,generation);if(!visible||!screenOn)clearCreditLocked();}
  void clearCreditLocked(){creditOutstanding=false;creditGranted=false;creditScheduleEpoch++;}
  synchronized void closePool(){for(ByteBuffer mapping:mappings)try{SharedMemory.unmap(mapping);}catch(Exception ignored){}for(SharedMemory memory:memories)try{memory.close();}catch(Exception ignored){}mappings=new ByteBuffer[0];memories=new SharedMemory[0];}
  synchronized void close(){closePool();clearCreditLocked();nodes.clear();sceneOrder.clear();}
 }
 private long nextSurfaceGeneration=1;
 /** Only the host's real mirror hit-test dispatches bounded pointer input to a current visible surface. */
 public boolean sendExtensionPointer(String component,String feature,int x,int y,int width,int height) {
  if(!ExtensionContract.surface(feature)||!isConnected(component)||!extensions.controls(component,feature)) return false;
  Connection c=connections.get(component); Surface surface=c==null?null:c.surfaces.get(feature);
  if(surface==null||!surface.visible||!surface.screenOn||surface.width!=width||surface.height!=height||x<0||y<0||x>=width||y>=height) return false;
  return sendExtensionInternal(component,feature,"input",Protocol.object("event","input","input",Protocol.object("type","pointer-click","x",x,"y",y)).toString(),false);
 }
 public boolean openExtensionSurface(String component,String feature,int width,int height) {
  if(!ExtensionContract.surface(feature)||!isConnected(component)||!extensions.controls(component,feature)) return false;
  try {
   Protocol.frameSize(width,height); Connection c=connections.get(component); Surface surface=new Surface("extension:"+feature,width,height,++nextSurfaceGeneration); Surface previous=c.surfaces.put(feature,surface); if(previous!=null)previous.close();
   c.sendControl("extension-surface",Protocol.object("feature",feature,"type","open","width",width,"height",height,"generation",surface.generation,"extensionGeneration",extensions.generation(feature),"visible",false,"screenOn",false)); return true;
  } catch(Exception ignored) { return false; }
 }
 public void setExtensionSurfaceVisibility(String component,String feature,boolean visible,boolean screenOn) {
  Connection c=connections.get(component); if(c==null||!extensions.controls(component,feature)) return;
  Surface surface=c.surfaces.get(feature); if(surface==null) return; surface.setState(visible,screenOn,surface.generation);
  send(component,"extension-surface",Protocol.object("feature",feature,"type","visibility","generation",surface.generation,"visible",visible,"screenOn",screenOn).toString());
  if(visible&&screenOn)scheduleCredit(c,surface,DisplayScheduler.Priority.VISIBLE_EXTENSION);
 }
 public void closeExtensionSurface(String component,String feature) {
  closeExtensionSurface(component,feature,null);
 }
 public void closeExtensionSurface(String component,String feature,String presentationId) {
  Connection c=connections.get(component); if(c==null) return; Surface surface=c.surfaces.remove(feature); if(surface==null) return;
  surface.close();
  JSONObject payload=Protocol.object("feature",feature,"type","close","generation",surface.generation);
  if(presentationId!=null&&!presentationId.isEmpty())try{payload.put("presentationId",presentationId);}catch(Exception ignored){}
  send(component,"extension-surface",payload.toString());
 }
 private String extensionLabel(String feature) {
  switch(feature) {
   case "notification-content": return "Own inbox (reads other apps’ notification content; open and dismiss with user input)";
   case "ui.notifications": return "Notification presentation (reads other apps' notification content)";
   case "ui.composer": return "Message composer (shows and edits drafts from other apps)";
   case "device-tools": return "Device tools (read and act through approved host tools)";
   case "assistant": return "Assistant (receives assistant prompts)";
   case "transcription": return "Transcription (receives microphone audio)";
   case "refinement": return "Refinement (receives dictated drafts)";
   default: return feature;
  }
 }
 private void showExtensionFeature(Activity activity,String component,String feature) {
  if(!ExtensionContract.known(feature) || extensions.declaration(component,feature)==null) {
   new AlertDialog.Builder(activity).setMessage("This feature is not published by the app. Open its settings and enable it first.").setPositiveButton("OK",null).show(); return;
  }
  boolean granted=extensions.granted(component,feature);
  boolean active=extensions.controls(component,feature);
  String state=!granted?"Permission needed":active?"Active":!extensions.declaration(component,feature).optBoolean("enabled")?"Disabled by app":!isConnected(component)?"Waiting for connection":"Allowed; another provider or dependency takes priority";
  String[] actions={granted?"Revoke permission":"Grant permission","Set feature priority"};
  new AlertDialog.Builder(activity).setTitle(extensionLabel(feature)+": "+state).setItems(actions,(dialog,index)->{
   if(index==1) { showExtensionOrder(activity,feature); return; }
   if(granted) { extensions.grant(component,feature,false); extensionsChanged(); return; }
   AlertDialog consent=new AlertDialog.Builder(activity).setTitle("Allow global feature?").setMessage(extensionLabel(feature)+" can affect Faceclaw outside this app. You can revoke this permission here.").setNegativeButton("Cancel",null).setPositiveButton("Allow",(ignored,which)->{ if(extensions.grant(component,feature,true)) extensionsChanged(); }).create();
   consent.show(); consent.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE); consent.getButton(AlertDialog.BUTTON_POSITIVE).setFilterTouchesWhenObscured(true);
  }).setNegativeButton("Close",null).show();
 }
 private void showExtensions(Activity activity,String component) {
  ArrayList<String> features=new ArrayList<>(),labels=new ArrayList<>();
  for(String feature:ExtensionContract.FEATURES) if(extensions.declaration(component,feature)!=null) {
   features.add(feature);
   String state=!extensions.granted(component,feature)?"Permission needed":extensions.controls(component,feature)?"Active":!extensions.declaration(component,feature).optBoolean("enabled")?"Disabled by app":!isConnected(component)?"Waiting for connection":"Allowed; another provider or dependency takes priority";
   labels.add(extensionLabel(feature)+": "+state);
  }
  if(features.isEmpty()) { new AlertDialog.Builder(activity).setMessage("This app has not published customization features. Open its settings and connect it first.").setPositiveButton("OK",null).show(); return; }
  new AlertDialog.Builder(activity).setTitle("Global customizations").setItems(labels.toArray(new String[0]),(d,index)->{
   showExtensionFeature(activity,component,features.get(index));
  }).setNegativeButton("Close",null).show();
 }
 private void showExtensionOrder(Activity activity,String feature) {
  List<String> components=extensions.orderedComponents(feature); String[] labels=new String[components.size()];
  for(int i=0;i<labels.length;i++) { ComponentName name=ComponentName.unflattenFromString(components.get(i)); String label=name==null?components.get(i):name.getPackageName(); try { label=context.getPackageManager().getApplicationLabel(context.getPackageManager().getApplicationInfo(name.getPackageName(),0)).toString(); } catch(Exception ignored) {} labels[i]=(i+1)+". "+label; }
  new AlertDialog.Builder(activity).setTitle("Priority: tap to move first").setItems(labels,(d,index)->{ if(extensions.prioritize(feature,components.get(index))) extensionsChanged(); showExtensionOrder(activity,feature); }).setNegativeButton("Done",null).show();
 }
 private void configure(Activity a,String k,ServiceInfo service) {
  if(!isConnected(k)||!"bridge".equals(service.metaData.getString("com.faceclaw.CONFIGURATION",""))) return;
  LinearLayout layout=new LinearLayout(a); layout.setOrientation(LinearLayout.VERTICAL);
  EditText endpoint=new EditText(a); endpoint.setHint("https://private-bridge.example"); endpoint.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
  EditText code=new EditText(a); code.setHint("One-time bridge pairing code"); code.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
  layout.addView(endpoint); layout.addView(code);
  AlertDialog dialog=new AlertDialog.Builder(a).setTitle("Connect app to bridge").setView(layout).setNegativeButton("Cancel",null).setPositiveButton("Pair",(d,w)->{
   String url=endpoint.getText().toString().trim(), token=code.getText().toString().trim();
   if(url.startsWith("https://")&&url.length()<2048&&token.length()>=16&&token.length()<=512) send(k,"configure",Protocol.object("endpoint",url,"code",token).toString());
   code.setText("");
  }).create(); dialog.getWindow(); dialog.show(); dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
 }
}
