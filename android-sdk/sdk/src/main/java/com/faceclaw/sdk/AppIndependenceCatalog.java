package com.faceclaw.sdk;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Immutable support catalog for the app-independence contract. */
public final class AppIndependenceCatalog {
 public static final int CONTRACT_VERSION=1,MAX_FEATURES=64,MAX_LIMITS=16,MAX_BYTES=49152;
 private static final String APP_KEY="appIndependence";
 private static final String ID="[a-z][a-z0-9]*(?:[._-][a-z0-9]+)+";
 private static final String KEY="[A-Za-z][A-Za-z0-9]*(?:[._-][A-Za-z0-9]+)*";
 public enum State { ABSENT,VALID,MALFORMED }
 public static final class Feature {
  public final String id; public final int version; public final Map<String,Long> limits;
  private Feature(String id,int version,Map<String,Long> limits){this.id=id;this.version=version;this.limits=Collections.unmodifiableMap(new LinkedHashMap<>(limits));}
 }
 public final State state; public final int contractVersion; public final long epoch; public final List<Feature> features;
 private AppIndependenceCatalog(State state,int contractVersion,long epoch,List<Feature> features){this.state=state;this.contractVersion=contractVersion;this.epoch=epoch;this.features=Collections.unmodifiableList(new ArrayList<>(features));}
 /** Parse without exposing host-owned JSON or throwing from a Binder callback. */
 public static AppIndependenceCatalog fromCapabilities(JSONObject capabilities){
  if(capabilities==null)return absent(); Object raw=capabilities.opt(APP_KEY); if(raw==null||raw==JSONObject.NULL)return absent(); if(!(raw instanceof JSONObject))return malformed();
  try{return parse((JSONObject)raw);}catch(Exception invalid){return malformed();}
 }
 public boolean isSupported(){return state==State.VALID&&contractVersion==CONTRACT_VERSION;}
 public boolean supports(String featureId,int minimumVersion){if(!isSupported()||featureId==null||minimumVersion<1)return false;for(Feature feature:features)if(feature.id.equals(featureId)&&feature.version>=minimumVersion)return true;return false;}
 public Feature feature(String featureId){if(featureId==null)return null;for(Feature feature:features)if(feature.id.equals(featureId))return feature;return null;}
 private static AppIndependenceCatalog parse(JSONObject supplied)throws Exception{
  if(supplied.toString().getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw new IllegalArgumentException("Catalog is too large");
  exact(supplied,"contractVersion","features","epoch"); Object rawVersion=supplied.opt("contractVersion"),rawFeatures=supplied.opt("features"),rawEpoch=supplied.opt("epoch");
  if(!(rawVersion instanceof Number)||!(rawFeatures instanceof JSONArray)||!(rawEpoch instanceof Number))throw new IllegalArgumentException("Invalid catalog fields");
  int version=((Number)rawVersion).intValue();long epoch=((Number)rawEpoch).longValue();if(version<1||epoch<1||((Number)rawVersion).doubleValue()!=version||((Number)rawEpoch).doubleValue()!=epoch)throw new IllegalArgumentException("Invalid catalog version");
  JSONArray suppliedFeatures=(JSONArray)rawFeatures;if(suppliedFeatures.length()>MAX_FEATURES)throw new IllegalArgumentException("Too many catalog features"); ArrayList<Feature> features=new ArrayList<>();HashSet<String> ids=new HashSet<>();
  for(int i=0;i<suppliedFeatures.length();i++){
   Object raw=suppliedFeatures.get(i);if(!(raw instanceof JSONObject))throw new IllegalArgumentException("Invalid catalog feature");JSONObject item=(JSONObject)raw;exact(item,"id","version","limits");Object rawId=item.opt("id"),rawFeatureVersion=item.opt("version"),rawLimits=item.opt("limits");
   if(!(rawId instanceof String)||!((String)rawId).matches(ID)||((String)rawId).length()>128||!ids.add((String)rawId)||!(rawFeatureVersion instanceof Number)||((Number)rawFeatureVersion).doubleValue()!=((Number)rawFeatureVersion).intValue()||((Number)rawFeatureVersion).intValue()<1||!(rawLimits instanceof JSONObject))throw new IllegalArgumentException("Invalid catalog feature");
   JSONObject suppliedLimits=(JSONObject)rawLimits;if(suppliedLimits.length()>MAX_LIMITS)throw new IllegalArgumentException("Too many catalog limits");LinkedHashMap<String,Long> limits=new LinkedHashMap<>();for(Iterator<String> keys=suppliedLimits.keys();keys.hasNext();){String key=keys.next();Object value=suppliedLimits.get(key);if(!key.matches(KEY)||!(value instanceof Number)||((Number)value).doubleValue()!=((Number)value).longValue()||((Number)value).longValue()<0)throw new IllegalArgumentException("Invalid catalog limit");limits.put(key,((Number)value).longValue());}
   features.add(new Feature((String)rawId,((Number)rawFeatureVersion).intValue(),limits));
  }
  return new AppIndependenceCatalog(State.VALID,version,epoch,features);
 }
 private static void exact(JSONObject object,String... expected){HashSet<String> allowed=new HashSet<>(Arrays.asList(expected));for(Iterator<String> keys=object.keys();keys.hasNext();)if(!allowed.contains(keys.next()))throw new IllegalArgumentException("Unknown catalog field");for(String key:expected)if(!object.has(key)||object.isNull(key))throw new IllegalArgumentException("Missing catalog field");}
 private static AppIndependenceCatalog absent(){return new AppIndependenceCatalog(State.ABSENT,0,0,Collections.emptyList());}
 private static AppIndependenceCatalog malformed(){return new AppIndependenceCatalog(State.MALFORMED,0,0,Collections.emptyList());}
}
