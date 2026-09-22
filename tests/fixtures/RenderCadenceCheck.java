package com.faceclaw.app;
public final class RenderCadenceCheck {
 public static void main(String[] args) {
  if(RenderCadence.requestedPeriod(24,17)!=17)throw new AssertionError("fast app preference ignored");
  if(RenderCadence.requestedPeriod(48,17)!=48)throw new AssertionError("backlog backpressure bypassed");
  if(RenderCadence.requestedPeriod(100,17)!=100)throw new AssertionError("unavailable transport bypassed");
  if(RenderCadence.requestedPeriod(24,0)!=24)throw new AssertionError("legacy cadence changed");
  if(RenderCadence.requestedPeriod(24,-1)!=24)throw new AssertionError("invalid preference changed cadence");
  if(RenderCadence.requestedPeriod(0,1)!=17)throw new AssertionError("unbounded fast cadence");
  if(RenderCadence.requestedPeriod(0,2000)!=1000)throw new AssertionError("unbounded slow cadence");
  if(RenderCadence.remainingDelay(100,0,48)!=0)throw new AssertionError("first frame delayed");
  if(RenderCadence.remainingDelay(130,100,48)!=18)throw new AssertionError("render work not deducted");
  if(RenderCadence.remainingDelay(165,100,48)!=0)throw new AssertionError("slow rendering adds another sleep");
  if(RenderCadence.remainingDelay(110,100,24)!=14)throw new AssertionError("transport period ignored");
 }
}
