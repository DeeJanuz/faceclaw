package com.faceclaw.sdk;

import org.junit.Test;
import static org.junit.Assert.*;

public class SurfaceValidationTest {
 @Test public void unopenedSurfaceDoesNotAllocateAnInvalidPool(){
  RenderSurface surface=new RenderSurface(null,"window");
  surface.configure(0,0,7,true,true);
  assertEquals(0,surface.width());
  assertEquals(0,surface.height());
  assertEquals(7,surface.generation());
  assertFalse(surface.visible());
 }
 @Test public void oversizedSnapshotIsKeptDisconnected(){
  RenderSurface surface=new RenderSurface(null,"window");
  surface.configure(Protocol.MAX_WIDTH+1,Protocol.MAX_HEIGHT,8,true,true);
  assertEquals(0,surface.width());
  assertFalse(surface.visible());
 }
}
