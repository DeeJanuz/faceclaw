package com.faceclaw.sdk;

import org.junit.Test;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class RecoveryTest {
 @Test public void rendererFailureStopsWorkUntilExplicitRecovery(){
  RenderFailureGate gate=new RenderFailureGate();AtomicInteger renders=new AtomicInteger(),released=new AtomicInteger(),reports=new AtomicInteger();
  for(int i=0;i<100;i++)gate.dispatch(Runnable::run,()->{renders.incrementAndGet();throw new Exception();},released::incrementAndGet,rejected->{assertFalse(rejected);reports.incrementAndGet();});
  assertEquals(1,renders.get());assertEquals(100,released.get());assertEquals(1,reports.get());assertTrue(gate.suspended());
  gate.recover();gate.dispatch(Runnable::run,renders::incrementAndGet,released::incrementAndGet,rejected->fail());assertEquals(2,renders.get());
 }
 @Test public void rejectedExecutorReleasesAndReportsOnce(){
  RenderFailureGate gate=new RenderFailureGate();AtomicInteger releases=new AtomicInteger(),reports=new AtomicInteger();
  gate.dispatch(work->{throw new RejectedExecutionException();},()->fail(),releases::incrementAndGet,rejected->{assertTrue(rejected);reports.incrementAndGet();});
  assertEquals(1,releases.get());assertEquals(1,reports.get());assertTrue(gate.suspended());
 }
 @Test public void queuedWorkFromOldRecoveryEpochCannotRun(){
  List<Runnable> tasks=new ArrayList<>();RenderFailureGate gate=new RenderFailureGate();AtomicInteger release=new AtomicInteger();
  gate.dispatch(tasks::add,()->fail(),release::incrementAndGet,rejected->fail());gate.recover();tasks.get(0).run();assertEquals(1,release.get());
 }
 @Test public void diagnosticsRemainBoundedWhileLooperIsBlocked(){
  List<Runnable> scheduled=new ArrayList<>();DiagnosticQueue queue=new DiagnosticQueue(scheduled::add);AtomicInteger delivered=new AtomicInteger();
  for(int i=0;i<10000;i++)queue.offer("failure"+i,delivered::incrementAndGet);
  assertEquals(1,scheduled.size());scheduled.remove(0).run();assertEquals(32,delivered.get());
  queue.offer("duplicate",delivered::incrementAndGet);queue.offer("duplicate",delivered::incrementAndGet);scheduled.remove(0).run();assertEquals(33,delivered.get());
 }
 @Test public void revokedStateCannotBeRestoredByOldOrLegacyDelta(){
  StateOrder order=new StateOrder();assertTrue(order.accept(0));assertTrue(order.accept(4));assertTrue(order.accept(7));
  assertFalse(order.accept(6));assertFalse(order.accept(7));assertFalse(order.accept(0));order.reset();assertTrue(order.accept(1));
 }
}
