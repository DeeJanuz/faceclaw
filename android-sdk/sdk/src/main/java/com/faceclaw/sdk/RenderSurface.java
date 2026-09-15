package com.faceclaw.sdk;

import android.graphics.*;
import android.os.SharedMemory;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.Executor;

/** A retained host surface backed by a reusable three-slot Gray8 pool. */
public final class RenderSurface implements AutoCloseable {
 private static final int MAX_RETIRED_WRITER_SLOTS=3;
 private final FaceclawSession session;
 private final String id;
 private final SceneController scene;
 private final Object canvasLock=new Object();
 private Slot[] slots=new Slot[0];
 private final ArrayList<Slot> retiredSlots=new ArrayList<>();
 private int width,height;
 private long generation,nextSequence=1,writeEpoch=2;
 private boolean visible,screenOn=true,closed,poolDeferred,sceneReplayPending;
 private final RenderFailureGate renderGate=new RenderFailureGate();
 private Executor executor;
 private RasterRenderer rasterRenderer;
 private CanvasRenderer canvasRenderer;
 private Bitmap canvasBitmap;
 private int[] canvasPixels;
 private RenderCredit credit;
 private InvalidateReason pendingReason=InvalidateReason.STATE;

 private static final class Slot {
  final long generation;
  final int id;
  final SharedMemory memory;
  final ByteBuffer mapping;
  long sequence;
  boolean busy,submitted,retired,closed;
  Slot(long generation,int id,SharedMemory memory,ByteBuffer mapping){this.generation=generation;this.id=id;this.memory=memory;this.mapping=mapping;}
 }

 RenderSurface(FaceclawSession session,String id){this.session=session;this.id=id;this.scene=new SceneController(this);}
 public String id(){return id;}
 public synchronized int width(){return width;}
 public synchronized int height(){return height;}
 public synchronized long generation(){return generation;}
 public synchronized boolean visible(){return visible&&screenOn&&!closed;}
 FaceclawSession session(){return session;}
 public SceneController scene(){return scene;}
 public synchronized boolean hasRenderCredit(){return credit!=null&&visible();}

 public void setRasterRenderer(Executor nextExecutor,RasterRenderer renderer){
  synchronized(this){executor=Objects.requireNonNull(nextExecutor);rasterRenderer=Objects.requireNonNull(renderer);canvasRenderer=null;}
  dispatchIfReady();
 }
 public void setCanvasRenderer(Executor nextExecutor,CanvasRenderer renderer){
  synchronized(this){executor=Objects.requireNonNull(nextExecutor);canvasRenderer=Objects.requireNonNull(renderer);rasterRenderer=null;}
  dispatchIfReady();
 }
 synchronized boolean hasWriters(){for(Slot slot:slots)if(slot.busy)return true;return !retiredSlots.isEmpty();}
 synchronized boolean hasRenderer(){return rasterRenderer!=null||canvasRenderer!=null;}

 /** Explicit recovery after a renderer/executor failure. */
 public void recoverRenderer(){renderGate.recover();invalidate(InvalidateReason.RECOVERY);}
 public boolean rendererSuspended(){return renderGate.suspended();}
 public void invalidate(InvalidateReason reason){
  InvalidateReason next;
  synchronized(this){if(closed||renderGate.suspended())return;pendingReason=reason==null?InvalidateReason.STATE:reason;next=pendingReason;}
  session.requestRender(this,next);
 }
 public void resetContent(ContentMode mode){
  scene.reset();
  long currentGeneration;
  synchronized(this){if(closed)return;currentGeneration=generation;}
  session.sendControl("surface-reset",Protocol.object("surfaceId",id,"generation",currentGeneration,"mode",(mode==null?ContentMode.OPAQUE:mode).name()));
  invalidate(InvalidateReason.STATE);
 }

 void configure(int nextWidth,int nextHeight,long nextGeneration,boolean nextVisible,boolean nextScreenOn){
  ArrayList<SharedMemory> memories=null;
  synchronized(this){
   if(closed)return;
   boolean replace=width!=nextWidth||height!=nextHeight||generation!=nextGeneration||slots.length==0;
   width=nextWidth;height=nextHeight;generation=nextGeneration;visible=nextVisible;screenOn=nextScreenOn;credit=null;
   if(replace){retirePoolLocked();if(retiredSlots.size()<MAX_RETIRED_WRITER_SLOTS)memories=openPoolLocked();else poolDeferred=true;}
  }
  if(memories!=null)session.registerSurface(this,nextGeneration,nextWidth,nextHeight,memories);
  if(nextVisible&&nextScreenOn)invalidate(InvalidateReason.RECOVERY);
 }
 void setVisibility(boolean nextVisible,boolean nextScreenOn){
  boolean wake;
  synchronized(this){visible=nextVisible;screenOn=nextScreenOn;if(!visible||!screenOn)credit=null;wake=visible&&screenOn&&!closed;}
  if(wake)invalidate(InvalidateReason.STATE);
 }

