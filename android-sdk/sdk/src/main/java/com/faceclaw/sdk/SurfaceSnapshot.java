package com.faceclaw.sdk;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import org.json.JSONObject;

/** One surface captured atomically in a HostSnapshot. */
public final class SurfaceSnapshot implements Parcelable {
 public final String id;public final int width,height;public final long generation;public final boolean visible,screenOn;
 SurfaceSnapshot(Bundle value){id=value.getString("id","");width=value.getInt("width");height=value.getInt("height");generation=value.getLong("generation");visible=value.getBoolean("visible");screenOn=value.getBoolean("screenOn",true);}
 public SurfaceSnapshot(String id,int width,int height,long generation,boolean visible,boolean screenOn){this.id=id;this.width=width;this.height=height;this.generation=generation;this.visible=visible;this.screenOn=screenOn;}
 public JSONObject toJson(){return Protocol.object("id",id,"width",width,"height",height,"generation",generation,"visible",visible,"screenOn",screenOn);}
 private SurfaceSnapshot(Parcel in){this(in.readString(),in.readInt(),in.readInt(),in.readLong(),in.readInt()!=0,in.readInt()!=0);}
 @Override public void writeToParcel(Parcel out,int flags){out.writeString(id);out.writeInt(width);out.writeInt(height);out.writeLong(generation);out.writeInt(visible?1:0);out.writeInt(screenOn?1:0);}
 @Override public int describeContents(){return 0;}
 public static final Creator<SurfaceSnapshot> CREATOR=new Creator<SurfaceSnapshot>(){public SurfaceSnapshot createFromParcel(Parcel in){return new SurfaceSnapshot(in);}public SurfaceSnapshot[] newArray(int size){return new SurfaceSnapshot[size];}};
}
