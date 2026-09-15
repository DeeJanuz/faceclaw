package com.faceclaw.sdk;

import org.json.JSONObject;
/** Stable SDK 1.0 wire contract. Protocol 2 intentionally rejects prerelease V1 builds. */
public final class Protocol {
 public static final String ACTION="com.faceclaw.action.APP_SERVICE";
 public static final String SDK_VERSION="1.1.0-rc.1";
 public static final int VERSION=2;
 public static final int MAX_WIDTH=640, MAX_HEIGHT=480, MAX_JSON=65536;
 public static final int MAX_DAMAGE_RECTS=8, BUFFER_SLOTS=3, FRAME_HEADER_BYTES=64;
 public static final int MAX_RESOURCE_BYTES=4*1024*1024, MAX_RESOURCES=2048;
 public static final int MAX_SCENE_NODES=2048, MAX_COMMAND_BYTES=256*1024;
 public static int frameSize(int width,int height) {
  if(width<1 || height<1 || width>MAX_WIDTH || height>MAX_HEIGHT) throw new IllegalArgumentException("Invalid viewport");
  return width*height;
 }
 public static ControlEvent control(String type,JSONObject json){return new ControlEvent(type,json);}
 public static JSONObject object(Object... fields) {
  JSONObject result=new JSONObject();
  try { for(int i=0;i<fields.length;i+=2) result.put((String)fields[i],fields[i+1]); }
  catch(Exception e) { throw new IllegalArgumentException(e); }
  return result;
 }
 private Protocol() {}
}