 private ArrayList<SharedMemory> openPoolLocked(){
  ArrayList<Slot> created=new ArrayList<>();
  try{
   int size=Protocol.frameSize(width,height)+Protocol.FRAME_HEADER_BYTES;
   for(int i=0;i<Protocol.BUFFER_SLOTS;i++){
    SharedMemory memory=SharedMemory.create("faceclaw-"+safeId()+"-"+generation+"-"+i,size);
    try{created.add(new Slot(generation,i,memory,memory.mapReadWrite()));}
    catch(Exception error){try{memory.close();}catch(Exception ignored){}throw new IllegalStateException("Unable to map render slot",error);}
   }
   slots=created.toArray(new Slot[0]);
   poolDeferred=false;
   ArrayList<SharedMemory> memories=new ArrayList<>();for(Slot slot:slots)memories.add(slot.memory);
   return memories;
  }catch(Exception error){for(Slot slot:created)closeSlotLocked(slot);slots=new Slot[0];throw new IllegalStateException("Unable to allocate render pool",error);}
 }
 private String safeId(){return id.replaceAll("[^A-Za-z0-9_.-]","_");}

 void grant(RenderCredit next){
  boolean replay;
  synchronized(this){if(closed||next.generation!=generation)return;credit=next;replay=sceneReplayPending;if(replay)sceneReplayPending=false;}
  if(!replay||!scene.replay())dispatchIfReady();
 }
 private void dispatchIfReady(){
  final Executor target;
  final RasterRenderer raster;
  final CanvasRenderer canvas;
  final FrameLease lease;
  final RenderRequest request;
  synchronized(this){
   if(renderGate.suspended()||credit==null||executor==null||(rasterRenderer==null&&canvasRenderer==null)||!visible())return;
   lease=leaseLocked();if(lease==null)return;
   target=executor;raster=rasterRenderer;canvas=canvasRenderer;request=new RenderRequest(this,lease.credit,pendingReason);
  }
  renderGate.dispatch(target,()->{if(raster!=null)raster.render(lease,request);else renderCanvas(canvas,lease,request);},lease::close,
   rejected->session.reportDiagnostic(rejected?SdkDiagnostic.Category.EXECUTOR_REJECTED:SdkDiagnostic.Category.RENDERER_FAILURE,"render",this,lease.generation,true));
 }
 private void renderCanvas(CanvasRenderer renderer,FrameLease lease,RenderRequest request)throws Exception{
  synchronized(canvasLock){
   if(canvasBitmap==null||canvasBitmap.getWidth()!=lease.width||canvasBitmap.getHeight()!=lease.height){canvasBitmap=Bitmap.createBitmap(lease.width,lease.height,Bitmap.Config.ARGB_8888);canvasPixels=new int[lease.width*lease.height];}
   Canvas canvas=new Canvas(canvasBitmap);canvas.drawColor(Color.BLACK);renderer.render(canvas,request);
   canvasBitmap.getPixels(canvasPixels,0,lease.width,0,0,lease.width,lease.height);
   ByteBuffer gray=lease.gray8();for(int value:canvasPixels){int alpha=value>>>24,luminance=(((value>>16)&255)*54+((value>>8)&255)*183+(value&255)*19)>>8;gray.put((byte)(luminance*alpha/255));}
  }
  lease.submit(FrameMetadata.builder(session.nextClientFrameId(),session.nextContentVersion()).fullDamage(lease.width,lease.height).traceId(request.credit.traceId).requestNextFrame(request.wantsNextFrame()).build());
 }
 public boolean submitGray8(ByteBuffer source,FrameMetadata metadata){
  FrameLease lease;
  synchronized(this){lease=leaseLocked();}
  if(lease==null){session.reportDiagnostic(SdkDiagnostic.Category.LOCAL_VALIDATION,"submit-gray8",this,generation,false);invalidate(InvalidateReason.STATE);return false;}
  if(source==null||source.remaining()!=lease.width*lease.height){lease.close();session.reportDiagnostic(SdkDiagnostic.Category.LOCAL_VALIDATION,"submit-gray8",this,lease.generation,false);return false;}
  lease.gray8().put(source.duplicate());lease.submit(metadata);return true;
 }
 private FrameLease leaseLocked(){
  if(credit==null||!visible())return null;
  for(Slot slot:slots)if(!slot.busy){
   slot.busy=true;RenderCredit used=credit;credit=null;long sequence=nextSequence++;slot.sequence=sequence;
   FrameWire.begin(slot.mapping,writeEpoch);ByteBuffer pixels=FrameWire.pixels(slot.mapping,width*height);pixels.clear();
   return new FrameLease(this,generation,slot.id,sequence,width,height,used,pixels);
  }
  return null;
 }
 void submit(FrameLease lease,FrameMetadata metadata){
  FrameMetadata wire=metadata;
  if(wire==null)wire=FrameMetadata.builder(session.nextClientFrameId(),session.nextContentVersion()).fullDamage(lease.width,lease.height).build();
  boolean currentLease;
  synchronized(this){
   Slot slot=findLeaseSlotLocked(lease.generation,lease.slotId,lease.sequence);
   if(slot==null)return;
   currentLease=!closed&&lease.generation==generation;
   if(!currentLease){releaseSlotLocked(slot);}
   else{writeEpoch+=2;FrameWire.finish(slot.mapping,lease.generation,lease.sequence,wire.contentVersion,lease.width*lease.height,writeEpoch);slot.submitted=true;}
  }
  if(!currentLease){maybeOpenDeferredPool();return;}
  session.submitFrame(this,lease,wire);
 }
 void cancel(FrameLease lease){
  boolean retry=false;
  synchronized(this){Slot slot=findLeaseSlotLocked(lease.generation,lease.slotId,lease.sequence);if(slot!=null){releaseSlotLocked(slot);retry=!closed&&lease.generation==generation&&visible&&screenOn;}}
  maybeOpenDeferredPool();
  if(retry)invalidate(InvalidateReason.STATE);
 }
 void release(long releasedGeneration,int slotId,long sequence){
  synchronized(this){Slot slot=findLeaseSlotLocked(releasedGeneration,slotId,sequence);if(slot!=null)releaseSlotLocked(slot);}
  maybeOpenDeferredPool();
  dispatchIfReady();
 }
 synchronized void suspend(){credit=null;retirePoolLocked();}
 synchronized void consumeSceneCredit(){credit=null;}
 void replayScene(){synchronized(this){if(!closed)sceneReplayPending=true;}}

