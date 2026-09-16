package com.faceclaw.sdk;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;

/** Session-scoped immutable resource registry. Registration is content-addressed. */
public final class ResourceRegistry {
 private static final class Entry {final ResourceHandle handle;final byte[] pixels;boolean released,sent;Entry(ResourceHandle handle,byte[] pixels){this.handle=handle;this.pixels=pixels;}}
 private final java.util.function.BiConsumer<ResourceHandle,byte[]> registration;
 private final java.util.function.IntPredicate releaseRequest;
 private final java.util.function.BiFunction<List<Integer>,Boolean,Boolean> prefetchRequest;
 private final java.util.function.BooleanSupplier supported,prefetchSupported,writers;
 private final java.util.function.Supplier<Set<Integer>> references;
 private final Runnable fallback; private final Map<String,Entry> byHash=new HashMap<>(); private int nextId=1,totalBytes; private boolean quotaExceeded;
 ResourceRegistry(FaceclawSession session){this(session::registerResource,id->session.sendControl("resource-release",Protocol.object("resourceId",id)),()->session.controls().supports("resource.release"),session::prefetchResources,()->session.controls().supports("resource.prefetch"),session::hasResourceWriters,session::sceneResources,session::invalidateAll);}
 ResourceRegistry(java.util.function.BiConsumer<ResourceHandle,byte[]> registration,java.util.function.IntPredicate releaseRequest,java.util.function.BooleanSupplier supported,java.util.function.BooleanSupplier writers,java.util.function.Supplier<Set<Integer>> references,Runnable fallback){this(registration,releaseRequest,supported,(ids,replace)->false,()->false,writers,references,fallback);}
 ResourceRegistry(java.util.function.BiConsumer<ResourceHandle,byte[]> registration,java.util.function.IntPredicate releaseRequest,java.util.function.BooleanSupplier supported,java.util.function.BiFunction<List<Integer>,Boolean,Boolean> prefetchRequest,java.util.function.BooleanSupplier prefetchSupported,java.util.function.BooleanSupplier writers,java.util.function.Supplier<Set<Integer>> references,Runnable fallback){this.registration=registration;this.releaseRequest=releaseRequest;this.supported=supported;this.prefetchRequest=prefetchRequest;this.prefetchSupported=prefetchSupported;this.writers=writers;this.references=references;this.fallback=fallback;}
 public synchronized ResourceHandle registerGrayImage(int width,int height,ByteBuffer gray8){return register(ResourceHandle.Type.IMAGE,"IMAGE",width,height,gray8);}
 public synchronized ResourceHandle registerGlyph(int width,int height,ByteBuffer gray8){return register(ResourceHandle.Type.GLYPH,"GLYPH",width,height,gray8);}
 /** Registers one printable glyph in a shared firmware font table. */
 public synchronized ResourceHandle registerGlyph(String fontKey,int encoding,int width,int height,ByteBuffer gray8){if(fontKey==null||!fontKey.matches("[A-Za-z0-9_.:-]{1,64}")||encoding<32||encoding>127)throw new IllegalArgumentException("Invalid glyph identity");return register(ResourceHandle.Type.GLYPH,"GLYPH/"+encoding+"/"+fontKey,width,height,gray8);}
 private ResourceHandle register(ResourceHandle.Type type,String wireType,int width,int height,ByteBuffer source){
  int size=Protocol.frameSize(width,height);if(width>255||height>255||source==null||source.remaining()!=size)throw new IllegalArgumentException("Invalid resource");
  byte[] pixels=new byte[size];source.duplicate().get(pixels);String hash=sha256(type,width,height,pixels),key=hash+"|"+wireType;Entry existing=byHash.get(key);if(existing!=null&&!existing.released)return existing.handle;
  if(existing!=null)throw new IllegalStateException("Released content is still retained; use raster fallback until disposal");
  if(quotaExceeded||byHash.size()>=Protocol.MAX_RESOURCES||totalBytes+size>Protocol.MAX_RESOURCE_BYTES){
   // Quota exhaustion is stable for this session. Re-invalidating every
   // surface for every rejected glyph would create a render retry loop.
   if(!quotaExceeded){quotaExceeded=true;fallback.run();}
   throw new IllegalStateException("Resource quota exceeded; raster fallback requested");
  }
  if(nextId==Integer.MAX_VALUE)throw new IllegalStateException("Resource ID space exhausted");
  ResourceHandle handle=new ResourceHandle(nextId++,width,height,hash,type,wireType);handle.onRelease(()->release(handle));byHash.put(key,new Entry(handle,pixels));totalBytes+=size;registration.accept(handle,pixels);return handle;
 }
 private static String sha256(ResourceHandle.Type type,int width,int height,byte[] pixels){try{
  MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update((byte)type.ordinal());digest.update((byte)(width>>8));digest.update((byte)width);digest.update((byte)(height>>8));digest.update((byte)height);byte[] value=digest.digest(pixels);StringBuilder out=new StringBuilder(64);for(byte b:value)out.append(String.format(Locale.US,"%02x",b));return out.toString();
 }catch(Exception impossible){throw new IllegalStateException(impossible);}}
 synchronized void replay(){List<Entry> ordered=new ArrayList<>(byHash.values());ordered.sort(Comparator.comparingInt(e->e.handle.id));for(Entry entry:ordered){entry.sent=false;registration.accept(entry.handle,entry.pixels);}}
 public synchronized int residentBytes(){return totalBytes;}
 public synchronized int residentCount(){return byHash.size();}
 public synchronized boolean releaseSupported(){return supported.getAsBoolean();}
 /** Queues registered glyphs and images for upload without evicting current cache contents. */
 public boolean prefetch(Collection<ResourceHandle> handles){return prefetch(handles,false);}
 /** Replaces an idle glasses texture cache with the complete set needed by the next animation. */
 public boolean prefetchWorkingSet(Collection<ResourceHandle> handles){return prefetch(handles,true);}
 public boolean prefetch(ResourceHandle... handles){return prefetch(Arrays.asList(handles),false);}
 public boolean prefetchWorkingSet(ResourceHandle... handles){return prefetch(Arrays.asList(handles),true);}
 /** Image-specific aliases retained for source compatibility with the initial API preview. */
 public boolean prefetchImages(Collection<ResourceHandle> handles){return prefetch(handles,false);}
 public boolean prefetchImageWorkingSet(Collection<ResourceHandle> handles){return prefetch(handles,true);}
 public boolean prefetchImages(ResourceHandle... handles){return prefetch(Arrays.asList(handles),false);}
 public boolean prefetchImageWorkingSet(ResourceHandle... handles){return prefetch(Arrays.asList(handles),true);}
 private boolean prefetch(Collection<ResourceHandle> handles,boolean replace){
  Objects.requireNonNull(handles);List<Integer> ids;
  synchronized(this){
   if(!prefetchSupported.getAsBoolean())return false;
   LinkedHashSet<Integer> unique=new LinkedHashSet<>();
   for(ResourceHandle handle:handles){Objects.requireNonNull(handle);Entry entry=byHash.get(handle.registryKey);if(entry==null||entry.handle!=handle||entry.released)throw new IllegalArgumentException("Prefetch requires live registered resources");unique.add(handle.id);}
   if(unique.isEmpty()||unique.size()>128)throw new IllegalArgumentException("Prefetch working set must contain 1..128 resources");ids=new ArrayList<>(unique);
  }
  return prefetchRequest.apply(ids,replace);
 }
 public void release(ResourceHandle handle){synchronized(this){Entry e=byHash.get(handle.registryKey);if(e==null||e.handle!=handle)return;e.released=true;}collect();}
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
