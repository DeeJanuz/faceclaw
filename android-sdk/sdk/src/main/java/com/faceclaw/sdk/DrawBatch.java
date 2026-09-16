package com.faceclaw.sdk;

import java.nio.*;
import java.util.*;

/** Optional structured resource placements accompanying baked raster pixels. */
public final class DrawBatch {
 public static final int RECORD_BYTES=14; private final List<Draw> draws;
 private DrawBatch(List<Draw> draws){this.draws=Collections.unmodifiableList(new ArrayList<>(draws));}
 public static Builder builder(){return new Builder();}
 byte[] encode(){return encode(Collections.emptyList());}
 byte[] encode(List<RetainedRegionCopy> copies){
  List<RetainedRegionCopy> safe=copies==null?Collections.emptyList():copies;
  long size=((long)draws.size()+safe.size())*RECORD_BYTES;if(size>Protocol.MAX_COMMAND_BYTES)throw new IllegalStateException("Frame metadata too large");
  ByteBuffer out=ByteBuffer.allocate((int)size).order(ByteOrder.LITTLE_ENDIAN);
  for(Draw d:draws)out.put((byte)d.resource.type.ordinal()).putInt(d.resource.id).putShort((short)d.x).putShort((short)d.y).put((byte)d.brightness).putInt(0);
  for(RetainedRegionCopy copy:safe)out.put((byte)2).putShort((short)copy.sourceX).putShort((short)copy.sourceY).putShort((short)copy.width).putShort((short)copy.height).putShort((short)copy.destinationX).putShort((short)copy.destinationY).put((byte)0);
  return out.array();
 }
 static byte[] encodeMetadata(DrawBatch draws,List<RetainedRegionCopy> copies){
  if(draws!=null)return draws.encode(copies);
  if(copies==null||copies.isEmpty())return null;
  return new DrawBatch(Collections.emptyList()).encode(copies);
 }
 public static final class Builder {
  private final List<Draw> draws=new ArrayList<>();
  public Builder image(ResourceHandle resource,int x,int y){return place(resource,x,y,255);}
  public Builder glyph(ResourceHandle resource,int x,int y,int brightness){return place(resource,x,y,brightness);}
  public Builder place(ResourceHandle resource,int x,int y,int brightness){Objects.requireNonNull(resource);if(x<Short.MIN_VALUE||x>Short.MAX_VALUE||y<Short.MIN_VALUE||y>Short.MAX_VALUE||brightness<0||brightness>255)throw new IllegalArgumentException("Invalid draw");if(((long)draws.size()+1)*RECORD_BYTES>Protocol.MAX_COMMAND_BYTES)throw new IllegalStateException("Draw batch too large");draws.add(new Draw(resource,x,y,brightness));return this;}
  public DrawBatch build(){return new DrawBatch(draws);}
 }
 private static final class Draw { final ResourceHandle resource;final int x,y,brightness;Draw(ResourceHandle r,int x,int y,int b){resource=r;this.x=x;this.y=y;brightness=b;} }
}
