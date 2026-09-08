package com.faceclaw.app;

import android.content.SharedPreferences;
import com.faceclaw.sdk.*;
import org.json.*;
import java.util.*;

/** Host-private, signer-bound declarations, grants and user-owned feature order. */
final class FaceclawExtensions {
 interface Owners { Map<String,String> approved(); boolean connected(String component); }
 private final SharedPreferences prefs; private final Owners owners;
 private long generation=1;
 private Map<String,JSONArray> cachedDeclarations;
 private List<ExtensionPolicy.Candidate> cachedCandidates;
 private Map<String,List<String>> cachedOrders;
 FaceclawExtensions(SharedPreferences prefs,Owners owners) { this.prefs=prefs; this.owners=owners; }
 long generation() { return generation; }
 void changed() { generation++; cachedDeclarations=null; cachedCandidates=null; cachedOrders=null; }
 boolean publish(String component,JSONArray declarations) {
  try {
   String pin=owners.approved().get(component); if(pin==null) return false;
   Map<String,JSONArray> existing=declarations(); if(!existing.containsKey(component)&&existing.size()>=8) return false;
   JSONArray valid=ExtensionContract.declarations(declarations);
   String value=Protocol.object("pin",pin,"declarations",valid).toString();
   if(value.equals(prefs.getString(component+":extensions",""))) return true;
   SharedPreferences.Editor edit=prefs.edit().putString(component+":extensions",value);
   Map<String,List<String>> existingOrders=orders();
   for(int i=0;i<valid.length();i++) {
    String feature=valid.getJSONObject(i).getString("feature"); List<String> order=new ArrayList<>(existingOrders.get(feature));
    if(!order.contains(component)) { order.add(component); edit.putString("extension-order:"+feature,new JSONArray(order).toString()); }
   }
   if(!edit.commit()) return false;
   changed(); return true;
  } catch(Exception ignored) { return false; }
 }
 private Map<String,JSONArray> declarations() {
  if(cachedDeclarations!=null) return cachedDeclarations;
  Map<String,JSONArray> result=new TreeMap<>();
  for(Map.Entry<String,String> owner:owners.approved().entrySet()) {
   try {
    JSONObject saved=new JSONObject(prefs.getString(owner.getKey()+":extensions","{}"));
    if(owner.getValue().equals(saved.optString("pin"))) result.put(owner.getKey(),ExtensionContract.declarations(saved.getJSONArray("declarations")));
   } catch(Exception ignored) { /* Invalid or foreign restored state supplies no authority. */ }
  }
  cachedDeclarations=result; return result;
 }
 private List<ExtensionPolicy.Candidate> candidates() {
  if(cachedCandidates!=null) return cachedCandidates;
  List<ExtensionPolicy.Candidate> result=new ArrayList<>();
  for(Map.Entry<String,JSONArray> owner:declarations().entrySet()) for(int i=0;i<owner.getValue().length();i++) {
   JSONObject item=owner.getValue().optJSONObject(i); String feature=item.optString("feature");
   List<String> requires=new ArrayList<>(); JSONArray array=item.optJSONArray("requires");
   for(int n=0;n<array.length();n++) requires.add(array.optString(n));
   result.add(new ExtensionPolicy.Candidate(owner.getKey(),feature,item.optBoolean("enabled"),prefs.getBoolean(owner.getKey()+":extension:"+feature,false),owners.connected(owner.getKey()),requires));
  }
  cachedCandidates=result; return result;
 }
 private Map<String,List<String>> orders() {
  if(cachedOrders!=null) return cachedOrders;
  Map<String,List<String>> result=new HashMap<>();
  for(String feature:ExtensionContract.FEATURES) {
   List<String> order=new ArrayList<>();
   try { JSONArray saved=new JSONArray(prefs.getString("extension-order:"+feature,"[]")); for(int i=0;i<Math.min(saved.length(),64);i++) order.add(saved.getString(i)); } catch(Exception ignored) {}
   result.put(feature,order);
  }
  cachedOrders=result; return result;
 }
 boolean grant(String component,String feature,boolean allowed) {
  if(!owners.approved().containsKey(component)||!ExtensionContract.known(feature)||!declarations().containsKey(component)||declaration(component,feature)==null) return false;
  if(!prefs.edit().putBoolean(component+":extension:"+feature,allowed).commit()) return false;
  changed(); return true;
 }
 boolean prioritize(String feature,String component) {
  if(declaration(component,feature)==null) return false;
  List<String> order=orderedComponents(feature); order.remove(component); order.add(0,component);
  if(!prefs.edit().putString("extension-order:"+feature,new JSONArray(order).toString()).commit()) return false;
  changed(); return true;
 }
 List<String> orderedComponents(String feature) {
  List<String> result=new ArrayList<>();
  for(ExtensionPolicy.Candidate candidate:candidates()) if(candidate.feature.equals(feature)) result.add(candidate.component);
  List<String> order=orders().getOrDefault(feature,Collections.emptyList());
  result.sort(Comparator.comparingInt((String component)->{ int index=order.indexOf(component); return index<0?Integer.MAX_VALUE:index; }).thenComparing(component->component));
  return result;
 }
 JSONObject declaration(String component,String feature) {
  JSONArray values=declarations().get(component); if(values==null) return null;
  for(int i=0;i<values.length();i++) if(feature.equals(values.optJSONObject(i).optString("feature"))) return values.optJSONObject(i);
  return null;
 }
 boolean granted(String component,String feature) { return declaration(component,feature)!=null&&prefs.getBoolean(component+":extension:"+feature,false); }
 ExtensionPolicy.Candidate winner(String feature) { return ExtensionPolicy.winner(feature,candidates(),orders()); }
 boolean controls(String component,String feature) { ExtensionPolicy.Candidate winner=winner(feature); return winner!=null&&winner.component.equals(component)&&winner.available; }
 JSONObject snapshot() {
  JSONArray features=new JSONArray(); List<ExtensionPolicy.Candidate> candidates=candidates(); Map<String,List<String>> orders=orders();
  for(String feature:ExtensionContract.FEATURES) {
   ExtensionPolicy.Candidate winner=ExtensionPolicy.winner(feature,candidates,orders); JSONArray contenders=new JSONArray();
   for(String component:orderedComponents(feature)) {
    JSONObject item=declaration(component,feature);
    contenders.put(Protocol.object("component",component,"enabled",item.optBoolean("enabled"),"granted",granted(component,feature),"connected",owners.connected(component)));
   }
   boolean live=ExtensionContract.live(feature), available=ExtensionPolicy.available(feature,candidates,orders);
   features.put(Protocol.object("feature",feature,"component",winner==null?"":winner.component,"configuration",winner==null?new JSONObject():declaration(winner.component,feature).optJSONObject("configuration"),"live",live,"available",available,"generation",generation,"contenders",contenders));
  }
  return Protocol.object("version",ExtensionContract.VERSION,"generation",generation,"features",features);
 }
}
