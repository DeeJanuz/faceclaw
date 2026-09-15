package com.faceclaw.sdk;

import org.json.JSONObject;
import java.util.*;

/** Bounded per-session host admission and duplicate retention, driven by boot-relative time. */
public final class ControlLedger {
 public static final class Admission {public final boolean execute;public final JSONObject result;Admission(boolean execute,JSONObject result){this.execute=execute;this.result=result;}}
 private static final class Entry {final String body;final long admitted,deadline;JSONObject result;Entry(JSONObject data,long now,JSONObject result){body=data.toString();admitted=now;deadline=data.optLong("expiresAtElapsedMs");this.result=result;}}
 private final Map<String,Entry> entries=new LinkedHashMap<>();
 private long catalogEpoch;
 public ControlLedger(long catalogEpoch){this.catalogEpoch=catalogEpoch;}
 public synchronized void catalogEpoch(long epoch){catalogEpoch=epoch;}
 public synchronized Admission admit(JSONObject request,long now,long generation,Set<String> negotiated){
  prune(now);String id=request.optString("requestId");
  try{IndependenceProtocol.request(request);}catch(IllegalArgumentException error){return new Admission(false,result(request,"rejected",error.getMessage()));}
  Entry existing=entries.get(id);
  if(existing!=null)return new Admission(false,existing.body.equals(request.toString())?IndependenceProtocol.copy(existing.result):result(request,"rejected","conflict"));
  String reason="";long deadline=request.optLong("expiresAtElapsedMs");String op=request.optString("operation");
  if(deadline<=now||deadline-now>IndependenceProtocol.RETENTION_MS)reason="expired";
  else if(!negotiated.contains(IndependenceProtocol.feature(op)))reason="unsupported";
  else if(!op.equals("window.open")&&request.optLong("windowGeneration")!=generation)reason="stale_window";
  else if(entries.size()>=64||entries.values().stream().filter(e->e.result.optString("state").equals("accepted")).count()>=32)reason="rate_limited";
  JSONObject result=result(request,reason.isEmpty()?"accepted":"rejected",reason);
  if(!reason.equals("rate_limited"))entries.put(id,new Entry(request,now,result));
  return new Admission(reason.isEmpty(),result);
 }
 public synchronized JSONObject complete(String id,String state,String reason){
  Entry e=entries.get(id);if(e==null||!e.result.optString("state").equals("accepted"))return null;
  if(!Arrays.asList("applied","rejected","cancelled","unknown","expired").contains(state))throw new IllegalArgumentException("Invalid terminal state");
  e.result=Protocol.object("requestId",id,"operation",e.result.optString("operation"),"state",state,"reason",reason,"windowGeneration",e.result.optLong("windowGeneration"),"revision",e.result.optLong("revision"),"catalogEpoch",catalogEpoch);return IndependenceProtocol.copy(e.result);
 }
 public synchronized boolean current(String id,long now){Entry e=entries.get(id);return e!=null&&e.deadline>now&&e.result.optString("state").equals("accepted");}
 public synchronized List<JSONObject> cancelAll(){List<JSONObject> results=new ArrayList<>();for(String id:entries.keySet()){JSONObject r=complete(id,"unknown","stale_session");if(r!=null)results.add(r);}return results;}
 public synchronized List<JSONObject> expire(long now){List<JSONObject> results=new ArrayList<>();for(Map.Entry<String,Entry> entry:entries.entrySet())if(entry.getValue().deadline<=now){JSONObject result=complete(entry.getKey(),"unknown","expired");if(result!=null)results.add(result);}prune(now);return results;}
 private void prune(long now){entries.entrySet().removeIf(e->now-e.getValue().admitted>=IndependenceProtocol.RETENTION_MS&&!e.getValue().result.optString("state").equals("accepted"));}
 private JSONObject result(JSONObject data,String state,String reason){return Protocol.object("requestId",data.optString("requestId"),"operation",data.optString("operation"),"state",state,"reason",reason,"windowGeneration",data.optLong("windowGeneration"),"revision",data.optLong("revision"),"catalogEpoch",catalogEpoch);}
}
