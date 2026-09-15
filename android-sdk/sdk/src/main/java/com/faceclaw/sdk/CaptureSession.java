package com.faceclaw.sdk;

import org.json.JSONObject;
import java.util.function.Consumer;

/** One draft-only capture. Completion never grants message-send authority. */
public final class CaptureSession {
 public enum Purpose { GENERIC, MESSAGE, SEARCH }
 public final String id;
 private final AppControls.Transport transport;
 private final Consumer<JSONObject> listener;
 private boolean terminal;
 CaptureSession(String id,AppControls.Transport transport,Consumer<JSONObject> listener){this.id=id;this.transport=transport;this.listener=listener;}
 public synchronized boolean terminal(){return terminal;}
 public synchronized void finish(){if(!terminal)transport.send("capture-finish",Protocol.object("captureId",id));}
 public synchronized void cancel(){if(!terminal){transport.send("capture-cancel",Protocol.object("captureId",id));event("capture-status",Protocol.object("captureId",id,"status","cancelled"));}}
 synchronized void event(String type,JSONObject data){
  if(terminal||!id.equals(data.optString("captureId")))return;
  String state=data.optString("status");if(type.equals("capture-status")&&java.util.Arrays.asList("complete","cancelled","rejected").contains(state))terminal=true;
  JSONObject copy=IndependenceProtocol.copy(data);try{copy.put("type",type);}catch(Exception ignored){}listener.accept(copy);
 }
}
