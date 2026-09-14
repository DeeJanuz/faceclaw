package com.faceclaw.sdk;

import java.nio.ByteBuffer;

/** Exclusive writable slot. Closing without submit returns the slot without a frame. */
public final class FrameLease implements AutoCloseable {
 final RenderSurface owner; final long generation; final int slotId,width,height; final long sequence; final RenderCredit credit; private final ByteBuffer pixels; private boolean closed;
 FrameLease(RenderSurface owner,long generation,int slotId,long sequence,int width,int height,RenderCredit credit,ByteBuffer pixels) { this.owner=owner;this.generation=generation;this.slotId=slotId;this.sequence=sequence;this.width=width;this.height=height;this.credit=credit;this.pixels=pixels; }
 public ByteBuffer gray8() { if(closed)throw new IllegalStateException("Frame lease closed");return pixels.duplicate(); }
 public int width(){return width;} public int height(){return height;}
 public void submit(FrameMetadata metadata) { if(closed)throw new IllegalStateException("Frame lease closed");closed=true;owner.submit(this,metadata); }
 @Override public void close() { if(!closed){closed=true;owner.cancel(this);} }
}
