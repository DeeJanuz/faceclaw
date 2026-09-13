package com.faceclaw.sdk;

import android.os.Bundle;
import org.json.JSONObject;

/** One surface captured atomically in a HostSnapshot. */
public final class SurfaceSnapshot {
 public final String id;public final int width,height;public final long generation;public final boolean visible,screenOn;
 SurfaceSnapshot(Bundle value){id=value.getString("id","");width=value.getInt("width");height=value.getInt("height");generation=value.getLong("generation");visible=value.getBoolean("visible");screenOn=value.getBoolean("screenOn",true);}
 public JSONObject toJson(){return Protocol.object("id",id,"width",width,"height",height,"generation",generation,"visible",visible,"screenOn",screenOn);}
}
