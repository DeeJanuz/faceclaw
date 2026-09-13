package com.faceclaw.sdk;

import android.os.*;
import com.faceclaw.sdk.ipc.IFaceclawHostSession;
import org.json.JSONObject;
import java.util.*;

/** Authenticated application-side session. Thread-safe; callbacks are marshalled by the service. */
public final class FaceclawSession {
 interface Callback {void onSnapshot(HostSnapshot snapshot);void onControl(ControlEvent event);void onInput(RenderSurface surface,FaceclawInputEvent event);void onCreditWithoutRenderer(RenderSurface surface,RenderCredit credit);void onOutcome(FrameOutcome outcome);}
 private IFaceclawHostSession host;private final Callback callback;private final Map<String,RenderSurface> surfaces=new HashMap<>();private final ResourceRegistry resources;private long nextFrame=1,nextContent=1;private boolean closed,connected=true;
 FaceclawSession(IFaceclawHostSession host,Callback callback){this.host=host;this.callback=callback;resources=new ResourceRegistry(this);surfaces.put("window",new RenderSurface(this,"window"));}
 public synchronized RenderSurface windowSurface(){return surfaces.get("window");}
 public synchronized RenderSurface extensionSurface(String feature){return surfaces.computeIfAbsent("extension:"+feature,id->new RenderSurface(this,id));}
 public ResourceRegistry resources(){return resources;}
 synchronized void invalidateAll(){for(RenderSurface surface:surfaces.values())surface.invalidate(InvalidateReason.STATE);}
 public synchronized long nextClientFrameId(){return nextFrame++;}public synchronized long nextContentVersion(){return nextContent++;}
 public synchronized void setRestorationToken(byte[] value){if(value==null||value.length>4096)throw new IllegalArgumentException("Restoration token exceeds 4 KiB");sendControlEvent(new ControlEvent("restoration-token",new JSONObject(),value));}
 synchronized void attach(IFaceclawHostSession next){if(closed)throw new IllegalStateException("Session is closed");host=next;connected=true;}
 synchronized void detach(){if(closed)return;connected=false;host=null;for(RenderSurface surface:surfaces.values())surface.suspend();}
 synchronized void applySnapshot(HostSnapshot snapshot){if(closed||!connected||snapshot==null)return;if(snapshot.protocolMajor!=Protocol.VERSION){close();return;}if(snapshot.surfaces.isEmpty()){RenderSurface window=surfaces.get("window");if(snapshot.windowOpen)window.configure(snapshot.windowWidth,snapshot.windowHeight,snapshot.windowGeneration,snapshot.windowVisible,snapshot.screenOn);}else for(SurfaceSnapshot state:snapshot.surfaces){RenderSurface surface="window".equals(state.id)?surfaces.get("window"):state.id.startsWith("extension:")?extensionSurface(state.id.substring(10)):null;if(surface!=null)surface.configure(state.width,state.height,state.generation,state.visible,state.screenOn);}callback.onSnapshot(snapshot);resources.replay();for(RenderSurface surface:surfaces.values())surface.replayScene();}
 synchronized void applyControl(ControlEvent event){if(closed||event==null)return;String type=event.type;JSONObject data=event.data;
  try{
   if(type.equals("open")||type.equals("resize")){RenderSurface window=surfaces.get("window");window.configure(data.getInt("width"),data.getInt("height"),data.getLong("generation"),data.optBoolean("visible",window.visible()),data.optBoolean("screenOn",true));window.replayScene();}
   else if(type.equals("visibility"))surfaces.get("window").setVisibility(data.optBoolean("visible"),data.optBoolean("screenOn",true));
   else if(type.equals("close"))surfaces.get("window").setVisibility(false,false);
   else if(type.equals("extension-surface")){String feature=data.getString("feature");RenderSurface surface=extensionSurface(feature);String action=data.getString("type");if(action.equals("open")||action.equals("resize")){surface.configure(data.getInt("width"),data.getInt("height"),data.getLong("generation"),data.optBoolean("visible",true),data.optBoolean("screenOn",true));surface.replayScene();}else if(action.equals("visibility"))surface.setVisibility(data.optBoolean("visible"),data.optBoolean("screenOn",true));else if(action.equals("close")){surface.close();surfaces.remove("extension:"+feature);}}
   callback.onControl(event);
  }catch(Exception ignored){}
 }
 synchronized void applyInput(String surfaceId,FaceclawInputEvent event){if(closed||event==null)return;RenderSurface surface=surfaces.get(surfaceId);if(surface!=null)callback.onInput(surface,event);}
 synchronized void grant(RenderCredit credit){if(credit==null)return;RenderSurface surface=surfaces.get(credit.surfaceId);if(surface==null)return;boolean notify=!surface.hasRenderer();surface.grant(credit);if(notify)callback.onCreditWithoutRenderer(surface,credit);}
 synchronized void released(String id,long generation,int slot,long sequence){RenderSurface surface=surfaces.get(id);if(surface!=null&&surface.generation()==generation)surface.release(slot,sequence);}
 synchronized void outcome(FrameOutcome value){if(value!=null)callback.onOutcome(value);}
 synchronized void registerSurface(RenderSurface surface,ArrayList<SharedMemory> memories){IFaceclawHostSession current=host;if(!connected||current==null)return;try{current.registerSurface(new SurfaceRegistration(surface.id(),surface.generation(),surface.width(),surface.height(),memories));}catch(RemoteException e){detach();}}
 synchronized void unregisterSurface(RenderSurface surface){IFaceclawHostSession current=host;if(!connected||current==null)return;try{current.unregisterSurface(surface.id(),surface.generation());}catch(RemoteException ignored){detach();}}
 synchronized void requestRender(RenderSurface surface,InvalidateReason reason){IFaceclawHostSession current=host;if(closed||!connected||current==null)return;try{current.requestRender(surface.id(),surface.generation(),reason.ordinal());}catch(RemoteException e){detach();}}
 synchronized void submitFrame(RenderSurface surface,FrameLease lease,FrameMetadata metadata){IFaceclawHostSession current=host;if(!connected||current==null){surface.release(lease.slotId,lease.sequence);return;}try{List<DamageRect> damage=metadata.damage.isEmpty()?Collections.singletonList(new DamageRect(0,0,surface.width(),surface.height())):metadata.damage;int[] rects=new int[damage.size()*4];for(int i=0;i<damage.size();i++){DamageRect r=damage.get(i);rects[i*4]=r.x;rects[i*4+1]=r.y;rects[i*4+2]=r.width;rects[i*4+3]=r.height;}current.submitFrame(new FrameSubmission(surface.id(),surface.generation(),lease.slotId,lease.sequence,lease.credit.creditId,metadata.clientFrameId,metadata.contentVersion,metadata.requestNextFrame,metadata.traceId,rects,metadata.draws==null?null:metadata.draws.encode()));}catch(Exception e){surface.release(lease.slotId,lease.sequence);detach();}}
 synchronized boolean sendControl(String type,JSONObject json){return sendControlEvent(new ControlEvent(type,json));}
 private synchronized boolean sendControlEvent(ControlEvent event){IFaceclawHostSession current=host;if(closed||!connected||current==null)return false;try{current.sendControl(event);return true;}catch(Exception e){detach();return false;}}
 synchronized void registerResource(ResourceHandle handle,byte[] pixels){IFaceclawHostSession current=host;if(!connected||current==null)return;try{current.registerResource(new ResourceRegistration(handle.id,handle.type.name(),handle.width,handle.height,handle.sha256,pixels));}catch(Exception e){detach();}}
 synchronized boolean commitScene(RenderSurface surface,SceneTransaction transaction){IFaceclawHostSession current=host;if(closed||!connected||current==null||surface.generation()==0)return false;try{current.commitScene(transaction.wire(surface.id(),surface.generation()));return true;}catch(Exception e){detach();return false;}}
 public synchronized void close(){if(closed)return;closed=true;connected=false;IFaceclawHostSession current=host;host=null;for(RenderSurface surface:surfaces.values())surface.closeFromSession();surfaces.clear();try{if(current!=null)current.close(new DisconnectInfo(DisconnectInfo.Reason.HOST_STOPPED,false,"Application closed session"));}catch(Exception ignored){}}
 synchronized void closeSilently(){if(closed)return;closed=true;connected=false;host=null;for(RenderSurface surface:surfaces.values())surface.closeFromSession();surfaces.clear();}
}
