package com.faceclaw.sdk;

public final class ResourceHandle {
 public enum Type { IMAGE, GLYPH }
 public final int id,width,height; public final String sha256; public final Type type;
 ResourceHandle(int id,int width,int height,String sha256,Type type) { this.id=id;this.width=width;this.height=height;this.sha256=sha256;this.type=type; }
}
