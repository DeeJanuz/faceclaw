package com.faceclaw.sdk;

import org.json.JSONObject;

/** Immutable SDK convenience state derived from one complete host snapshot. */
final class HostSnapshotState {
 final JSONObject grants,sharedStyle,extensions,capabilities;
 final boolean messagingAllowed,notificationReplyAllowed;
 private HostSnapshotState(JSONObject grants,JSONObject sharedStyle,JSONObject extensions,JSONObject capabilities){
  this.grants=copy(grants);this.sharedStyle=copy(sharedStyle);this.extensions=copy(extensions);this.capabilities=copy(capabilities);
  messagingAllowed=this.grants.optBoolean("messaging",false);
  notificationReplyAllowed=this.grants.optBoolean("notifications",false)&&this.grants.optBoolean("dictation",false)&&this.capabilities.optBoolean("notificationReplies",false);
 }
 static HostSnapshotState from(HostSnapshot snapshot){return snapshot==null?from(new JSONObject(),new JSONObject(),new JSONObject(),new JSONObject()):from(snapshot.grants,snapshot.sharedStyle,snapshot.extensions,snapshot.capabilities);}
 static HostSnapshotState from(JSONObject grants,JSONObject sharedStyle,JSONObject extensions,JSONObject capabilities){return new HostSnapshotState(grants,sharedStyle,extensions,capabilities);}
 private static JSONObject copy(JSONObject value){try{return value==null?new JSONObject():new JSONObject(value.toString());}catch(Exception ignored){return new JSONObject();}}
}
