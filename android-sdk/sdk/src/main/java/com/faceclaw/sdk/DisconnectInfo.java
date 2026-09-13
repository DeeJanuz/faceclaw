package com.faceclaw.sdk;

/** Classified session loss. Details are bounded and contain no application content. */
public final class DisconnectInfo {
 public enum Reason { BINDER_DIED, HOST_STOPPED, IDENTITY_CHANGED, REVOKED, UPDATE_REQUIRED, PROTOCOL_ABUSE, TRANSPORT_ERROR, UNKNOWN }
 public final Reason reason; public final boolean recoverable; public final String detail;
 public DisconnectInfo(Reason reason,boolean recoverable,String detail) {
  this.reason=reason;this.recoverable=recoverable;this.detail=detail==null?"":detail.substring(0,Math.min(256,detail.length()));
 }
}
