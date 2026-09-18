package com.faceclaw.sdk;

import android.os.*;
import com.faceclaw.sdk.ipc.IFaceclawHostSession;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Authenticated application-side session. Thread-safe; callbacks are marshalled by the service. */
public final class FaceclawSession {
 interface Callback {void onSnapshot(HostSnapshot snapshot);void onControl(ControlEvent event);void onInput(RenderSurface surface,FaceclawInputEvent event);void onCreditWithoutRenderer(RenderSurface surface,RenderCredit credit);void onOutcome(FrameOutcome outcome);void onDiagnostic(SdkDiagnostic diagnostic);default void onTransportLost(IFaceclawHostSession failedHost) {}}
 private IFaceclawHostSession host;
 private final Callback callback;
 private final Handler callbackHandler=new Handler(Looper.getMainLooper());
 private final DiagnosticQueue diagnostics=new DiagnosticQueue(task->callbackHandler.post(task));
 private final StateOrder stateOrder=new StateOrder();
 private final Map<String,RenderSurface> surfaces=new HashMap<>();
 private final ResourceRegistry resources;
 private final AppControls controls=new AppControls(this::sendControl,SystemClock::elapsedRealtime,(delay,task)->callbackHandler.postDelayed(task,delay));
 public AppControls controls(){return controls;}
 private long nextFrame=1,nextContent=1;
 private boolean closed,connected=true,replayRequired=true;

 FaceclawSession(IFaceclawHostSession host,Callback callback){this.host=host;this.callback=callback;resources=new ResourceRegistry(this);surfaces.put("window",new RenderSurface(this,"window"));}
 public synchronized RenderSurface windowSurface(){return surfaces.get("window");}
 public synchronized RenderSurface extensionSurface(String feature){return surfaces.computeIfAbsent("extension:"+feature,id->new RenderSurface(this,id));}
 public ResourceRegistry resources(){return resources;}
 public synchronized long nextClientFrameId(){return nextFrame++;}
 public synchronized long nextContentVersion(){return nextContent++;}

 void invalidateAll(){for(RenderSurface surface:surfaceSnapshot())surface.invalidate(InvalidateReason.STATE);}
 public void setRestorationToken(byte[] value){if(value==null||value.length>4096)throw new IllegalArgumentException("Restoration token exceeds 4 KiB");sendControlEvent(new ControlEvent("restoration-token",new JSONObject(),value));}
 synchronized void attach(IFaceclawHostSession next){if(closed)throw new IllegalStateException("Session is closed");host=next;connected=true;replayRequired=true;stateOrder.reset();diagnostics.clear();}
 void detach(){
  ArrayList<RenderSurface> current;
  synchronized(this){if(closed)return;connected=false;host=null;current=new ArrayList<>(surfaces.values());}
  controls.disconnect();
  for(RenderSurface surface:current)surface.suspend();
 }

