package com.faceclaw.app;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

public final class RetainedCopyPlannerCheck {
 private static int pixel(byte[] packed,int width,int x,int y){int value=packed[y*((width+1)>>1)+(x>>1)]&255;return (x&1)==0?value>>4:value&15;}
 private static void set(byte[] packed,int width,int x,int y,int value){int at=y*((width+1)>>1)+(x>>1),old=packed[at]&255;packed[at]=(byte)((x&1)==0?((value&15)<<4)|(old&15):(old&0xf0)|(value&15));}
 private static byte[] expectedCopy(byte[] input,int width,SurfaceCompositor.ScreenCopy copy){byte[] out=input.clone();int[] source=new int[copy.width*copy.height];for(int y=0;y<copy.height;y++)for(int x=0;x<copy.width;x++)source[y*copy.width+x]=pixel(input,width,copy.sourceX+x,copy.sourceY+y);for(int y=0;y<copy.height;y++)for(int x=0;x<copy.width;x++)set(out,width,copy.destinationX+x,copy.destinationY+y,source[y*copy.width+x]);return out;}
 public static void main(String[] args){
  Random random=new Random(9017);
  for(int trial=0;trial<1000;trial++){
   int width=1+random.nextInt(80),height=1+random.nextInt(40),copyWidth=1+random.nextInt(width),copyHeight=1+random.nextInt(height);
   int sx=random.nextInt(width-copyWidth+1),sy=random.nextInt(height-copyHeight+1),dx=random.nextInt(width-copyWidth+1),dy=random.nextInt(height-copyHeight+1);
   byte[] packed=new byte[((width+1)>>1)*height];random.nextBytes(packed);SurfaceCompositor.ScreenCopy copy=new SurfaceCompositor.ScreenCopy(sx,sy,copyWidth,copyHeight,dx,dy);
   byte[] expected=expectedCopy(packed,width,copy),actual=packed.clone();BleImageOptimizer.applyRetainedCopy(actual,width,copy);
   if(!Arrays.equals(expected,actual))throw new AssertionError("overlap copy mismatch at trial "+trial);
  }

  int width=96,height=32,stride=(width+1)>>1;byte[] previous=new byte[stride*height];random.nextBytes(previous);
  byte[] next=new byte[stride*height];for(int y=0;y<height;y++)for(int x=0;x<width-12;x++)set(next,width,x,y,pixel(previous,width,x+12,y));for(int y=0;y<height;y++)for(int x=width-12;x<width;x++)set(next,width,x,y,random.nextInt(16));
  SurfaceCompositor.ScreenCopy[] copies={new SurfaceCompositor.ScreenCopy(12,0,width-12,height,0,0)};
  BleImageOptimizer.CopyPlan plan=BleImageOptimizer.buildRetainedCopyPayload(previous,next,width,height,copies,7,6);
  if(plan==null||plan.payload[0]!=8||plan.copyCount!=1||plan.repairRectCount<1)throw new AssertionError("copy+repair plan missing");
  int firstLength=(plan.payload[2]&255)|((plan.payload[3]&255)<<8);if(firstLength!=17||plan.payload[4]!=9)throw new AssertionError("mode-9 copy not first in batch");
  BleImageOptimizer.IncrementalPlan raster=BleImageOptimizer.buildIncrementalImagePayload(previous,next,width,height,7);int rasterBytes=raster==null?BleImageOptimizer.maybeCompress(next,width,height).length:raster.payload.length;
  if(plan.payload.length>=rasterBytes)throw new AssertionError("copy plan did not reduce payload");

  SurfaceCompositor compositor=new SurfaceCompositor();compositor.configureScreen(20,10);compositor.configureSurface("apk",2,3,10,4,0,SurfaceCompositor.TRANSPARENCY_OPAQUE);
  byte[] gray=new byte[40];compositor.applyDamageAndCompositePacked("apk",ByteBuffer.wrap(gray),null,"first",null,null,new byte[100]);
  gray[0]=(byte)255;SurfaceCompositor.PackedComposite translated=compositor.applyDamageAndCompositePacked("apk",ByteBuffer.wrap(gray),null,"second",null,new int[]{2,0,8,4,0,0},new byte[100]);
  if(translated.composite.copies.length!=1)throw new AssertionError("surface copy hint was dropped");SurfaceCompositor.ScreenCopy screen=translated.composite.copies[0];
  if(screen.sourceX!=4||screen.sourceY!=3||screen.destinationX!=2||screen.destinationY!=3||screen.width!=8||screen.height!=4)throw new AssertionError("surface copy was not translated to screen coordinates");
 }
}
