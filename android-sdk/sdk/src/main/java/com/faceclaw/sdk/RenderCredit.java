package com.faceclaw.sdk;

public final class RenderCredit {
 public final String surfaceId,traceId; public final long generation,creditId,targetPresentationTimeNanos; public final int maxDamageRects;
 RenderCredit(String surfaceId,long generation,long creditId,long targetPresentationTimeNanos,int maxDamageRects,String traceId) {
  this.surfaceId=surfaceId;this.generation=generation;this.creditId=creditId;this.targetPresentationTimeNanos=targetPresentationTimeNanos;
  this.maxDamageRects=maxDamageRects;this.traceId=traceId==null?"":traceId;
 }
}
