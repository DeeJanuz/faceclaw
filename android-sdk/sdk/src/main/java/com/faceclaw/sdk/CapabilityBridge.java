package com.faceclaw.sdk;

import android.content.ComponentName;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

/**
 * Helpers for an approved APK that relays Faceclaw's effective capability
 * catalog to an authenticated agent transport. The host remains the authority
 * for provider selection, schema validation, presentation, and result filtering.
 */
public final class CapabilityBridge {
 public static final String FEATURE="device-tools";

 /** Declares the separately granted bridge role. Publishing it does not grant it. */
 public static JSONArray extensionDeclaration(String label) {
  if(label==null||label.trim().isEmpty()||label.length()>100||label.matches("(?s).*[\\p{Cntrl}].*")) throw new IllegalArgumentException("Invalid bridge label");
  return new JSONArray().put(Protocol.object("feature",FEATURE,"enabled",true,
   "configuration",Protocol.object("label",label),"requires",new JSONArray()));
 }

 /** Returns this service's current device-tools authority epoch, or zero. */
 public static long activeGeneration(FaceclawAppService service) {
  if(service==null) return 0;
  String component=new ComponentName(service,service.getClass()).flattenToString();
  JSONArray features=service.extensions().optJSONArray("features");
  if(features==null) return 0;
  for(int i=0;i<features.length();i++) {
   JSONObject feature=features.optJSONObject(i);
   if(feature!=null&&FEATURE.equals(feature.optString("feature"))&&component.equals(feature.optString("component"))&&feature.optBoolean("available")) {
    long generation=feature.optLong("generation"); return generation>0?generation:0;
   }
  }
  return 0;
 }

 /**
  * Accepts an onHostEvent envelope and returns {generation, tools}, or null
  * when the event is unrelated or malformed. Callers replace their complete
  * remote catalog whenever a non-null value is returned.
  */
 public static JSONObject catalog(String type,JSONObject envelope) {
  try {
   if(!"extension-event".equals(type)||!event(envelope,"tool-catalog")) return null;
   JSONArray supplied=envelope.getJSONObject("data").getJSONArray("tools");
   if(supplied.length()>128||supplied.toString().length()>Protocol.MAX_JSON) return null;
   JSONArray tools=new JSONArray(); Set<String> names=new HashSet<>();
   for(int i=0;i<supplied.length();i++) {
    JSONObject tool=supplied.getJSONObject(i); String name=tool.getString("name"),description=tool.getString("description");
    JSONObject schema=tool.getJSONObject("inputSchema");
    if(!name(name)||!names.add(name)||description.isEmpty()||description.length()>2000||schema.toString().length()>16384||!"object".equals(schema.optString("type"))) return null;
    JSONObject clean=Protocol.object("name",name,"description",description,"inputSchema",new JSONObject(schema.toString()));
    if(tool.has("_meta")) { JSONObject metadata=tool.getJSONObject("_meta"); if(metadata.toString().length()>4096) return null; clean.put("_meta",new JSONObject(metadata.toString())); }
    tools.put(clean);
   }
   return Protocol.object("generation",envelope.getLong("generation"),"tools",tools);
  } catch(Exception malformed) { return null; }
 }

 /** Returns the matching tool/action result payload, or null. */
 public static JSONObject result(String type,JSONObject envelope,long generation,String callId) {
  try {
   if(!"extension-event".equals(type)||!ExtensionContract.token(callId)||envelope==null||!FEATURE.equals(envelope.optString("feature"))||
      generation<1||envelope.optLong("generation")!=generation||!"event".equals(envelope.optString("type"))) return null;
   JSONObject data=envelope.getJSONObject("data"); String event=data.optString("event");
   if(!callId.equals(data.optString("callId"))||!(event.equals("tool-result")||event.equals("action-result"))||data.toString().length()>Protocol.MAX_JSON/2) return null;
   return new JSONObject(data.toString());
  } catch(Exception malformed) { return null; }
 }

 /**
  * Dispatches one fresh remote call through the current device-tools epoch.
  * The bridge must still authenticate its project/session and consume one
  * matching result. A false return does not authorize retry after uncertainty.
  */
 public static boolean invoke(FaceclawAppService service,long generation,String projectId,String bridgeSession,
   String pairingFingerprint,String callId,String name,JSONObject arguments,long issuedAt,long expiresAt) {
  long now=System.currentTimeMillis();
  if(service==null||generation<1||generation!=activeGeneration(service)||!token(projectId,256)||!token(bridgeSession,256)||!ExtensionContract.token(callId)||!name(name)||arguments==null||
     arguments.toString().length()>16384||issuedAt>now+5000||expiresAt<=now||expiresAt<=issuedAt||expiresAt-issuedAt>120000||
     pairingFingerprint!=null&&!pairingFingerprint.isEmpty()&&!pairingFingerprint.matches("[a-f0-9]{64}")) return false;
  try {
   JSONObject data=Protocol.object("projectId",projectId,"bridgeSession",bridgeSession,"callId",callId,"name",name,
    "arguments",new JSONObject(arguments.toString()),"issuedAt",issuedAt,"expiresAt",expiresAt);
   if(pairingFingerprint!=null&&!pairingFingerprint.isEmpty()) data.put("pairingFingerprint",pairingFingerprint);
   return service.invokeExtensionAction(FEATURE,generation,"tool-call",data);
  } catch(Exception malformed) { return false; }
 }

 private static boolean event(JSONObject envelope,String event) {
  if(envelope==null||!FEATURE.equals(envelope.optString("feature"))||envelope.optLong("generation")<1||!"event".equals(envelope.optString("type"))) return false;
  JSONObject data=envelope.optJSONObject("data"); return data!=null&&event.equals(data.optString("event"));
 }
 private static boolean name(String value) { return value!=null&&value.matches("[A-Za-z0-9_.-]{1,128}"); }
 private static boolean token(String value,int maximum) { return value!=null&&value.length()>0&&value.length()<=maximum&&value.matches("[A-Za-z0-9_.:-]+"); }
 private CapabilityBridge() {}
}
