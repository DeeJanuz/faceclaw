package com.faceclaw.sdk;

import org.json.JSONObject;

public final class FaceclawInputEvent {
 public final String type,source; public final long timestampMs; public final int x,y; public final JSONObject extras;
 public FaceclawInputEvent(JSONObject data) {
  type=data.optString("type");source=data.optString("source");timestampMs=data.optLong("timestampMs");
  x=data.optInt("x",-1);y=data.optInt("y",-1);extras=data;
 }
}
