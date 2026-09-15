package com.faceclaw.sdk;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class AppIndependenceCatalogTest {
 private static JSONObject valid(){return Protocol.object("appIndependence",Protocol.object("contractVersion",1,"epoch",7,"features",new JSONArray().put(Protocol.object("id","window.policy","version",1,"limits",Protocol.object("maxPolicyBytes",4096)))));}
 @Test public void absentCatalogIsDistinctFromSupported(){AppIndependenceCatalog catalog=AppIndependenceCatalog.fromCapabilities(new JSONObject());assertEquals(AppIndependenceCatalog.State.ABSENT,catalog.state);assertFalse(catalog.isSupported());assertFalse(catalog.supports("window.policy",1));}
 @Test public void validCatalogIsImmutableAndSeparatesSupportFromGrant() throws Exception {JSONObject capabilities=valid();AppIndependenceCatalog catalog=AppIndependenceCatalog.fromCapabilities(capabilities);assertEquals(AppIndependenceCatalog.State.VALID,catalog.state);assertTrue(catalog.isSupported());assertTrue(catalog.supports("window.policy",1));assertFalse(catalog.supports("window.policy",2));assertEquals(Long.valueOf(4096),catalog.feature("window.policy").limits.get("maxPolicyBytes"));capabilities.getJSONObject("appIndependence").getJSONArray("features").getJSONObject(0).put("version",9);assertEquals(1,catalog.feature("window.policy").version);try{catalog.features.clear();fail("features must be immutable");}catch(UnsupportedOperationException expected){}}
 @Test public void unknownFeatureRemainsRepresentableButMalformedCatalogIsUnsupported() throws Exception {JSONObject capabilities=valid();capabilities.getJSONObject("appIndependence").getJSONArray("features").put(Protocol.object("id","future.feature","version",3,"limits",new JSONObject()));AppIndependenceCatalog catalog=AppIndependenceCatalog.fromCapabilities(capabilities);assertEquals(AppIndependenceCatalog.State.VALID,catalog.state);assertTrue(catalog.supports("future.feature",3));capabilities.getJSONObject("appIndependence").put("epoch",0);assertEquals(AppIndependenceCatalog.State.MALFORMED,AppIndependenceCatalog.fromCapabilities(capabilities).state);}
 @Test public void duplicateAndMalformedFeaturesFailClosed() throws Exception {JSONObject duplicate=valid();duplicate.getJSONObject("appIndependence").getJSONArray("features").put(Protocol.object("id","window.policy","version",1,"limits",new JSONObject()));assertEquals(AppIndependenceCatalog.State.MALFORMED,AppIndependenceCatalog.fromCapabilities(duplicate).state);JSONObject wrong=valid();wrong.getJSONObject("appIndependence").put("features",new JSONArray().put("bad"));assertEquals(AppIndependenceCatalog.State.MALFORMED,AppIndependenceCatalog.fromCapabilities(wrong).state);}
}
