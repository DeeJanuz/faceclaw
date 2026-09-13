package com.faceclaw.sdk;

import java.nio.ByteBuffer;

/** Exclusive writable slot. Closing without submit returns the slot without a frame. */
public final class FrameLease implements AutoCloseable {
 final RenderSurface owner; final int slotId; final long sequence; final RenderCredit credit; private final ByteBuffer pixels; private boolean closed;
 FrameLease(RenderSurface owner,int slotId,long sequence,RenderCredit credit,ByteBuffer pixels) { this.owner=owner;this.slotId=slotId;this.sequence=sequence;this.credit=credit;this.pixels=pixels; }
 public ByteBuffer gray8() { if(closed)throw new IllegalStateException("Frame lease closed");return pixels.duplicate(); }
 public int width(){return owner.width();} public int height(){return owner.height();}
 public void submit(FrameMetadata metadata) { if(closed)throw new IllegalStateException("Frame lease closed");closed=true;owner.submit(this,metadata); }
 @Override public void close() { if(!closed){closed=true;owner.cancel(this);} }
}