 void applySnapshot(HostSnapshot snapshot){
  boolean reconnect;
  synchronized(this){if(closed||!connected||snapshot==null||!stateOrder.accept(snapshot.stateRevision))return;reconnect=replayRequired;replayRequired=false;}
  if(snapshot.protocolMajor!=Protocol.VERSION){close();return;}
  HashSet<String> present=new HashSet<>();LinkedHashSet<RenderSurface> reconstructing=new LinkedHashSet<>();
  for(SurfaceSnapshot state:snapshot.surfaces){
   RenderSurface surface="window".equals(state.id)?windowSurface():state.id.startsWith("extension:")?extensionSurface(state.id.substring(10)):null;
   if(surface!=null){present.add(state.id);if(reconnect||surface.generation()!=state.generation)reconstructing.add(surface);surface.configure(state.width,state.height,state.generation,state.visible,state.screenOn);}
  }
  if(!present.contains("window")){
   RenderSurface window=windowSurface();
   if(window!=null){
    if(snapshot.windowWidth>0&&snapshot.windowHeight>0){if(reconnect||window.generation()!=snapshot.windowGeneration)reconstructing.add(window);window.configure(snapshot.windowWidth,snapshot.windowHeight,snapshot.windowGeneration,snapshot.windowOpen&&snapshot.windowVisible,snapshot.screenOn);}
    else window.setVisibility(false,snapshot.screenOn);
   }
  }
  ArrayList<RenderSurface> removed=new ArrayList<>();
  synchronized(this){for(Iterator<Map.Entry<String,RenderSurface>> iterator=surfaces.entrySet().iterator();iterator.hasNext();){Map.Entry<String,RenderSurface> entry=iterator.next();if(entry.getKey().startsWith("extension:")&&!present.contains(entry.getKey())){removed.add(entry.getValue());iterator.remove();}}}
  for(RenderSurface surface:removed)surface.closeFromSession();
  controls.snapshot(snapshot.appIndependence,snapshot.windowGeneration);
  callback.onSnapshot(snapshot);
  if(!reconstructing.isEmpty()){resources.replay();for(RenderSurface surface:reconstructing)surface.replayScene();}
 }

 void applyControl(ControlEvent event){
  synchronized(this){if(closed||!connected||event==null||!stateOrder.accept(event.data.optLong("stateRevision",0)))return;}
  String type=event.type;JSONObject data=event.data;
  try{
   if(type.equals("scene-result")){
    RenderSurface surface=surface(data.getString("surfaceId"));
    if(surface==null||surface.generation()!=data.getLong("generation"))return;
    surface.scene().result(data.getLong("sceneVersion"),data.getBoolean("accepted"),surface.authorityEpoch());
   }else if(type.equals("open")||type.equals("resize")){
    RenderSurface window=windowSurface();if(window!=null){window.configure(data.getInt("width"),data.getInt("height"),data.getLong("generation"),data.optBoolean("visible",window.visible()),data.optBoolean("screenOn",true));window.replayScene();}
   }else if(type.equals("visibility")){
    RenderSurface window=windowSurface();if(window!=null)window.setVisibility(data.optBoolean("visible"),data.optBoolean("screenOn",true));
   }else if(type.equals("close")){
    RenderSurface window=windowSurface();if(window!=null)window.setVisibility(false,false);
   }else if(type.equals("extension-surface")){
    String feature=data.getString("feature"),key="extension:"+feature;RenderSurface surface=extensionSurface(feature);String action=data.getString("type");
    if(action.equals("open")||action.equals("resize")){surface.configure(data.getInt("width"),data.getInt("height"),data.getLong("generation"),data.optBoolean("visible",true),data.optBoolean("screenOn",true));surface.replayScene();}
    else if(action.equals("visibility"))surface.setVisibility(data.optBoolean("visible"),data.optBoolean("screenOn",true));
    else if(action.equals("close")){surface.close();synchronized(this){if(surfaces.get(key)==surface)surfaces.remove(key);}}
   }
   if(type.equals("open")||type.equals("resize"))controls.window(data.optLong("generation"));
   if(type.equals("visibility"))controls.visible(data.optBoolean("visible")&&data.optBoolean("screenOn"));controls.receive(type,data);if(type.equals("contract-result")&&data.optString("state").equals("applied")&&controls.supports("resource.release")){resources.replay();for(RenderSurface surface:surfaceSnapshot())surface.replayScene();invalidateAll();}if(type.equals("resource-result"))resources.released(data.optInt("resourceId"),data.optString("status"));callback.onControl(event);resources.collect();
  }catch(Exception ignored){}
 }

