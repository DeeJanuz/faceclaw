package com.faceclaw.sdk;

import org.json.*;
import java.util.*;
import java.util.function.*;

/** Negotiated application controls. Terminal unknown outcomes must not be retried automatically. */
public final class AppControls {
 public interface Transport {boolean send(String type,JSONObject data);}
 public interface Scheduler {void after(long milliseconds,Runnable task);}
 private static final class Pending {final JSONObject request;final Consumer<JSONObject> callback;Pending(JSONObject request,Consumer<JSONObject> callback){this.request=request;this.callback=callback;}}
 private final Transport transport;private final LongSupplier clock;private final Scheduler scheduler;
 private final Map<String,Pending> pending=new LinkedHashMap<>();private final Set<String> negotiated=java.util.concurrent.ConcurrentHashMap.newKeySet();
 private AppIndependenceCatalog catalog=AppIndependenceCatalog.fromCapabilities(null);
 private long windowGeneration,revision,declarationEpoch,connectionEpoch;
 private final Map<String,Long> invocations=new HashMap<>();
 private final Map<String,CaptureSession> captures=new HashMap<>();
 private JSONObject desiredPolicy;private Runnable ready=()->{};
 public AppControls(Transport transport,LongSupplier clock,Scheduler scheduler){this.transport=transport;this.clock=clock;this.scheduler=scheduler;}
 public boolean supports(String feature){return negotiated.contains(feature);}
 public synchronized void onReady(Runnable callback){ready=callback==null?()->{}:callback;}
 public synchronized void snapshot(AppIndependenceCatalog next,long generation){
  windowGeneration=generation;
  if(catalog.epoch==next.epoch&&catalog.state==next.state)return;
  disconnect();catalog=next;windowGeneration=generation;if(!next.isSupported())return;
  JSONArray features=new JSONArray();for(String id:Arrays.asList("control.result","window.policy","capture.session","invocation.lifecycle","resource.release",ExtensionContract.NOTIFICATION_PREVIEW_TIMING))if(next.supports(id,1))features.put(Protocol.object("id",id,"minVersion",1,"required",false,"fallback","none"));
  transport.send("publish-contract",Protocol.object("contractVersion",1,"epoch",++declarationEpoch,"features",features));
 }
 public synchronized void window(long generation){windowGeneration=generation;}
 public synchronized void visible(boolean visible){if(visible&&desiredPolicy!=null&&supports("window.policy"))request("window.policy",desiredPolicy,5000,result->{});}
 public synchronized void receive(String type,JSONObject data){
  if(type.equals("capture-status")||type.equals("capture-transcript")){CaptureSession capture=captures.get(data.optString("captureId"));if(capture!=null){capture.event(type,data);if(capture.terminal())captures.remove(capture.id);}return;}
  if(type.equals("contract-result")){
   if(data.optLong("catalogEpoch")!=catalog.epoch||data.optLong("epoch")!=declarationEpoch||!data.optString("state").equals("applied"))return;
   negotiated.clear();JSONArray features=data.optJSONArray("features");if(features==null)return;for(int i=0;i<features.length();i++){JSONObject f=features.optJSONObject(i);if(f!=null&&f.optString("state").equals("accepted")&&catalog.supports(f.optString("id"),1))negotiated.add(f.optString("id"));}
   if(desiredPolicy!=null&&supports("window.policy")&&windowGeneration>0)request("window.policy",desiredPolicy,5000,result->{});
   ready.run();return;
  }
  if(!type.equals("control-result")||data.optLong("catalogEpoch")!=catalog.epoch)return;
  Pending p=pending.get(data.optString("requestId"));if(p==null||!p.request.optString("operation").equals(data.optString("operation"))||p.request.optLong("revision")!=data.optLong("revision")||p.request.optLong("windowGeneration")!=data.optLong("windowGeneration"))return;
  String state=data.optString("state");if(Arrays.asList("applied","rejected","cancelled","unknown","expired").contains(state)){pending.remove(data.optString("requestId"));p.callback.accept(IndependenceProtocol.copy(data));}
 }
 public synchronized String request(String operation,JSONObject payload,long timeoutMs,Consumer<JSONObject> callback){
  Objects.requireNonNull(callback);String id=UUID.randomUUID().toString();JSONObject request=Protocol.object("requestId",id,"operation",operation,"windowGeneration",operation.equals("window.open")?0:windowGeneration,"revision",++revision,"expiresAtElapsedMs",clock.getAsLong()+Math.max(1,Math.min(300000,timeoutMs)),"payload",IndependenceProtocol.copy(payload));
  String reason="";try{IndependenceProtocol.request(request);}catch(IllegalArgumentException invalid){reason=invalid.getMessage();}
  if(reason.isEmpty()&&!supports(IndependenceProtocol.feature(operation)))reason="unsupported";
  if(reason.isEmpty()&&pending.size()>=32)reason="rate_limited";
  if(!reason.isEmpty()){callback.accept(local(request,"rejected",reason));return id;}
  Pending entry=new Pending(request,callback);pending.put(id,entry);long epoch=connectionEpoch;
  boolean sent=transport.send("control-request",request);
  if(!sent){if(pending.remove(id)==entry)callback.accept(local(request,"unknown","host_unavailable"));return id;}
  scheduler.after(Math.max(1,request.optLong("expiresAtElapsedMs")-clock.getAsLong()),()->{synchronized(AppControls.this){if(epoch==connectionEpoch&&pending.remove(id)==entry)callback.accept(local(request,"unknown","expired"));}});
  return id;
 }
 public synchronized String setWindowPolicy(WindowPolicy policy,Consumer<JSONObject> callback){desiredPolicy=policy.toJson();return request("window.policy",desiredPolicy,5000,callback);}
 public synchronized boolean acceptInvocation(JSONObject data){
  long now=clock.getAsLong();invocations.entrySet().removeIf(e->e.getValue()<=now);
  try{IndependenceProtocol.keys(data,"invocationId","entryPoint","providerGeneration","windowGeneration","expiresAtElapsedMs","target","stateRevision");String id=IndependenceProtocol.token(data,"invocationId");long expiry=IndependenceProtocol.integer(data,"expiresAtElapsedMs",1,Long.MAX_VALUE);if(!supports("invocation.lifecycle")||expiry<=now||expiry-now>30000||data.optLong("windowGeneration")!=windowGeneration||!Arrays.asList("wakeword","text-entry","app-button").contains(data.optString("entryPoint"))||invocations.containsKey(id)||invocations.size()>=32)return false;invocations.put(id,expiry);transport.send("invocation-result",Protocol.object("invocationId",id,"state","accepted"));return true;}catch(IllegalArgumentException invalid){return false;}
 }
 public synchronized void finishInvocation(String id,boolean completed){if(invocations.containsKey(id))transport.send("invocation-result",Protocol.object("invocationId",id,"state",completed?"completed":"cancelled"));}
 public synchronized CaptureSession capture(CaptureSession.Purpose purpose,String label,long providerGeneration,java.util.function.Consumer<JSONObject> listener){
  captures.entrySet().removeIf(entry->entry.getValue().terminal());
  String id=UUID.randomUUID().toString();CaptureSession capture=new CaptureSession(id,transport,listener);
  if(!supports("capture.session")||captures.size()>=1){capture.event("capture-status",Protocol.object("captureId",id,"status","rejected","reason",supports("capture.session")?"busy":"unsupported"));return capture;}
  captures.put(id,capture);long epoch=connectionEpoch;
  if(!transport.send("capture-start",Protocol.object("captureId",id,"purpose",purpose.name().toLowerCase(java.util.Locale.ROOT),"label",label,"providerGeneration",providerGeneration,"windowGeneration",windowGeneration,"expiresAtElapsedMs",clock.getAsLong()+300000))){captures.remove(id);capture.event("capture-status",Protocol.object("captureId",id,"status","rejected","reason","host_unavailable"));}
  scheduler.after(300000,()->{synchronized(AppControls.this){if(epoch==connectionEpoch&&captures.remove(id)==capture)capture.cancel();}});
  return capture;
 }
 public synchronized void disconnect(){
  connectionEpoch++;invocations.clear();for(CaptureSession c:new ArrayList<>(captures.values()))c.event("capture-status",Protocol.object("captureId",c.id,"status","cancelled","reason","host_unavailable"));captures.clear();List<Pending> abandoned=new ArrayList<>(pending.values());pending.clear();negotiated.clear();catalog=AppIndependenceCatalog.fromCapabilities(null);windowGeneration=0;
  for(Pending p:abandoned)try{p.callback.accept(local(p.request,"unknown","host_unavailable"));}catch(RuntimeException ignored){}
 }
 private JSONObject local(JSONObject request,String state,String reason){JSONObject result=IndependenceProtocol.copy(request);try{result.remove("payload");result.put("state",state);result.put("reason",reason);}catch(JSONException impossible){throw new IllegalStateException(impossible);}return result;}
}
