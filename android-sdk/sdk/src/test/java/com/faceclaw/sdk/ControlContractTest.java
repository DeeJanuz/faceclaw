package com.faceclaw.sdk;

import org.json.*;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class ControlContractTest {
 private static final Set<String> SUPPORTED=new HashSet<>(Arrays.asList("control.result","window.policy"));
 static JSONObject request(String id,long generation,long expires){return Protocol.object("requestId",id,"operation","window.menu","windowGeneration",generation,"revision",1,"expiresAtElapsedMs",expires,"payload",Protocol.object("available",true));}
 @Test public void duplicateActionReusesActualTerminalResultAndConflictingBodyRejects()throws Exception{
  ControlLedger ledger=new ControlLedger(1);JSONObject r=request("r1",3,1000);assertTrue(ledger.admit(r,100,3,SUPPORTED).execute);ledger.complete("r1","applied","");
  ControlLedger.Admission repeat=ledger.admit(r,200,3,SUPPORTED);assertFalse(repeat.execute);assertEquals("applied",repeat.result.getString("state"));
  r.getJSONObject("payload").put("available",false);assertEquals("conflict",ledger.admit(r,200,3,SUPPORTED).result.getString("reason"));
 }
 @Test public void staleExpiredUnsupportedAndQuotaRequestsCannotExecute(){
  ControlLedger ledger=new ControlLedger(1);
  assertEquals("stale_window",ledger.admit(request("old",2,1000),100,3,SUPPORTED).result.optString("reason"));
  assertEquals("expired",ledger.admit(request("late",3,100),100,3,SUPPORTED).result.optString("reason"));
  assertEquals("unsupported",ledger.admit(request("unsupported",3,1000),100,3,Collections.emptySet()).result.optString("reason"));
  for(int i=0;i<32;i++)assertTrue(ledger.admit(request("r"+i,3,1000),100,3,SUPPORTED).execute);
  assertEquals("rate_limited",ledger.admit(request("overflow",3,1000),100,3,SUPPORTED).result.optString("reason"));
  assertEquals(32,ledger.expire(1000).size());assertNull(ledger.complete("r1","applied",""));
 }
 @Test public void invalidBooleanAndUnknownPayloadCannotCrossAdmission()throws Exception{
  JSONObject r=request("r",3,1000);r.getJSONObject("payload").put("available","true");assertFalse(new ControlLedger(1).admit(r,100,3,SUPPORTED).execute);
  r.getJSONObject("payload").put("available",true).put("package","foreign");assertFalse(new ControlLedger(1).admit(r,100,3,SUPPORTED).execute);
 }
 @Test public void requiredNegotiationIsAtomicAndOptionalUnsupportedRemainsExplicit()throws Exception{
  JSONObject d=Protocol.object("contractVersion",1,"epoch",1,"features",new JSONArray().put(Protocol.object("id","control.result","minVersion",1,"required",false,"fallback","legacy-control")).put(Protocol.object("id","future.feature","minVersion",1,"required",true,"fallback","legacy-window")));
  assertEquals("rejected",IndependenceProtocol.negotiate(d,SUPPORTED,1,0).getString("state"));
  d.getJSONArray("features").getJSONObject(1).put("required",false);JSONObject result=IndependenceProtocol.negotiate(d,SUPPORTED,1,0);assertEquals("applied",result.getString("state"));assertEquals("unsupported",result.getJSONArray("features").getJSONObject(1).getString("state"));
 }
 @Test public void droppedReplyIsUnknownAndLateReplyCannotCompleteTwice()throws Exception{
  AtomicLong now=new AtomicLong(100);List<Runnable> timers=new ArrayList<>();List<JSONObject> sent=new ArrayList<>(),results=new ArrayList<>();
  AppControls client=new AppControls((type,data)->{sent.add(data);return true;},now::get,(delay,task)->timers.add(task));
  AppIndependenceCatalog catalog=AppIndependenceCatalog.fromCapabilities(Protocol.object("appIndependence",Protocol.object("contractVersion",1,"epoch",1,"features",new JSONArray().put(Protocol.object("id","control.result","version",1,"limits",new JSONObject())))));
  client.snapshot(catalog,3);client.receive("contract-result",IndependenceProtocol.negotiate(sent.get(0),SUPPORTED,1,0));
  client.request("window.menu",Protocol.object("available",true),1000,results::add);assertTrue(results.isEmpty());
  ControlLedger host=new ControlLedger(1);JSONObject r=sent.get(1);assertTrue(host.admit(r,100,3,SUPPORTED).execute);JSONObject applied=host.complete(r.getString("requestId"),"applied","");
  now.set(1100);timers.get(0).run();client.receive("control-result",applied);assertEquals(1,results.size());assertEquals("unknown",results.get(0).getString("state"));
 }
 @Test public void oldHostGetsNoNewWireMessages(){
  List<JSONObject> sent=new ArrayList<>(),results=new ArrayList<>();AppControls client=new AppControls((type,data)->{sent.add(data);return true;},()->100,(delay,task)->{});
  client.snapshot(AppIndependenceCatalog.fromCapabilities(new JSONObject()),0);client.request("window.menu",Protocol.object("available",true),1000,results::add);assertTrue(sent.isEmpty());assertEquals("unsupported",results.get(0).optString("reason"));
 }
 @Test public void byteLimitCountsMultibytePayloadAndRejectsOneByteOver(){
  JSONObject value=Protocol.object("text","é");int exact=value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;IndependenceProtocol.bytes(value,exact);
  try{IndependenceProtocol.bytes(value,exact-1);fail();}catch(IllegalArgumentException expected){assertEquals("too_large",expected.getMessage());}
 }
}
