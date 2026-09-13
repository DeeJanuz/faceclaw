package com.faceclaw.sdk;

/** Immutable surface-local damage rectangle. */
public final class DamageRect {
 public final int x,y,width,height;
 public DamageRect(int x,int y,int width,int height) {
  if(x<0||y<0||width<=0||height<=0) throw new IllegalArgumentException("Invalid damage rectangle");
  this.x=x;this.y=y;this.width=width;this.height=height;
 }
}
