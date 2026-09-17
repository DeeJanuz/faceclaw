package com.faceclaw.sdk;

import static org.junit.Assert.*;
import org.junit.Test;

public class FrameAnimatorTest {
 @Test public void limitsLateTranslationWithoutOvershootingEitherDirection() {
  assertEquals(164,FrameAnimator.limitTranslationStep(100,500,64));
  assertEquals(36,FrameAnimator.limitTranslationStep(100,-500,64));
  assertEquals(125,FrameAnimator.limitTranslationStep(100,125,64));
  assertEquals(75,FrameAnimator.limitTranslationStep(100,75,64));
 }

 @Test public void rejectsNonPositiveStep() {
  try{FrameAnimator.limitTranslationStep(0,1,0);fail("Accepted zero step");}
  catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("positive"));}
 }
}
