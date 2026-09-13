package com.faceclaw.sdk;

import org.json.*;
import java.util.*;

/** Public, transport-neutral contract for app and bridge capabilities. */
public final class CapabilityContract {
 public static final int VERSION=1, MAX_CAPABILITIES=64, MAX_CATALOG_BYTES=49152, MAX_RESULT_BYTES=24576;
 public static final List<String> STATES=Collections.unmodifiableList(Arrays.asList(
  "running","waiting_for_user","waiting_for_presentation","completed","failed","cancelled","unknown"));
 private static final List<String> KINDS=Arrays.asList("operation","interface");
 private static final List<String> VISIBILITY=Arrays.asList("agent","status-only","local");
 private static final List<String> DURABILITY=Arrays.asList("transient","reconnect","durable");
 private static final List<String> OPERATION_CLASSES=Arrays.asList("read","control","side-effect");

 public static boolean id(String value) {
  return value!=null&&value.length()<=128&&value.matches("[a-z][a-z0-9]*(?:[._-][a-z0-9]+){2,}");
 }
 public static JSONArray declarations(JSONArray supplied) throws JSONException {
  if(supplied==null||supplied.length()>MAX_CAPABILITIES||supplied.toString().length()>MAX_CATALOG_BYTES) throw new IllegalArgumentException("Capability catalog is too large");
  JSONArray result=new JSONArray(); Set<String> ids=new HashSet<>();
  for(int i=0;i<supplied.length();i++) {
   JSONObject item=supplied.getJSONObject(i);
   exact(item,"id","version","kind","title","description","inputSchema","resultVisibility","durability","operationClass","profiles");
   String capabilityId=item.getString("id"),kind=item.getString("kind"),title=item.getString("title"),description=item.getString("description");
   int version=item.getInt("version");
   if(!id(capabilityId)||!ids.add(capabilityId)||version<1||version>1_000_000||!KINDS.contains(kind)||
      !plain(title,1,100)||!plain(description,1,1000)||!VISIBILITY.contains(item.getString("resultVisibility"))||
      !DURABILITY.contains(item.getString("durability"))||!OPERATION_CLASSES.contains(item.getString("operationClass"))) throw new IllegalArgumentException("Invalid capability declaration");
   JSONObject schema=item.getJSONObject("inputSchema"); int[] nodes={0}; validateSchema(schema,0,nodes);
   JSONArray profiles=item.getJSONArray("profiles"); if(profiles.length()>8) throw new IllegalArgumentException("Too many capability profiles");
   JSONArray cleanProfiles=new JSONArray(); Set<String> profileIds=new HashSet<>();
   for(int n=0;n<profiles.length();n++) {
    JSONObject profile=profiles.getJSONObject(n); exact(profile,"id","version");
    String profileId=profile.getString("id"); int profileVersion=profile.getInt("version");
    if(!id(profileId)||!profileIds.add(profileId)||profileVersion<1||profileVersion>1_000_000) throw new IllegalArgumentException("Invalid capability profile");
    cleanProfiles.put(Protocol.object("id",profileId,"version",profileVersion));
   }
   result.put(Protocol.object("id",capabilityId,"version",version,"kind",kind,"title",title,"description",description,
    "inputSchema",new JSONObject(schema.toString()),"resultVisibility",item.getString("resultVisibility"),
    "durability",item.getString("durability"),"operationClass",item.getString("operationClass"),"profiles",cleanProfiles));
  }
  return result;
 }
 public static JSONObject result(JSONObject supplied,boolean progress) throws JSONException {
  if(supplied==null||supplied.toString().length()>MAX_RESULT_BYTES) throw new IllegalArgumentException("Capability result is too large");
  exact(supplied,"state","operationId","message","content","continuations");
  String state=supplied.getString("state");
  if(!STATES.contains(state)||(progress&&!Arrays.asList("running","waiting_for_user","waiting_for_presentation").contains(state))) throw new IllegalArgumentException("Invalid capability state");
  JSONObject result=Protocol.object("state",state);
  if(supplied.has("operationId")) { String id=supplied.getString("operationId"); if(!ExtensionContract.token(id)) throw new IllegalArgumentException("Invalid operation id"); result.put("operationId",id); }
  if(supplied.has("message")) { String message=supplied.getString("message"); if(!plain(message,0,1000)) throw new IllegalArgumentException("Invalid result message"); result.put("message",message); }
  if(supplied.has("content")) result.put("content",copyJson(supplied.get("content"),0,new int[]{0}));
  if(supplied.has("continuations")) {
   JSONArray values=supplied.getJSONArray("continuations"); if(values.length()>16) throw new IllegalArgumentException("Too many continuations");
   JSONArray clean=new JSONArray(); Set<String> names=new HashSet<>();
   for(int i=0;i<values.length();i++) { String value=values.getString(i); if(!id(value)||!names.add(value)) throw new IllegalArgumentException("Invalid continuation"); clean.put(value); }
   result.put("continuations",clean);
  }
  return result;
 }
 /** Validates and copies arguments against the exact schema published by the
  * provider. Hosts call this before dispatch; SDKs repeat it at the app boundary. */
 public static JSONObject arguments(JSONObject schema,JSONObject supplied) throws JSONException {
  if(schema==null||supplied==null||supplied.toString().length()>16384) throw new IllegalArgumentException("Capability arguments are too large");
  int[] nodes={0}; validateSchema(schema,0,new int[]{0});
  Object value=validateValue(schema,supplied,0,nodes);
  if(!(value instanceof JSONObject)) throw new IllegalArgumentException("Capability arguments must be an object");
  return (JSONObject)value;
 }
 private static void validateSchema(JSONObject schema,int depth,int[] nodes) throws JSONException {
  if(depth>5||++nodes[0]>128) throw new IllegalArgumentException("Capability schema is too complex");
  exact(schema,"type","description","properties","required","additionalProperties","items","enum","minimum","maximum","minLength","maxLength","minItems","maxItems");
  String type=schema.getString("type"); if(!Arrays.asList("object","array","string","integer","number","boolean").contains(type)) throw new IllegalArgumentException("Unsupported schema type");
  if(schema.has("description")&&!plain(schema.getString("description"),0,300)) throw new IllegalArgumentException("Invalid schema description");
  if(schema.has("enum")) { JSONArray values=schema.getJSONArray("enum"); if(values.length()<1||values.length()>32) throw new IllegalArgumentException("Invalid schema enum"); for(int i=0;i<values.length();i++) copyJson(values.get(i),depth+1,nodes); }
  for(String key:Arrays.asList("minimum","maximum","minLength","maxLength","minItems","maxItems")) if(schema.has(key)&&!(schema.get(key) instanceof Number)) throw new IllegalArgumentException("Invalid schema bound");
  for(String key:Arrays.asList("minLength","maxLength","minItems","maxItems")) if(schema.has(key)&&(schema.getLong(key)<0||schema.getLong(key)>16384)) throw new IllegalArgumentException("Invalid schema bound");
  if(schema.has("minimum")&&schema.has("maximum")&&schema.getDouble("minimum")>schema.getDouble("maximum")||
     schema.has("minLength")&&schema.has("maxLength")&&schema.getLong("minLength")>schema.getLong("maxLength")||
     schema.has("minItems")&&schema.has("maxItems")&&schema.getLong("minItems")>schema.getLong("maxItems")) throw new IllegalArgumentException("Invalid schema bounds");
  if(type.equals("object")) {
   JSONObject properties=schema.has("properties")?schema.getJSONObject("properties"):new JSONObject(); if(properties.length()>64) throw new IllegalArgumentException("Too many schema properties");
   Iterator<String> keys=properties.keys(); Set<String> propertyNames=new HashSet<>();
   while(keys.hasNext()) { String key=keys.next(); if(!key.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")) throw new IllegalArgumentException("Invalid property name"); propertyNames.add(key); validateSchema(properties.getJSONObject(key),depth+1,nodes); }
   if(schema.opt("additionalProperties")!=Boolean.FALSE) throw new IllegalArgumentException("Object schemas must reject unknown properties");
   JSONArray required=schema.has("required")?schema.getJSONArray("required"):new JSONArray(); Set<String> seen=new HashSet<>();
   for(int i=0;i<required.length();i++) { String name=required.getString(i); if(!propertyNames.contains(name)||!seen.add(name)) throw new IllegalArgumentException("Invalid required property"); }
  } else if(type.equals("array")) validateSchema(schema.getJSONObject("items"),depth+1,nodes);
 }
 private static Object validateValue(JSONObject schema,Object supplied,int depth,int[] nodes) throws JSONException {
  if(depth>6||++nodes[0]>256) throw new IllegalArgumentException("Capability arguments are too complex");
  String type=schema.getString("type"); Object clean;
  if(type.equals("object")) {
   if(!(supplied instanceof JSONObject)) throw new IllegalArgumentException("Expected object argument");
   JSONObject input=(JSONObject)supplied,properties=schema.optJSONObject("properties"),out=new JSONObject();
   if(properties==null) properties=new JSONObject();
   Iterator<String> keys=input.keys();
   while(keys.hasNext()) { String key=keys.next(); JSONObject child=properties.optJSONObject(key); if(child==null) throw new IllegalArgumentException("Unknown capability argument"); out.put(key,validateValue(child,input.get(key),depth+1,nodes)); }
   JSONArray required=schema.optJSONArray("required");
   if(required!=null)for(int i=0;i<required.length();i++)if(!input.has(required.getString(i)))throw new IllegalArgumentException("Missing capability argument");
   clean=out;
  } else if(type.equals("array")) {
   if(!(supplied instanceof JSONArray)) throw new IllegalArgumentException("Expected array argument");
   JSONArray input=(JSONArray)supplied,out=new JSONArray(); int length=input.length();
   if(length<schema.optInt("minItems",0)||length>schema.optInt("maxItems",128)) throw new IllegalArgumentException("Invalid argument array length");
   for(int i=0;i<length;i++)out.put(validateValue(schema.getJSONObject("items"),input.get(i),depth+1,nodes)); clean=out;
  } else if(type.equals("string")) {
   if(!(supplied instanceof String)) throw new IllegalArgumentException("Expected string argument");
   String value=(String)supplied; if(value.length()<schema.optInt("minLength",0)||value.length()>schema.optInt("maxLength",16000)||!argumentText(value)) throw new IllegalArgumentException("Invalid string argument"); clean=value;
  } else if(type.equals("boolean")) {
   if(!(supplied instanceof Boolean)) throw new IllegalArgumentException("Expected boolean argument"); clean=supplied;
  } else {
   if(!(supplied instanceof Number)||!Double.isFinite(((Number)supplied).doubleValue())||type.equals("integer")&&Math.rint(((Number)supplied).doubleValue())!=((Number)supplied).doubleValue()) throw new IllegalArgumentException("Expected numeric argument");
   double value=((Number)supplied).doubleValue(); if(schema.has("minimum")&&value<schema.getDouble("minimum")||schema.has("maximum")&&value>schema.getDouble("maximum")) throw new IllegalArgumentException("Numeric argument is out of range"); clean=supplied;
  }
  if(schema.has("enum")) { JSONArray values=schema.getJSONArray("enum"); boolean match=false; for(int i=0;i<values.length();i++)if(sameJson(values.get(i),clean)) { match=true; break; } if(!match)throw new IllegalArgumentException("Argument is outside the declared enum"); }
  return clean;
 }
 private static boolean sameJson(Object left,Object right) {
  if(left==right||left==JSONObject.NULL&&right==JSONObject.NULL) return true;
  if(left instanceof Number&&right instanceof Number) return Double.compare(((Number)left).doubleValue(),((Number)right).doubleValue())==0;
  return left!=null&&right!=null&&left.getClass().equals(right.getClass())&&left.toString().equals(right.toString());
 }
 private static Object copyJson(Object value,int depth,int[] nodes) throws JSONException {
  if(depth>6||++nodes[0]>256) throw new IllegalArgumentException("Result content is too complex");
  if(value==null||value==JSONObject.NULL||value instanceof Boolean) return value;
  if(value instanceof Number) { if(!Double.isFinite(((Number)value).doubleValue())) throw new IllegalArgumentException("Invalid result number"); return value; }
  if(value instanceof String) { if(!plain((String)value,0,16000)) throw new IllegalArgumentException("Invalid result text"); return value; }
  if(value instanceof JSONArray) { JSONArray input=(JSONArray)value,out=new JSONArray(); if(input.length()>128) throw new IllegalArgumentException("Result array is too large"); for(int i=0;i<input.length();i++)out.put(copyJson(input.get(i),depth+1,nodes)); return out; }
  if(value instanceof JSONObject) { JSONObject input=(JSONObject)value,out=new JSONObject(); if(input.length()>128) throw new IllegalArgumentException("Result object is too large"); Iterator<String> keys=input.keys(); while(keys.hasNext()) { String key=keys.next(); if(!key.matches("[A-Za-z][A-Za-z0-9_.:-]{0,127}"))throw new IllegalArgumentException("Invalid result key"); out.put(key,copyJson(input.get(key),depth+1,nodes)); } return out; }
  throw new IllegalArgumentException("Unsupported result content");
 }
 private static boolean plain(String value,int minimum,int maximum) { return value!=null&&value.length()>=minimum&&value.length()<=maximum&&!value.matches("(?s).*[\\p{Cntrl}\\u202a-\\u202e\\u2066-\\u2069].*"); }
 private static boolean argumentText(String value) { return value!=null&&!value.matches("(?s).*[\\u0000-\\u0008\\u000b\\u000c\\u000e-\\u001f\\u007f\\u202a-\\u202e\\u2066-\\u2069].*"); }
 private static void exact(JSONObject object,String... allowed) {
  Set<String> names=new HashSet<>(Arrays.asList(allowed)); Iterator<String> keys=object.keys();
  while(keys.hasNext()) if(!names.contains(keys.next())) throw new IllegalArgumentException("Unknown capability field");
 }
 private CapabilityContract() {}
}
