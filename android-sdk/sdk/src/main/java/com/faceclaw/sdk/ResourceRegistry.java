package com.faceclaw.sdk;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;

/** Session-scoped immutable resource registry. Registration is content-addressed. */
public final class ResourceRegistry {
 private static final class Entry {final ResourceHandle handle;final byte[] pixels;boolean released,sent;Entry(ResourceHandle handle,byte[] pixels){this.handle=handle;this.pixels=pixels;}}
 private final java.util.function.BiConsumer<ResourceHandle,byte[]> registration;
 private final java.util.function.IntPredicate releaseRequest;
 private final java.util.function.BooleanSupplier supported,writers;
 private final java.util.function.Supplier<Set<Integer>> references;
 private final Runnable fallback; private final Map<String,Entry> byHash=new HashMap<>(); private int nextId=1,totalBytes; private boolean quotaExceeded;
 ResourceRegistry(FaceclawSession session){this(session::registerResource,id->session.sendControl("resource-release",Protocol.object("resourceId",id)),()->session.controls().supports("resource.release"),session::hasResourceWriters,session::sceneResources,session::invalidateAll);}
 ResourceRegistry(java.util.function.BiConsumer<ResourceHandle,byte[]> registration,java.util.function.IntPredicate releaseRequest,java.util.function.BooleanSupplier supported,java.util.function.BooleanSupplier writers,java.util.function.Supplier<Set<Integer>> references,Runnable fallback){this.registration=registration;this.releaseRequest=releaseRequest;this.supported=supported;this.writers=writers;this.references=references;this.fallback=fallback;}
 public synchronized ResourceHandle registerGrayImage(int width,int height,ByteBuffer gray8){return register(ResourceHandle.Type.IMAGE,width,height,gray8);}
 public synchronized ResourceHandle registerGlyph(int width,int height,ByteBuffer gray8){return register(ResourceHandle.Type.GLYPH,width,height,gray8);}
 private ResourceHandle register(ResourceHandle.Type type,int width,int height,ByteBuffer source){
  int size=Protocol.frameSize(width,height);if(width>255||height>255||source==null||source.remaining()!=size)throw new IllegalArgumentException("Invalid resource");
  byte[] pixels=new byte[size];source.duplicate().get(pixels);String hash=sha256(type,width,height,pixels);Entry existing=byHash.get(hash);if(existing!=null&&!existing.released)return existing.handle;
  if(existing!=null)throw new IllegalStateException("Released content is still retained; use raster fallback until disposal");
  if(quotaExceeded||byHash.size()>=Protocol.MAX_RESOURCES||totalBytes+size>Protocol.MAX_RESOURCE_BYTES){
   // Quota exhaustion is stable for this session. Re-invalidating every
   // surface for every rejected glyph would create a render retry loop.
   if(!quotaExceeded){quotaExceeded=true;fallback.run();}
   throw new IllegalStateException("Resource quota exceeded; raster fallback requested");
  }
  if(nextId==Integer.MAX_VALUE)throw new IllegalStateException("Resource ID space exhausted");
  ResourceHandle handle=new ResourceHandle(nextId++,width,height,hash,type);handle.onRelease(()->release(handle));byHash.put(hash,new Entry(handle,pixels));totalBytes+=size;registration.accept(handle,pixels);return handle;
 }
 private static String sha256(ResourceHandle.Type type,int width,int height,byte[] pixels){try{
  MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update((byte)type.ordinal());digest.update((byte)(width>>8));digest.update((byte)width);digest.update((byte)(height>>8));digest.update((byte)height);byte[] value=digest.digest(pixels);StringBuilder out=new StringBuilder(64);for(byte b:value)out.append(String.format(Locale.US,"%02x",b));return out.toString();
 }catch(Exception impossible){throw new IllegalStateException(impossible);}}
 synchronized void replay(){List<Entry> ordered=new ArrayList<>(byHash.values());ordered.sort(Comparator.comparingInt(e->e.handle.id));for(Entry entry:ordered){entry.sent=false;registration.accept(entry.handle,entry.pixels);}}
 public synchronized int residentBytes(){return totalBytes;}
 public synchronized int residentCount(){return byHash.size();}
 public synchronized boolean releaseSupported(){return supported.getAsBoolean();}
 public void release(ResourceHandle handle){synchronized(this){Entry e=byHash.get(handle.sha256);if(e==null||e.handle!=handle)return;e.released=true;}collect();}
 void collect(){
  if(!releaseSupported()||writers.getAsBoolean())return;
  Set<Integer> refs=references.get();List<Entry> sending=new ArrayList<>();
  synchronized(this){for(Entry e:byHash.values())if(e.released&&!e.sent&&!refs.contains(e.handle.id)){e.sent=true;sending.add(e);}}
  for(Entry e:sending)if(!releaseRequest.test(e.handle.id))synchronized(this){e.sent=false;}
 }
 synchronized void released(int id,String status){
  if(!status.equals("released"))return;
  for(Iterator<Map.Entry<String,Entry>> it=byHash.entrySet().iterator();it.hasNext();){Entry e=it.next().getValue();if(e.handle.id==id&&e.released){totalBytes-=e.pixels.length;it.remove();quotaExceeded=false;return;}}
 }
}
