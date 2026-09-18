package com.faceclaw.sdk;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Regression coverage for local render-credit ownership around snapshots and leases. */
public class RenderSurfaceRecoveryTest {
 private static final int WIDTH = 8;
 private static final int HEIGHT = 4;

 private static RenderSurface surface() throws Exception {
  RenderSurface surface = new RenderSurface(new FaceclawSession(null, null), "window");
  set(surface, "width", WIDTH);
  set(surface, "height", HEIGHT);
  set(surface, "generation", 7L);
  set(surface, "visible", true);
  set(surface, "screenOn", true);
  Class<?> slotType = Class.forName("com.faceclaw.sdk.RenderSurface$Slot");
  Constructor<?> constructor = slotType.getDeclaredConstructor(long.class, int.class, android.os.SharedMemory.class, ByteBuffer.class);
  constructor.setAccessible(true);
  Object slot = constructor.newInstance(7L, 0, null, ByteBuffer.allocate(Protocol.FRAME_HEADER_BYTES + WIDTH * HEIGHT));
  Object slots = Array.newInstance(slotType, 1);
  Array.set(slots, 0, slot);
  set(surface, "slots", slots);
  return surface;
 }

 private static void set(Object target, String name, Object value) throws Exception {
  Field field = target.getClass().getDeclaredField(name);
  field.setAccessible(true);
  field.set(target, value);
 }

 private static Object get(Object target, String name) throws Exception {
  Field field = target.getClass().getDeclaredField(name);
  field.setAccessible(true);
  return field.get(target);
 }

 @Test public void sceneResultCanCompleteAfterNewerLifecycleState() throws Exception {
  FaceclawSession session = new FaceclawSession(null, null);
  RenderSurface surface = session.windowSurface();
  set(surface, "generation", 7L);
  SceneController scene = surface.scene();
  set(scene, "pendingVersion", 3L);
  set(scene, "pendingAuthorityEpoch", surface.authorityEpoch());
  set(scene, "pendingNodes", new LinkedHashMap<Long, SceneNode>());
  set(scene, "pendingOrder", new ArrayList<Long>());

  StateOrder order = (StateOrder) get(session, "stateOrder");
  assertTrue(order.accept(10L));
  JSONObject result = Protocol.object("stateRevision", 9L, "surfaceId", "window",
      "generation", 7L, "sceneVersion", 3L, "accepted", true);
  session.applyControl(new ControlEvent("scene-result", result));

  assertEquals(0L, scene.pendingVersion());
  assertEquals(3L, scene.acceptedVersion());
 }

 @Test public void unchangedSnapshotPreservesGrantedCredit() throws Exception {
  RenderSurface surface = surface();
  RenderCredit credit = new RenderCredit("window", 7L, 11L, 0L, 8, "trace");
  surface.grant(credit);
  surface.configure(WIDTH, HEIGHT, 7L, true, true);
  assertSame(credit, get(surface, "credit"));
 }

 @Test public void abandonedCurrentLeaseReturnsItsCredit() throws Exception {
  RenderSurface surface = surface();
  RenderCredit credit = new RenderCredit("window", 7L, 12L, 0L, 8, "trace");
  surface.grant(credit);
  java.lang.reflect.Method leaseLocked = RenderSurface.class.getDeclaredMethod("leaseLocked");
  leaseLocked.setAccessible(true);
  FrameLease lease = (FrameLease) leaseLocked.invoke(surface);
  assertNull(get(surface, "credit"));
  lease.close();
  assertSame(credit, get(surface, "credit"));
 }

 @Test public void localSceneReservationCanBeReturnedBeforeDispatch() throws Exception {
  RenderSurface surface = surface();
  RenderCredit credit = new RenderCredit("window", 7L, 14L, 0L, 8, "trace");
  surface.grant(credit);
  RenderSurface.CreditReservation reservation = surface.reserveSceneCredit();
  assertNotNull(reservation);
  assertNull(get(surface, "credit"));
  assertTrue(surface.restoreSceneCredit(reservation));
  assertSame(credit, get(surface, "credit"));
 }

 @Test public void leaseClosedAfterVisibilityRevocationCannotRestoreCredit() throws Exception {
  RenderSurface surface = surface();
  RenderCredit credit = new RenderCredit("window", 7L, 13L, 0L, 8, "trace");
  surface.grant(credit);
  java.lang.reflect.Method leaseLocked = RenderSurface.class.getDeclaredMethod("leaseLocked");
  leaseLocked.setAccessible(true);
  FrameLease lease = (FrameLease) leaseLocked.invoke(surface);
  surface.setVisibility(false, false);
  lease.close();
  assertNull(get(surface, "credit"));
 }

 @Test public void disconnectedSubmitReturnsCreditAfterLocalPreDispatchFailure() throws Exception {
  RenderSurface surface = surface();
  RenderCredit credit = new RenderCredit("window", 7L, 15L, 0L, 8, "trace");
  surface.grant(credit);
  ByteBuffer pixels = ByteBuffer.allocate(WIDTH * HEIGHT);
  assertFalse(surface.submitGray8(pixels, FrameMetadata.builder(1L, 1L).fullDamage(WIDTH, HEIGHT).build()));
  assertSame(credit, get(surface, "credit"));
  assertFalse(surface.hasWriters());
 }

 @Test public void rendererFailureSuspendsUntilExplicitRecoveryAndDoesNotLoseCredit() throws Exception {
  RenderSurface surface = surface();
  RenderCredit credit = new RenderCredit("window", 7L, 16L, 0L, 8, "trace");
  surface.grant(credit);
  surface.setRasterRenderer(Runnable::run, (lease, request) -> { throw new IllegalStateException("renderer"); });
  assertTrue(surface.rendererSuspended());
  assertSame(credit, get(surface, "credit"));
  surface.setRasterRenderer(Runnable::run, (lease, request) -> lease.submit(FrameMetadata.builder(2L, 2L).fullDamage(WIDTH, HEIGHT).build()));
  surface.recoverRenderer();
  assertFalse(surface.rendererSuspended());
  assertSame(credit, get(surface, "credit"));
  assertFalse(surface.hasWriters());
 }
}
