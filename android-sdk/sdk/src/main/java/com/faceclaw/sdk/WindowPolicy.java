package com.faceclaw.sdk;

import org.json.*;

/** App-local preferences; host lock, focus and escape gestures always take precedence. */
public final class WindowPolicy {
 public enum Height { MIN, MEDIUM, MAX }
 private final JSONObject value;
 public WindowPolicy(Height height,boolean menuAvailable,boolean compact,boolean hostBack,boolean claimLongPress,boolean directional){
  JSONArray claims=new JSONArray();if(claimLongPress)claims.put("long-press");if(directional)claims.put("directional");
  value=Protocol.object("preferredHeightMode",height.name().toLowerCase(java.util.Locale.ROOT),"preferredWidthMode","display","chrome",compact?"compact":"host","menuAvailable",menuAvailable,"back",hostBack?"host-only":"app-then-host","gestureClaims",claims);
 }
 public JSONObject toJson(){return IndependenceProtocol.copy(value);}
}
