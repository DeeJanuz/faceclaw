package com.faceclaw.sdk;

import android.graphics.*;
import java.util.*;
/** Optional ordinary Canvas helpers. All coordinates are viewport pixels. */
public final class Ui {
 public static void text(Canvas canvas,String text,float x,float baseline,float size,int color) {
  Paint p=new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(color); p.setTextSize(size); p.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL)); canvas.drawText(text,x,baseline,p);
 }
 public static void card(Canvas canvas,float left,float top,float right,float bottom,float radius,int color) {
  Paint p=new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(color); canvas.drawRoundRect(left,top,right,bottom,radius,radius,p);
 }
 public static List<String> wrap(String text,Paint paint,float width) {
  if(width<=0) throw new IllegalArgumentException("Invalid wrap width");
  List<String> lines=new ArrayList<>();
  for(String paragraph:text.split("\n",-1)) {
   if(paragraph.isEmpty()) { lines.add(""); continue; }
   while(!paragraph.isEmpty()) {
    int n=paint.breakText(paragraph,true,width,null); if(n<1) n=Character.charCount(paragraph.codePointAt(0));
    if(n<paragraph.length() && Character.isHighSurrogate(paragraph.charAt(n-1))) n--;
    if(n<=0) n=Character.charCount(paragraph.codePointAt(0));
    int space=paragraph.lastIndexOf(' ',n); if(n<paragraph.length() && space>0) n=space;
    lines.add(paragraph.substring(0,n)); paragraph=paragraph.substring(n).replaceFirst("^ +","");
   }
  }
  return lines;
 }
 public static Bitmap layers(int width,int height,Bitmap... layers) {
  Protocol.frameSize(width,height); Bitmap out=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888); Canvas c=new Canvas(out); c.drawColor(Color.BLACK);
  for(Bitmap layer:layers) c.drawBitmap(layer,0,0,null); return out;
 }
 private Ui() {}
}
