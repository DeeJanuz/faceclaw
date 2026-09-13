package com.faceclaw.sdk;

import java.nio.*;
import java.util.*;

/** Optional structured resource placements accompanying baked raster pixels. */
public final class DrawBatch {
 public static final int RECORD_BYTES=14; private final List<Draw> draws;
 private DrawBatch(List<Draw> draws){this.draws=Collections.unmodifiableList(new ArrayList<>(draws));}
 public static Builder builder(){return new Builder();}
 byte[] encode(){ByteBuffer out=ByteBuffer.allocate(draws.size()*RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN);for(Draw d:draws){out.put((byte)d.resource.type.ordinal()).putInt(d.resource.id).putShort((short)d.x).putShort((short)d.y).put((byte)d.brightness).putInt(0);}return out.array();}
 public static final class Builder {
  private final List<Draw> draws=new ArrayList<>();
  public Builder image(ResourceHandle resource,int x,int y){return place(resource,x,y,255);}
  public Builder glyph(ResourceHandle resource,int x,int y,int brightness){return place(resource,x,y,brightness);}
  public Builder place(ResourceHandle resource,int x,int y,int brightness){Objects.requireNonNull(resource);if(x<Short.MIN_VALUE||x>Short.MAX_VALUE||y<Short.MIN_VALUE||y>Short.MAX_VALUE||brightness<0||brightness>255)throw new IllegalArgumentException("Invalid draw");if(((long)draws.size()+1)*RECORD_BYTES>Protocol.MAX_COMMAND_BYTES)throw new IllegalStateException("Draw batch too large");draws.add(new Draw(resource,x,y,brightness));return this;}
  public DrawBatch build(){return new DrawBatch(draws);}
 }
 private static final class Draw { final ResourceHandle resource;final int x,y,brightness;Draw(ResourceHandle r,int x,int y,int b){resource=r;this.x=x;this.y=y;brightness=b;} }
}
