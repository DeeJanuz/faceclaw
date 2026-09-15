package com.faceclaw.sdk;

public final class ResourceHandle implements AutoCloseable {
 private Runnable release=()->{};
 void onRelease(Runnable action){release=action;}
 /** Relinquish app ownership after the final application use. Retained scenes remain valid. */
 @Override public void close(){release.run();}
 public enum Type { IMAGE, GLYPH }
 public final int id,width,height; public final String sha256; public final Type type;
 ResourceHandle(int id,int width,int height,String sha256,Type type) { this.id=id;this.width=width;this.height=height;this.sha256=sha256;this.type=type; }
}
