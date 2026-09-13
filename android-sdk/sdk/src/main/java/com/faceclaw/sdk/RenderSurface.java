package com.faceclaw.sdk;

import android.graphics.*;
import android.os.SharedMemory;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.Executor;

/** A retained host surface backed by a reusable three-slot Gray8 pool. */
public final class RenderSurface implements AutoCloseable {
 private final FaceclawSession session;private final String id;private final SceneController scene;private Slot[] slots=new Slot[0];
 private int width,height;private long generation,nextSequence=1,writeEpoch=2;private boolean visible,screenOn=true,closed;
 private Executor executor;private RasterRenderer rasterRenderer;private CanvasRenderer canvasRenderer;private Bitmap canvasBitmap;private int[] canvasPixels;
 private RenderCredit credit;private InvalidateReason pendingReason=InvalidateReason.STATE;
 private static final class Slot {final int id;final SharedMemory memory;final ByteBuffer mapping;boolean busy;Slot(int id,SharedMemory memory,ByteBuffer mapping){this.id=id;this.memory=memory;this.mapping=mapping;}}
 RenderSurface(FaceclawSession session,String id){this.session=session;this.id=id;this.scene=new SceneController(this);}
 public String id(){return id;}public synchronized int width(){return width;}public synchronized int height(){return height;}public synchronized long generation(){return generation;}public synchronized boolean visible(){return visible&&screenOn&&!closed;}FaceclawSession session(){return session;}
 public synchronized SceneController scene(){return scene;}
 public synchronized void setRasterRenderer(Executor executor,RasterRenderer renderer){this.executor=Objects.requireNonNull(executor);this.rasterRenderer=Objects.requireNonNull(renderer);this.canvasRenderer=null;dispatchIfReady();}
 public synchronized void setCanvasRenderer(Executor executor,CanvasRenderer renderer){this.executor=Objects.requireNonNull(executor);this.canvasRenderer=Objects.requireNonNull(renderer);this.rasterRenderer=null;dispatchIfReady();}
 synchronized boolean hasRenderer(){return rasterRenderer!=null||canvasRenderer!=null;}
 public synchronized void invalidate(InvalidateReason reason){if(closed)return;pendingReason=reason==null?InvalidateReason.STATE:reason;session.requestRender(this,pendingReason);}
 public synchronized void resetContent(ContentMode mode){scene.reset();session.sendControl("surface-reset",Protocol.object("surfaceId",id,"generation",generation,"mode",(mode==null?ContentMode.OPAQUE:mode).name()));invalidate(InvalidateReason.STATE);}
 synchronized void configure(int width,int height,long generation,boolean visible,boolean screenOn){
  if(closed)return;boolean replace=this.width!=width||this.height!=height||this.generation!=generation||slots.length==0;this.width=width;this.height=height;this.generation=generation;this.visible=visible;this.screenOn=screenOn;credit=null;
  if(replace){closePool();openPool();}if(visible&&screenOn)invalidate(InvalidateReason.RECOVERY);
 }
 synchronized void setVisibility(boolean visible,boolean screenOn){this.visible=visible;this.screenOn=screenOn;if(!visible||!screenOn)credit=null;else invalidate(InvalidateReason.STATE);}
 private void openPool(){try{int size=Protocol.frameSize(width,height)+Protocol.FRAME_HEADER_BYTES;slots=new Slot[Protocol.BUFFER_SLOTS];ArrayList<SharedMemory> memories=new ArrayList<>();for(int i=0;i<slots.length;i++){SharedMemory memory=SharedMemory.create("faceclaw-"+safeId()+"-"+i,size);slots[i]=new Slot(i,memory,memory.mapReadWrite());memories.add(memory);}session.registerSurface(this,memories);}catch(Exception error){closePool();throw new IllegalStateException("Unable to allocate render pool",error);}}
 private String safeId(){return id.replaceAll("[^A-Za-z0-9_.-]","_");}
 synchronized void grant(RenderCredit next){if(closed||next.generation!=generation)return;credit=next;dispatchIfReady();}
 private void dispatchIfReady(){if(credit==null||executor==null||(rasterRenderer==null&&canvasRenderer==null)||!visible())return;FrameLease lease=leaseLocked();if(lease==null)return;RenderRequest request=new RenderRequest(this,lease.credit,pendingReason);executor.execute(()->{try{if(rasterRenderer!=null)rasterRenderer.render(lease,request);else renderCanvas(lease,request);}catch(Exception ignored){lease.close();invalidate(InvalidateReason.STATE);}});}
 private void renderCanvas(FrameLease lease,RenderRequest request) throws Exception{synchronized(this){if(canvasBitmap==null||canvasBitmap.getWidth()!=width||canvasBitmap.getHeight()!=height){canvasBitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);canvasPixels=new int[width*height];}}Canvas canvas=new Canvas(canvasBitmap);canvas.drawColor(Color.BLACK);canvasRenderer.render(canvas,request);canvasBitmap.getPixels(canvasPixels,0,width,0,0,width,height);ByteBuffer gray=lease.gray8();for(int value:canvasPixels){int alpha=value>>>24,luminance=(((value>>16)&255)*54+((value>>8)&255)*183+(value&255)*19)>>8;gray.put((byte)(luminance*alpha/255));}lease.submit(FrameMetadata.builder(session.nextClientFrameId(),session.nextContentVersion()).fullDamage(width,height).traceId(request.credit.traceId).requestNextFrame(request.wantsNextFrame()).build());}
 public synchronized boolean submitGray8(ByteBuffer source,FrameMetadata metadata){if(source==null||source.remaining()!=width*height)return false;FrameLease lease=leaseLocked();if(lease==null){invalidate(InvalidateReason.STATE);return false;}lease.gray8().put(source.duplicate());lease.submit(metadata);return true;}
 private FrameLease leaseLocked(){if(credit==null||!visible())return null;for(Slot slot:slots)if(!slot.busy){slot.busy=true;RenderCredit used=credit;credit=null;long sequence=nextSequence++;FrameWire.begin(slot.mapping,writeEpoch);ByteBuffer pixels=FrameWire.pixels(slot.mapping,width*height);pixels.clear();return new FrameLease(this,slot.id,sequence,used,pixels);}return null;}
 synchronized void submit(FrameLease lease,FrameMetadata metadata){Slot slot=slot(lease.slotId);if(slot==null||!slot.busy)return;if(metadata==null)metadata=FrameMetadata.builder(session.nextClientFrameId(),session.nextContentVersion()).fullDamage(width,height).build();writeEpoch+=2;FrameWire.finish(slot.mapping,generation,lease.sequence,metadata.contentVersion,width*height,writeEpoch);session.submitFrame(this,lease,metadata);}
 synchronized void cancel(FrameLease lease){Slot slot=slot(lease.slotId);if(slot!=null)slot.busy=false;invalidate(InvalidateReason.STATE);}
 synchronized void release(int slotId,long sequence){Slot slot=slot(slotId);if(slot!=null)slot.busy=false;dispatchIfReady();}
 synchronized void suspend(){credit=null;for(Slot slot:slots)slot.busy=false;}
 synchronized void consumeSceneCredit(){credit=null;}
 synchronized void replayScene(){scene.replay();}
 private Slot slot(int id){return id>=0&&id<slots.length?slots[id]:null;}
 @Override public synchronized void close(){if(closed)return;closed=true;session.unregisterSurface(this);closePool();credit=null;canvasBitmap=null;canvasPixels=null;}
 synchronized void closeFromSession(){closed=true;closePool();credit=null;}
 private void closePool(){for(Slot slot:slots)if(slot!=null){try{SharedMemory.unmap(slot.mapping);}catch(Exception ignored){}try{slot.memory.close();}catch(Exception ignored){}}slots=new Slot[0];}
}
