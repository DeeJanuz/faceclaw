package com.faceclaw.sdk.fixture;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.*;
import com.faceclaw.sdk.*;
import com.faceclaw.sdk.ipc.*;
import java.nio.ByteBuffer;
import java.util.*;

/** Deliberately bypasses the SDK to exercise the typed protocol's validation boundary. */
public final class AdversarialService extends Service {
 private IFaceclawHostSession host;private String session="",pendingAttack="";private long generation,extensionGeneration,sequence,creditId,creditGeneration;private String creditSurface="window";private int width=32,height=16;private SharedMemory[] memories;private ByteBuffer[] mappings;
 private final IFaceclawAppEndpoint.Stub endpoint=new IFaceclawAppEndpoint.Stub(){@Override public void connect(Bundle hello,IFaceclawHostSession remote){host=remote;session=hello.getString("session","");try{Bundle ready=new Bundle();ready.putInt("protocolMajor",Protocol.VERSION);ready.putString("sdkVersion","adversarial");ready.putString("session",session);remote.onReady(ready,appSession);}catch(Exception ignored){}}};
 private final IFaceclawAppSession.Stub appSession=new IFaceclawAppSession.Stub(){
  @Override public void applyHostSnapshot(Bundle snapshot){}
  @Override public void grantRenderCredit(Bundle credit){creditSurface=credit.getString("surfaceId","window");creditGeneration=credit.getLong("generation");creditId=credit.getLong("creditId");String attack=pendingAttack;pendingAttack="";if(!attack.isEmpty())runAttack(attack);}
  @Override public void onBufferReleased(String id,long generation,int slot,long submittedSequence){}
  @Override public void onFrameOutcome(Bundle outcome){}
  @Override public void sendControl(Bundle event){try{String type=event.getString("type","");org.json.JSONObject data=Protocol.json(event);if(type.equals("open")||type.equals("resize")){generation=data.getLong("generation");width=data.getInt("width");height=data.getInt("height");openPool("window",generation,width,height);}else if(type.equals("extension-surface")&&(data.optString("type").equals("open")||data.optString("type").equals("resize"))){extensionGeneration=data.getLong("generation");width=data.getInt("width");height=data.getInt("height");openPool("extension:"+data.getString("feature"),extensionGeneration,width,height);}else if(type.equals("fixture"))runAttack(data.getString("attack"));}catch(Exception ignored){}}
  @Override public void close(Bundle reason){closePool();}
 };
 @Override public IBinder onBind(Intent intent){return endpoint;}
 @Override public void onDestroy(){closePool();super.onDestroy();}
 private synchronized void openPool(String id,long poolGeneration,int poolWidth,int poolHeight)throws Exception{closePool();int size=Protocol.FRAME_HEADER_BYTES+Protocol.frameSize(poolWidth,poolHeight);memories=new SharedMemory[Protocol.BUFFER_SLOTS];mappings=new ByteBuffer[Protocol.BUFFER_SLOTS];ArrayList<SharedMemory> wire=new ArrayList<>();for(int i=0;i<memories.length;i++){memories[i]=SharedMemory.create("adversarial-"+i,size);mappings[i]=memories[i].mapReadWrite();wire.add(memories[i]);}Bundle registration=new Bundle();registration.putString("surfaceId",id);registration.putLong("generation",poolGeneration);registration.putInt("width",poolWidth);registration.putInt("height",poolHeight);registration.putParcelableArrayList("buffers",wire);host.registerSurface(registration);host.requestRender(id,poolGeneration,InvalidateReason.STATE.ordinal());}
 private synchronized void closePool(){if(mappings!=null)for(ByteBuffer mapping:mappings)try{SharedMemory.unmap(mapping);}catch(Exception ignored){}if(memories!=null)for(SharedMemory memory:memories)try{memory.close();}catch(Exception ignored){}mappings=null;memories=null;}
 private void runAttack(String attack){try{
  if(attack.equals("publish-launcher")){host.sendControl(Protocol.control("publish-extensions",Protocol.object("declarations",new org.json.JSONArray().put(Protocol.object("feature","ui.launcher","enabled",true,"configuration",new org.json.JSONObject())))));return;}
  if(attack.equals("disconnect")){host.sendControl(Protocol.control("disconnected",new org.json.JSONObject()));return;}
  if(attack.equals("notification")){host.sendControl(Protocol.control("notification",Protocol.object("id","x","target","synthetic","title","Synthetic","text","Synthetic")));return;}
  if(attack.equals("system-menu-valid")){host.sendControl(Protocol.control("request-system-menu",new org.json.JSONObject()));return;}
  if(attack.equals("system-menu-stale"))return;
  if(attack.equals("consent-broadcast")){PendingIntent pi=PendingIntent.getBroadcast(this,0,new Intent("com.faceclaw.fixture.NO_ACTION").setPackage(getPackageName()),PendingIntent.FLAG_IMMUTABLE);Bundle consent=new Bundle();consent.putParcelable("consent",pi);consent.putString("session",session);host.onConsentRequired(consent);return;}
  pendingAttack=attack;String wanted=attack.startsWith("extension")?"extension:ui.launcher":"window";long wantedGeneration=attack.startsWith("extension")?extensionGeneration:generation;if(creditId==0||!wanted.equals(creditSurface)||creditGeneration!=wantedGeneration){host.requestRender(wanted,wantedGeneration,InvalidateReason.STATE.ordinal());return;}sendFrame(attack,wanted,wantedGeneration);
 }catch(Exception ignored){}}
 private void sendFrame(String attack,String id,long wantedGeneration)throws Exception{if(mappings==null)return;int count=attack.endsWith("burst")?4:1;for(int item=1;item<=count;item++){ByteBuffer mapping=mappings[0];long submitted=++sequence;FrameWire.begin(mapping,submitted*2);ByteBuffer pixels=FrameWire.pixels(mapping,width*height);while(pixels.hasRemaining())pixels.put((byte)(attack.endsWith("memory")?7:item));FrameWire.finish(mapping,wantedGeneration,submitted,submitted,width*height,submitted*2);Bundle frame=new Bundle();frame.putString("surfaceId",id);frame.putLong("generation",attack.equals("generation")?wantedGeneration-1:wantedGeneration);frame.putInt("slotId",0);frame.putLong("sequence",submitted);frame.putLong("creditId",creditId);frame.putLong("clientFrameId",submitted);frame.putLong("contentVersion",submitted);frame.putString("traceId","adversarial-"+attack);frame.putIntArray("damage",attack.equals("length")?new int[]{0,0,width+1,height}:new int[]{0,0,width,height});host.submitFrame(frame);if(attack.endsWith("memory")){ByteBuffer raced=FrameWire.pixels(mapping,width*height);while(raced.hasRemaining())raced.put((byte)200);}}
  creditId=0;
 }
}