 void applyInput(String surfaceId,FaceclawInputEvent event){if(event==null)return;RenderSurface surface=surface(surfaceId);if(surface!=null)callback.onInput(surface,event);}
 void grant(RenderCredit credit){if(credit==null)return;RenderSurface surface=surface(credit.surfaceId);if(surface==null)return;boolean notify=!surface.hasRenderer();surface.grant(credit);if(notify)callback.onCreditWithoutRenderer(surface,credit);}
 void released(String id,long generation,int slot,long sequence){RenderSurface surface=surface(id);if(surface!=null)surface.release(generation,slot,sequence);resources.collect();}
 void outcome(FrameOutcome value){if(value!=null)callback.onOutcome(value);}
 void reportDiagnostic(SdkDiagnostic.Category category,String operation,RenderSurface surface,long generation,boolean recoverable){
  diagnostics.offer(category.name()+":"+operation,()->callback.onDiagnostic(new SdkDiagnostic(category,operation,surface==null?"":surface.id(),generation,recoverable)));
 }
 private void transportLost(IFaceclawHostSession failedHost){
  ArrayList<RenderSurface> failed;
  synchronized(this){if(closed||!connected||host!=failedHost)return;connected=false;host=null;failed=new ArrayList<>(surfaces.values());}
  for(RenderSurface surface:failed)surface.suspend();
  callbackHandler.post(()->callback.onTransportLost(failedHost));
 }


 void registerSurface(RenderSurface surface,long generation,int width,int height,ArrayList<SharedMemory> memories){
  IFaceclawHostSession current=currentHost();if(current==null)return;
  try{current.registerSurface(new SurfaceRegistration(surface.id(),generation,width,height,memories));}catch(RemoteException error){reportDiagnostic(SdkDiagnostic.Category.TRANSPORT_FAILURE,"register-surface",surface,generation,true);transportLost(current);}
 }
 void unregisterSurface(RenderSurface surface,long generation){
  IFaceclawHostSession current=currentHost();if(current==null)return;
  try{current.unregisterSurface(surface.id(),generation);}catch(RemoteException error){reportDiagnostic(SdkDiagnostic.Category.TRANSPORT_FAILURE,"unregister-surface",surface,generation,true);transportLost(current);}
 }
 void requestRender(RenderSurface surface,InvalidateReason reason){
  long generation=surface.generation();IFaceclawHostSession current=currentHost();if(current==null)return;
  try{current.requestRender(surface.id(),generation,reason.ordinal());}catch(RemoteException error){reportDiagnostic(SdkDiagnostic.Category.TRANSPORT_FAILURE,"request-render",surface,generation,true);transportLost(current);}
 }
 boolean submitFrame(RenderSurface surface,FrameLease lease,FrameMetadata metadata){
  IFaceclawHostSession current=currentHost();if(current==null){surface.failLocalSubmit(lease);return false;}
  try{
   List<DamageRect> damage=metadata.damage.isEmpty()?Collections.singletonList(new DamageRect(0,0,lease.width,lease.height)):metadata.damage;
   int[] rects=new int[damage.size()*4];for(int i=0;i<damage.size();i++){DamageRect r=damage.get(i);rects[i*4]=r.x;rects[i*4+1]=r.y;rects[i*4+2]=r.width;rects[i*4+3]=r.height;}
   current.submitFrame(new FrameSubmission(surface.id(),lease.generation,lease.slotId,lease.sequence,lease.credit.creditId,metadata.clientFrameId,metadata.contentVersion,metadata.requestNextFrame,metadata.traceId,rects,DrawBatch.encodeMetadata(metadata.draws,metadata.retainedCopies)));
   return true;
  }catch(RemoteException error){surface.release(lease.generation,lease.slotId,lease.sequence);reportDiagnostic(SdkDiagnostic.Category.TRANSPORT_FAILURE,"submit-frame",surface,lease.generation,true);transportLost(current);return true;}
  catch(RuntimeException error){surface.failLocalSubmit(lease);reportDiagnostic(SdkDiagnostic.Category.LOCAL_VALIDATION,"submit-frame",surface,lease.generation,false);return false;}
 }
 boolean sendControl(String type,JSONObject json){return sendControlEvent(new ControlEvent(type,json));}
 private boolean sendControlEvent(ControlEvent event){IFaceclawHostSession current=currentHost();if(current==null)return false;try{current.sendControl(event);return true;}catch(RemoteException error){reportDiagnostic(SdkDiagnostic.Category.TRANSPORT_FAILURE,"send-control",null,0,true);transportLost(current);return false;}catch(RuntimeException error){reportDiagnostic(SdkDiagnostic.Category.LOCAL_VALIDATION,"send-control",null,0,false);return false;}}
 void registerResource(ResourceHandle handle,byte[] pixels){IFaceclawHostSession current=currentHost();if(current==null)return;try{current.registerResource(new ResourceRegistration(handle.id,handle.wireType,handle.width,handle.height,handle.sha256,pixels));}catch(RemoteException error){reportDiagnostic(SdkDiagnostic.Category.TRANSPORT_FAILURE,"register-resource",null,0,true);transportLost(current);}catch(RuntimeException error){reportDiagnostic(SdkDiagnostic.Category.LOCAL_VALIDATION,"register-resource",null,0,false);}}
 boolean prefetchResources(List<Integer> ids,boolean replace){JSONArray values=new JSONArray();for(int id:ids)values.put(id);return sendControl("resource-prefetch",Protocol.object("requestId",UUID.randomUUID().toString(),"resourceIds",values,"replace",replace));}
 enum SceneSendResult { SENT, NOT_CONNECTED, LOCAL_FAILURE, UNKNOWN_FAILURE }
 SceneSendResult sendScene(RenderSurface surface,SceneTransaction transaction){
  long generation=surface.generation();IFaceclawHostSession current=currentHost();if(current==null||generation==0)return SceneSendResult.NOT_CONNECTED;
  try{current.commitScene(transaction.wire(surface.id(),generation));return SceneSendResult.SENT;}
  catch(RemoteException error){reportDiagnostic(SdkDiagnostic.Category.TRANSPORT_FAILURE,"commit-scene",surface,generation,true);transportLost(current);return SceneSendResult.UNKNOWN_FAILURE;}
  catch(RuntimeException error){reportDiagnostic(SdkDiagnostic.Category.LOCAL_VALIDATION,"commit-scene",surface,generation,false);return SceneSendResult.LOCAL_FAILURE;}
 }
 boolean commitScene(RenderSurface surface,SceneTransaction transaction){return sendScene(surface,transaction)==SceneSendResult.SENT;}

