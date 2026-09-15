package com.faceclaw.sdk;

import org.json.JSONObject;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** Lifecycle regressions for the legacy menu/protection controls. */
public class WindowStateCacheTest {
 private static final class Sent {
  final String type;
  final JSONObject data;
  Sent(String type,JSONObject data){this.type=type;this.data=data;}
 }
 private static final class Harness {
  final List<Sent> sent=new ArrayList<>();
  final FaceclawAppService.WindowStateCache cache=new FaceclawAppService.WindowStateCache((type,data)->{sent.add(new Sent(type,data));return true;});
 }
 private static Sent at(Harness harness,int index){return harness.sent.get(index);}

 @Test public void setBeforeOpenIsReplayedForTheOpenedGeneration() {
  Harness harness=new Harness();
  assertTrue(harness.cache.setMenuAvailable(true));
  assertEquals(1,harness.sent.size());
  harness.cache.observeOpen(7);
  assertEquals(2,harness.sent.size());
  assertEquals("window-menu-state",at(harness,1).type);
  assertTrue(at(harness,1).data.optBoolean("available"));
 }

 @Test public void equalRepeatedValuesAreCoalescedWithinOneGeneration() {
  Harness harness=new Harness();
  harness.cache.observeOpen(7);
  assertTrue(harness.cache.setMenuAvailable(true));
  assertTrue(harness.cache.setMenuAvailable(true));
  assertTrue(harness.cache.setProtected(false));
  assertTrue(harness.cache.setProtected(false));
  assertEquals(2,harness.sent.size());
  assertFalse(at(harness,1).data.optBoolean("protected",true));
 }

 @Test public void closeReopenReplaysMenuButClearsTransientProtection() {
  Harness harness=new Harness();
  harness.cache.observeOpen(7);
  assertTrue(harness.cache.setMenuAvailable(true));
  assertTrue(harness.cache.setProtected(true));
  harness.cache.observeClose();
  harness.cache.observeOpen(8);
  assertTrue(harness.cache.setMenuAvailable(true));
  assertEquals(3,harness.sent.size());
  assertEquals("window-menu-state",at(harness,2).type);
  assertEquals("window-protection",at(harness,1).type);
  assertTrue(at(harness,1).data.optBoolean("protected"));
  for(int i=2;i<harness.sent.size();i++)assertFalse("stale protection must not be replayed",at(harness,i).type.equals("window-protection"));
 }

 @Test public void reconnectReplaysMenuForTheNewGenerationAndResetsProtection() {
  Harness harness=new Harness();
  harness.cache.observeOpen(7);
  assertTrue(harness.cache.setMenuAvailable(false));
  assertTrue(harness.cache.setProtected(true));
  harness.cache.observeDisconnect();
  harness.cache.observeSnapshot(true,11);
  harness.cache.replay();
  assertEquals(3,harness.sent.size());
  assertEquals("window-menu-state",at(harness,2).type);
  assertFalse(at(harness,2).data.optBoolean("available",true));
  assertTrue(harness.cache.setProtected(false));
  assertEquals(4,harness.sent.size());
  assertFalse(at(harness,3).data.optBoolean("protected",true));
 }

 @Test public void explicitFalseIsSentAfterTrueInTheSameGeneration() {
  Harness harness=new Harness();
  harness.cache.observeOpen(3);
  assertTrue(harness.cache.setProtected(true));
  assertTrue(harness.cache.setProtected(false));
  assertEquals(2,harness.sent.size());
  assertTrue(at(harness,0).data.optBoolean("protected"));
  assertFalse(at(harness,1).data.optBoolean("protected",true));
 }
}
