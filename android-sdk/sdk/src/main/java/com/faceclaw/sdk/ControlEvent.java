package com.faceclaw.sdk;

import org.json.JSONObject;

/** A bounded non-rendering host control. Rendering lifecycle is represented by typed surface state. */
public final class ControlEvent {
 public final String type; public final JSONObject data;
 public ControlEvent(String type,JSONObject data) { this.type=type==null?"":type;this.data=data==null?new JSONObject():data; }
}
