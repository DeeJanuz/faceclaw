package com.faceclaw.sdk;

import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Shared wire validation. Authorization remains at the authenticated host boundary. */
public final class IndependenceProtocol {
 public static final long RETENTION_MS=300000;
 public static final int MAX_REQUEST_BYTES=16384;
 private IndependenceProtocol(){}
 public static void keys(JSONObject object,String... allowed){
  Set<String> set=new HashSet<>(Arrays.asList(allowed));
  for(Iterator<String> it=object.keys();it.hasNext();)if(!set.contains(it.next()))throw new IllegalArgumentException("malformed");
 }
 public static long integer(JSONObject object,String key,long min,long max){
  Object value=object.opt(key);
  if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<min||((Number)value).longValue()>max)throw new IllegalArgumentException("malformed");
  return ((Number)value).longValue();
 }
 public static String token(JSONObject object,String key){Object value=object.opt(key);if(!(value instanceof String)||!((String)value).matches("[A-Za-z0-9_-]{1,128}"))throw new IllegalArgumentException("malformed");return (String)value;}
 public static String text(JSONObject object,String key,int max){Object value=object.opt(key);if(!(value instanceof String)||((String)value).length()>max)throw new IllegalArgumentException("malformed");return (String)value;}
 public static boolean bool(JSONObject object,String key){Object value=object.opt(key);if(!(value instanceof Boolean))throw new IllegalArgumentException("malformed");return (Boolean)value;}
 public static void bytes(JSONObject object,int max){if(object.toString().getBytes(StandardCharsets.UTF_8).length>max)throw new IllegalArgumentException("too_large");}
 public static JSONObject copy(JSONObject object){try{return new JSONObject(object.toString());}catch(JSONException e){throw new IllegalArgumentException("malformed");}}
 public static String feature(String operation){return operation.equals("window.policy")?"window.policy":operation.equals("capture.cancel")?"capture.session":"control.result";}
 public static void request(JSONObject data){
  bytes(data,MAX_REQUEST_BYTES);keys(data,"requestId","operation","windowGeneration","revision","expiresAtElapsedMs","payload");token(data,"requestId");integer(data,"windowGeneration",0,Long.MAX_VALUE);integer(data,"revision",0,Long.MAX_VALUE);integer(data,"expiresAtElapsedMs",1,Long.MAX_VALUE);
  String op=text(data,"operation",64);JSONObject p=data.optJSONObject("payload");if(p==null)throw new IllegalArgumentException("malformed");
  switch(op){
   case "window.menu": keys(p,"available");bool(p,"available");break;
   case "window.protection": keys(p,"protected");bool(p,"protected");break;
   case "window.open": keys(p,"target");text(p,"target",512);break;
   case "window.sleep": case "window.system-menu": keys(p);break;
   case "window.back": keys(p,"response");if(!Arrays.asList("handled","at-root").contains(text(p,"response",16)))throw new IllegalArgumentException("malformed");break;
   case "window.policy": policy(p);break;
   case "capture.cancel": keys(p,"captureId");token(p,"captureId");break;
   default: throw new IllegalArgumentException("unsupported");
  }
 }
 public static void policy(JSONObject p){
  bytes(p,4096);keys(p,"preferredHeightMode","preferredWidthMode","chrome","menuAvailable","back","gestureClaims");
  if(!Arrays.asList("min","medium","max").contains(text(p,"preferredHeightMode",8))||!text(p,"preferredWidthMode",16).equals("display")||!Arrays.asList("host","compact").contains(text(p,"chrome",8))||!Arrays.asList("app-then-host","host-only").contains(text(p,"back",16)))throw new IllegalArgumentException("malformed");
  bool(p,"menuAvailable");JSONArray claims=p.optJSONArray("gestureClaims");if(claims==null||claims.length()>4)throw new IllegalArgumentException("malformed");Set<String> seen=new HashSet<>();for(int i=0;i<claims.length();i++){Object c=claims.opt(i);if(!(c instanceof String)||!Arrays.asList("long-press","directional").contains(c)||!seen.add((String)c))throw new IllegalArgumentException("unsupported");}
 }
 public static JSONObject negotiate(JSONObject declaration,Set<String> supported,long catalogEpoch,long previousEpoch){
  bytes(declaration,16384);keys(declaration,"contractVersion","epoch","features");integer(declaration,"contractVersion",1,1);long epoch=integer(declaration,"epoch",1,Long.MAX_VALUE);
  if(epoch<=previousEpoch)throw new IllegalArgumentException("stale_session");JSONArray entries=declaration.optJSONArray("features");if(entries==null||entries.length()>32)throw new IllegalArgumentException("malformed");
  Set<String> seen=new HashSet<>();JSONArray results=new JSONArray();boolean rejected=false;
  for(int i=0;i<entries.length();i++){
   JSONObject f=entries.optJSONObject(i);if(f==null)throw new IllegalArgumentException("malformed");keys(f,"id","minVersion","required","fallback");String id=text(f,"id",128);if(!id.matches("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)+")||!seen.add(id))throw new IllegalArgumentException("malformed");
   long version=integer(f,"minVersion",1,Integer.MAX_VALUE);boolean required=bool(f,"required");String fallback=text(f,"fallback",32);if(!Arrays.asList("legacy-window","legacy-control","legacy-assistant","legacy-capture","none").contains(fallback)||required&&fallback.equals("none"))throw new IllegalArgumentException("malformed");
   boolean accepted=supported.contains(id)&&version==1;rejected|=required&&!accepted;
   results.put(Protocol.object("id",id,"state",accepted?"accepted":"unsupported","version",accepted?1:0,"fallback",accepted?"none":fallback));
  }
  return Protocol.object("epoch",epoch,"catalogEpoch",catalogEpoch,"state",rejected?"rejected":"applied","features",results);
 }
}
