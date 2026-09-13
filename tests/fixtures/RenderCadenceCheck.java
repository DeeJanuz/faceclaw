package com.faceclaw.app;
public final class RenderCadenceCheck {
 public static void main(String[] args) {
  if(RenderCadence.remainingDelay(100,0,48)!=0)throw new AssertionError("first frame delayed");
  if(RenderCadence.remainingDelay(130,100,48)!=18)throw new AssertionError("render work not deducted");
  if(RenderCadence.remainingDelay(165,100,48)!=0)throw new AssertionError("slow rendering adds another sleep");
  if(RenderCadence.remainingDelay(110,100,24)!=14)throw new AssertionError("transport period ignored");
 }
}
