package com.faceclaw.sdk;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;

/** Versioned passive summaries. No actions, window authority, credentials, or rendered pixels. */
public final class GlanceboardContract {
 public static final int VERSION=1, MAX_ENTRIES=12;
 public static final long MAX_LIFETIME_MS=60000;
 private GlanceboardContract() {}
 private static String text(JSONObject object,String key,int max) throws Exception {
  Object value=object.opt(key);
  if(!(value instanceof String)||((String)value).length()>max)throw new IllegalArgumentException("Invalid glance text");
  return ((String)value).replaceAll("[\\p{Cntrl}]"," ");
 }
 private static long expiry(JSONObject object,String key) {
  Object value=object.opt(key);
  if(!(value instanceof Number))throw new IllegalArgumentException("Invalid glance expiry type");
  double number=((Number)value).doubleValue();
  if(!Double.isFinite(number)||number!=Math.rint(number)||number<1||number>9007199254740991L)throw new IllegalArgumentException("Invalid glance expiry value");
  return ((Number)value).longValue();
 }
 public static JSONObject validate(JSONObject value,long now) {
  try {
   if(value==null||value.toString().length()>16384||value.optInt("version")!=VERSION)throw new IllegalArgumentException("Invalid glance version or size");
   long expires=expiry(value,"expiresAt");
   if(expires<=now||expires-now>MAX_LIFETIME_MS)throw new IllegalArgumentException("Invalid glance expiry");
   JSONArray input=value.getJSONArray("entries"),entries=new JSONArray();
   if(input.length()>MAX_ENTRIES)throw new IllegalArgumentException("Too many glance entries");
   HashSet<String> ids=new HashSet<>();
   for(int i=0;i<input.length();i++) {
    JSONObject row=input.getJSONObject(i);String id=text(row,"id",256);
    if(id.isEmpty()||!ids.add(id))throw new IllegalArgumentException("Invalid glance identity");
    long rowExpiry=row.has("expiresAt")?expiry(row,"expiresAt"):expires;
    if(rowExpiry<=now)continue;
    entries.put(Protocol.object("id",id,"title",text(row,"title",160),"detail",text(row,"detail",256),"expiresAt",Math.min(rowExpiry,expires)));
   }
   return Protocol.object("version",VERSION,"title",text(value,"title",80),"emptyText",text(value,"emptyText",160),"expiresAt",expires,"entries",entries);
  } catch(Exception error) { throw new IllegalArgumentException("Invalid Glanceboard content",error); }
 }
 public static final int REGISTRY_VERSION=1, CONTENT_VERSION=2, WIDTH=288, SLOT_HEIGHT=144;
 private static long integer(JSONObject value,String key,long min,long max) {
  Object raw=value.opt(key);
  if(!(raw instanceof Number))throw new IllegalArgumentException("Invalid widget number");
  double n=((Number)raw).doubleValue();
  if(!Double.isFinite(n)||n!=Math.rint(n)||n<min||n>max)throw new IllegalArgumentException("Invalid widget number");
  return (long)n;
 }
 private static String token(JSONObject value,String key) throws Exception {
  String id=text(value,key,64);
  if(!id.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}"))throw new IllegalArgumentException("Invalid widget id");
  return id;
 }
 /** Replaces this service's complete catalog. An empty list unregisters all widgets. */
 public static JSONObject registry(JSONObject value) {
  try {
   if(value==null||value.toString().length()>8192||integer(value,"version",1,1)!=1)throw new IllegalArgumentException();
   JSONArray input=value.getJSONArray("widgets"),out=new JSONArray();HashSet<String> ids=new HashSet<>();
   if(input.length()>8)throw new IllegalArgumentException();
   for(int i=0;i<input.length();i++){
    JSONObject row=input.getJSONObject(i);String id=token(row,"id"),label=text(row,"label",80),kind=text(row,"kind",16);
    if(!ids.add(id)||label.trim().isEmpty()||!java.util.Arrays.asList("list","scene").contains(kind))throw new IllegalArgumentException();
    int rows=(int)integer(row,"rows",1,2);long refresh=integer(row,"refreshMs",5000,60000);
    JSONArray uses=row.has("uses")?row.getJSONArray("uses"):new JSONArray(),cleanUses=new JSONArray();HashSet<String> unique=new HashSet<>();
    if(uses.length()>3)throw new IllegalArgumentException();
    for(int j=0;j<uses.length();j++){Object use=uses.get(j);if(!(use instanceof String)||!java.util.Arrays.asList("battery","weather","time-format").contains(use)||!unique.add((String)use))throw new IllegalArgumentException();cleanUses.put(use);}
    out.put(Protocol.object("id",id,"label",label,"kind",kind,"rows",rows,"refreshMs",refresh,"uses",cleanUses));
   }
   return Protocol.object("version",1,"widgets",out);
  }catch(Exception error){throw new IllegalArgumentException("Invalid Glanceboard registry",error);}
 }
 public static JSONObject widget(JSONObject registry,String id) {
  if(registry==null)return null;JSONArray rows=registry.optJSONArray("widgets");if(rows==null)return null;
  for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null&&id.equals(row.optString("id")))return row;}
  return null;
 }
 /** Validates an app-owned, registered passive widget frame. */
 public static JSONObject content(JSONObject value,JSONObject registry,long now) {
  try {
   if(value==null||value.toString().length()>32768||integer(value,"version",2,2)!=2)throw new IllegalArgumentException();
   String id=token(value,"widgetId");JSONObject declaration=widget(registry,id);if(declaration==null)throw new IllegalArgumentException();
   long expires=expiry(value,"expiresAt");if(expires<=now||expires-now>MAX_LIFETIME_MS)throw new IllegalArgumentException();
   if(declaration.getString("kind").equals("list")){
    JSONObject legacy=new JSONObject(value.toString());legacy.put("version",1);
    JSONObject result=validate(legacy,now);result.put("version",2);result.put("widgetId",id);return result;
   }
   int height=declaration.getInt("rows")*SLOT_HEIGHT,totalPixels=0;
   JSONArray input=value.getJSONArray("commands"),out=new JSONArray();if(input.length()>96)throw new IllegalArgumentException();
   for(int i=0;i<input.length();i++){
    JSONObject command=input.getJSONObject(i);String op=text(command,"op",16);
    int x=(int)integer(command,"x",0,WIDTH-1),y=(int)integer(command,"y",0,height-1),gray=(int)integer(command,"value",0,255);
    JSONObject clean=Protocol.object("op",op,"x",x,"y",y,"value",gray);
    if(op.equals("text")){clean.put("text",text(command,"text",256));clean.put("width",integer(command,"width",1,WIDTH-x));}
    else if(op.equals("rect")||op.equals("bitmap")){
     int w=(int)integer(command,"width",1,WIDTH-x),h=(int)integer(command,"height",1,height-y);clean.put("width",w);clean.put("height",h);
     if(op.equals("bitmap")){
      totalPixels+=w*h;if(totalPixels>WIDTH*height)throw new IllegalArgumentException();
      String bits=text(command,"bits",16384);byte[] bytes=java.util.Base64.getDecoder().decode(bits);
      if(bytes.length!=(w*h+7)/8||!java.util.Base64.getEncoder().encodeToString(bytes).equals(bits))throw new IllegalArgumentException();
      clean.put("bits",bits);
     }
    }else throw new IllegalArgumentException();
    out.put(clean);
   }
   return Protocol.object("version",2,"widgetId",id,"expiresAt",expires,"commands",out);
  }catch(Exception error){throw new IllegalArgumentException("Invalid Glanceboard widget content",error);}
 }

}
