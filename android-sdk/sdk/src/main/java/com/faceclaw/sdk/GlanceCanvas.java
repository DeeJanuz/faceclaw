package com.faceclaw.sdk;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Base64;

/** Portable, passive drawing commands. Publish validates them against the registered viewport. */
public final class GlanceCanvas {
 private final String widgetId; private final long expiresAt; private final JSONArray commands=new JSONArray();
 public GlanceCanvas(String widgetId,long expiresAt){this.widgetId=widgetId;this.expiresAt=expiresAt;}
 public GlanceCanvas text(int x,int y,String text,int width,int value){commands.put(Protocol.object("op","text","x",x,"y",y,"width",width,"text",text,"value",value));return this;}
 public GlanceCanvas rect(int x,int y,int width,int height,int value){commands.put(Protocol.object("op","rect","x",x,"y",y,"width",width,"height",height,"value",value));return this;}
 public GlanceCanvas bitmap(int x,int y,int width,int height,byte[] gray,int value){
  if(width<1||height<1||width>288||height>288||gray==null||gray.length!=width*height)throw new IllegalArgumentException("Invalid widget bitmap");
  byte[] bits=new byte[(gray.length+7)/8];for(int i=0;i<gray.length;i++)if(gray[i]!=0)bits[i/8]|=(byte)(1<<(7-i%8));
  commands.put(Protocol.object("op","bitmap","x",x,"y",y,"width",width,"height",height,"bits",Base64.getEncoder().encodeToString(bits),"value",value));return this;
 }
 public JSONObject build(){try{return new JSONObject(Protocol.object("version",2,"widgetId",widgetId,"expiresAt",expiresAt,"commands",commands).toString());}catch(Exception error){throw new IllegalStateException(error);}}
}
