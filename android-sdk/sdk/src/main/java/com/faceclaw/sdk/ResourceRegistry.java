package com.faceclaw.sdk;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;

/** Session-scoped immutable resource registry. Registration is content-addressed. */
public final class ResourceRegistry {
 private static final class Entry {final ResourceHandle handle;final byte[] pixels;Entry(ResourceHandle handle,byte[] pixels){this.handle=handle;this.pixels=pixels;}}
 private final FaceclawSession session; private final Map<String,Entry> byHash=new HashMap<>(); private int nextId=1,totalBytes;
 ResourceRegistry(FaceclawSession session){this.session=session;}
 public synchronized ResourceHandle registerGrayImage(int width,int height,ByteBuffer gray8){return register(ResourceHandle.Type.IMAGE,width,height,gray8);}
 public synchronized ResourceHandle registerGlyph(int width,int height,ByteBuffer gray8){return register(ResourceHandle.Type.GLYPH,width,height,gray8);}
 private ResourceHandle register(ResourceHandle.Type type,int width,int height,ByteBuffer source){
  int size=Protocol.frameSize(width,height);if(width>255||height>255||source==null||source.remaining()!=size)throw new IllegalArgumentException("Invalid resource");
  byte[] pixels=new byte[size];source.duplicate().get(pixels);String hash=sha256(type,width,height,pixels);Entry existing=byHash.get(hash);if(existing!=null)return existing.handle;
  if(byHash.size()>=Protocol.MAX_RESOURCES||totalBytes+size>Protocol.MAX_RESOURCE_BYTES){session.invalidateAll();throw new IllegalStateException("Resource quota exceeded; raster fallback requested");}
  ResourceHandle handle=new ResourceHandle(nextId++,width,height,hash,type);byHash.put(hash,new Entry(handle,pixels));totalBytes+=size;session.registerResource(handle,pixels);return handle;
 }
 private static String sha256(ResourceHandle.Type type,int width,int height,byte[] pixels){try{
  MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update((byte)type.ordinal());digest.update((byte)(width>>8));digest.update((byte)width);digest.update((byte)(height>>8));digest.update((byte)height);byte[] value=digest.digest(pixels);StringBuilder out=new StringBuilder(64);for(byte b:value)out.append(String.format(Locale.US,"%02x",b));return out.toString();
 }catch(Exception impossible){throw new IllegalStateException(impossible);}}
 synchronized void replay(){for(Entry entry:byHash.values())session.registerResource(entry.handle,entry.pixels);}
}
