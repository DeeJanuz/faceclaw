package com.faceclaw.app;

/** Content-addressing seam; texture planning is outside the identity boundary fixture. */
public final class GlyphAtlas {
 private static int next=1;
 private GlyphAtlas(){}
 public static synchronized int ensureGray(String key,int encoding,int width,int height,AndroidByteReader pixels){return next++;}
 public static synchronized void forgetGray(String key,int fontId,int encoding){}
}
