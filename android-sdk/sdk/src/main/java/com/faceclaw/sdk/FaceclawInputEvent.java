package com.faceclaw.sdk;

import android.os.Parcel;
import android.os.Parcelable;
import org.json.JSONObject;

public final class FaceclawInputEvent implements Parcelable {
 public final String type,source,traceId; public final long timestampMs; public final int x,y; public final JSONObject extras;
 public FaceclawInputEvent(JSONObject data) {
  type=data.optString("type");source=data.optString("source");timestampMs=data.optLong("timestampMs");
  x=data.optInt("x",-1);y=data.optInt("y",-1);traceId=data.optString("traceId");extras=data;
 }
 private FaceclawInputEvent(Parcel in){this(parse(in.readString()));}
 private static JSONObject parse(String value){try{return new JSONObject(value==null?"{}":value);}catch(Exception ignored){return new JSONObject();}}
 @Override public void writeToParcel(Parcel out,int flags){out.writeString(extras.toString());}
 @Override public int describeContents(){return 0;}
 public static final Creator<FaceclawInputEvent> CREATOR=new Creator<FaceclawInputEvent>(){public FaceclawInputEvent createFromParcel(Parcel in){return new FaceclawInputEvent(in);}public FaceclawInputEvent[] newArray(int size){return new FaceclawInputEvent[size];}};
}
