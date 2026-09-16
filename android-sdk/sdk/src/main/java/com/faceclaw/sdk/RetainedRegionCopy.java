package com.faceclaw.sdk;

/**
 * A best-effort hint that pixels from the previously submitted frame can be
 * moved to build the new frame. The submitted Gray8 pixels remain authoritative;
 * hosts that do not support this hint ignore it, and supporting hosts repair any
 * pixels that do not match the new frame after applying the copy.
 */
public final class RetainedRegionCopy {
 public final int sourceX,sourceY,width,height,destinationX,destinationY;

 public RetainedRegionCopy(int sourceX,int sourceY,int width,int height,int destinationX,int destinationY) {
  if(sourceX<Short.MIN_VALUE||sourceX>Short.MAX_VALUE||sourceY<Short.MIN_VALUE||sourceY>Short.MAX_VALUE
      ||destinationX<Short.MIN_VALUE||destinationX>Short.MAX_VALUE||destinationY<Short.MIN_VALUE||destinationY>Short.MAX_VALUE
      ||width<1||width>0xffff||height<1||height>0xffff)throw new IllegalArgumentException("Invalid retained region copy");
  this.sourceX=sourceX;this.sourceY=sourceY;this.width=width;this.height=height;
  this.destinationX=destinationX;this.destinationY=destinationY;
 }
}
