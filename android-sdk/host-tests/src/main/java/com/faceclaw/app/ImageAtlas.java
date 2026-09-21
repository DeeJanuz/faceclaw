package com.faceclaw.app;

/** Content-addressing seam; texture planning is outside the identity boundary fixture. */
public final class ImageAtlas {
 private static int next=1;
 private ImageAtlas(){}
 public static synchronized int ensure(String key,int width,int height,AndroidByteReader pixels){return next++;}
 public static synchronized void forget(String key,int id){}
}