 public void close(){
  IFaceclawHostSession current;ArrayList<RenderSurface> closing;
  synchronized(this){if(closed)return;closed=true;connected=false;current=host;host=null;closing=new ArrayList<>(surfaces.values());surfaces.clear();}
  for(RenderSurface surface:closing)surface.closeFromSession();
  try{if(current!=null)current.close(new DisconnectInfo(DisconnectInfo.Reason.HOST_STOPPED,false,"Application closed session"));}catch(Exception ignored){}
 }
 void closeSilently(){
  ArrayList<RenderSurface> closing;
  synchronized(this){if(closed)return;closed=true;connected=false;host=null;closing=new ArrayList<>(surfaces.values());surfaces.clear();}
  for(RenderSurface surface:closing)surface.closeFromSession();
 }

 boolean hasResourceWriters(){for(RenderSurface surface:surfaceSnapshot())if(surface.hasWriters())return true;return false;}
 Set<Integer> sceneResources(){Set<Integer> ids=new HashSet<>();for(RenderSurface surface:surfaceSnapshot())ids.addAll(surface.scene().resourceIds());return ids;}
 private synchronized IFaceclawHostSession currentHost(){return !closed&&connected?host:null;}
 private synchronized RenderSurface surface(String id){return closed?null:surfaces.get(id);}
 private synchronized ArrayList<RenderSurface> surfaceSnapshot(){return new ArrayList<>(surfaces.values());}
}
