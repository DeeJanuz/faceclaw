package com.faceclaw.sdk;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Used by real render dispatch and deterministic failure tests. */
final class RenderFailureGate {
 interface Work {void run() throws Exception;}
 private boolean suspended;
 private long epoch;
 synchronized boolean suspended(){return suspended;}
 synchronized void recover(){suspended=false;epoch++;}
 void dispatch(Executor executor,Work work,Runnable release,Consumer<Boolean> failure){
  final long started;
  synchronized(this){if(suspended){release.run();return;}started=epoch;}
  try{executor.execute(()->{
   synchronized(this){if(suspended||epoch!=started){release.run();return;}}
   try{work.run();}catch(Exception error){fail(started,release,failure,false);}
  });}catch(RuntimeException rejected){fail(started,release,failure,true);}
 }
 private void fail(long started,Runnable release,Consumer<Boolean> failure,boolean rejected){
  boolean report;
  synchronized(this){report=epoch==started&&!suspended;if(report)suspended=true;}
  release.run();if(report)failure.accept(rejected);
 }
}
