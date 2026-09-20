package com.faceclaw.sdk;

import org.json.JSONObject;
import java.util.Arrays;
import java.util.function.Consumer;

/** One host-presented text composer. A confirmed result represents one physical review gesture. */
public final class ComposerSession {
 public enum Purpose { GENERIC, MESSAGE }
 public final String id;
 private final AppControls.Transport transport;
 private final Consumer<JSONObject> listener;
 private boolean terminal;
 ComposerSession(String id,AppControls.Transport transport,Consumer<JSONObject> listener){this.id=id;this.transport=transport;this.listener=listener;}
 public synchronized boolean terminal(){return terminal;}
 public synchronized void cancel(){if(!terminal){transport.send("composer-cancel",Protocol.object("composerId",id));event(Protocol.object("composerId",id,"status","cancelled","reason","app_cancelled"));}}
 synchronized void event(JSONObject data){
  if(terminal||!id.equals(data.optString("composerId")))return;
  String status=data.optString("status");if(Arrays.asList("confirmed","accepted","cancelled","rejected","expired","unknown").contains(status))terminal=true;
  listener.accept(IndependenceProtocol.copy(data));
 }
}