 private Slot findLeaseSlotLocked(long leaseGeneration,int slotId,long sequence){
  if(leaseGeneration==generation&&slotId>=0&&slotId<slots.length){Slot slot=slots[slotId];if(slot.busy&&slot.sequence==sequence)return slot;}
  for(Slot slot:retiredSlots)if(slot.generation==leaseGeneration&&slot.id==slotId&&slot.busy&&slot.sequence==sequence)return slot;
  return null;
 }
 private void releaseSlotLocked(Slot slot){slot.busy=false;slot.submitted=false;if(slot.retired){retiredSlots.remove(slot);closeSlotLocked(slot);}}
 private void retirePoolLocked(){
  for(Slot slot:slots)if(slot!=null){if(slot.busy&&!slot.submitted){slot.retired=true;retiredSlots.add(slot);}else closeSlotLocked(slot);}
  slots=new Slot[0];
 }
 private void closeSlotLocked(Slot slot){
  if(slot.closed)return;slot.closed=true;
  try{SharedMemory.unmap(slot.mapping);}catch(Exception ignored){}
  try{slot.memory.close();}catch(Exception ignored){}
 }
 private void maybeOpenDeferredPool(){
  ArrayList<SharedMemory> memories=null;long nextGeneration=0;int nextWidth=0,nextHeight=0;
  synchronized(this){if(poolDeferred&&!closed&&slots.length==0&&retiredSlots.size()<MAX_RETIRED_WRITER_SLOTS){memories=openPoolLocked();nextGeneration=generation;nextWidth=width;nextHeight=height;}}
  if(memories!=null)session.registerSurface(this,nextGeneration,nextWidth,nextHeight,memories);
 }

 @Override public void close(){
  long closingGeneration;
  synchronized(this){if(closed)return;closed=true;closingGeneration=generation;retirePoolLocked();credit=null;canvasBitmap=null;canvasPixels=null;}
  session.unregisterSurface(this,closingGeneration);
 }
 void closeFromSession(){synchronized(this){if(closed)return;closed=true;retirePoolLocked();credit=null;canvasBitmap=null;canvasPixels=null;}}
}
