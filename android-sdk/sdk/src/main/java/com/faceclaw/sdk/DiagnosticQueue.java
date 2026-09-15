package com.faceclaw.sdk;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Coalesces diagnostics while the main looper is busy; at most one posted drain. */
final class DiagnosticQueue {
 private final Consumer<Runnable> scheduler;
 private final Map<String,Runnable> pending=new LinkedHashMap<>();
 private boolean scheduled;
 DiagnosticQueue(Consumer<Runnable> scheduler){this.scheduler=scheduler;}
 synchronized void offer(String key,Runnable callback){
  if(pending.containsKey(key)||pending.size()>=32)return;
  pending.put(key,callback);
  if(!scheduled){scheduled=true;scheduler.accept(this::drain);}
 }
 private void drain(){
  Runnable[] batch;
  synchronized(this){batch=pending.values().toArray(new Runnable[0]);pending.clear();scheduled=false;}
  for(Runnable callback:batch)try{callback.run();}catch(RuntimeException ignored){}
 }
 synchronized void clear(){pending.clear();}
}
