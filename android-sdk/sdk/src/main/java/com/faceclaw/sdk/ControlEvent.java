package com.faceclaw.sdk;

import android.os.Parcel;
import android.os.Parcelable;
import org.json.JSONObject;

/** A bounded non-rendering host control. Rendering lifecycle is represented by typed surface state. */
public final class ControlEvent implements Parcelable {
 public final String type; public final JSONObject data; public final byte[] opaquePayload;
 public ControlEvent(String type,JSONObject data){this(type,data,null);}
 public ControlEvent(String type,JSONObject data,byte[] opaquePayload){this.type=type==null?"":type;this.data=data==null?new JSONObject():data;this.opaquePayload=opaquePayload==null?null:opaquePayload.clone();if(this.type.length()>128||this.data.toString().length()>Protocol.MAX_JSON||(this.opaquePayload!=null&&this.opaquePayload.length>4096))throw new IllegalArgumentException("Control event exceeds negotiated bounds");}
 private ControlEvent(Parcel in){type=in.readString();JSONObject parsed;try{String json=in.readString();parsed=new JSONObject(json==null?"{}":json);}catch(Exception ignored){parsed=new JSONObject();}data=parsed;opaquePayload=in.createByteArray();}
 @Override public void writeToParcel(Parcel out,int flags){out.writeString(type);out.writeString(data.toString());out.writeByteArray(opaquePayload);}
 @Override public int describeContents(){return 0;}
 public static final Creator<ControlEvent> CREATOR=new Creator<ControlEvent>(){public ControlEvent createFromParcel(Parcel in){return new ControlEvent(in);}public ControlEvent[] newArray(int size){return new ControlEvent[size];}};
}
