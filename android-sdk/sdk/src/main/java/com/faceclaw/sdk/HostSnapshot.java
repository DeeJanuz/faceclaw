package com.faceclaw.sdk;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import org.json.JSONObject;
import java.util.*;

/** Complete atomic host state delivered before the first render credit. */
public final class HostSnapshot implements Parcelable {
 public final int protocolMajor,maxWidth,maxHeight,maxDamageRects,bufferSlots;
 public final boolean screenOn,windowOpen,windowVisible;
 public final long windowGeneration;
 public final int windowWidth,windowHeight;
 public final byte[] restorationToken;
 public final JSONObject grants,sharedStyle,extensions,hostState,capabilities;
 public final java.util.List<SurfaceSnapshot> surfaces;
 public HostSnapshot(Bundle b) {
  protocolMajor=b.getInt("protocolMajor",Protocol.VERSION);maxWidth=b.getInt("maxWidth",Protocol.MAX_WIDTH);
  maxHeight=b.getInt("maxHeight",Protocol.MAX_HEIGHT);maxDamageRects=b.getInt("maxDamageRects",Protocol.MAX_DAMAGE_RECTS);
  bufferSlots=b.getInt("bufferSlots",Protocol.BUFFER_SLOTS);screenOn=b.getBoolean("screenOn",true);
  windowOpen=b.getBoolean("windowOpen");windowVisible=b.getBoolean("windowVisible");windowGeneration=b.getLong("windowGeneration");
  windowWidth=b.getInt("windowWidth");windowHeight=b.getInt("windowHeight");
  byte[] restored=b.getByteArray("restorationToken");restorationToken=restored==null?null:restored.clone();
  grants=json(b,"grants");sharedStyle=json(b,"sharedStyle");extensions=json(b,"extensions");hostState=json(b,"hostState");capabilities=json(b,"capabilities");ArrayList<SurfaceSnapshot> current=new ArrayList<>();ArrayList<Bundle> values=b.getParcelableArrayList("surfaces");if(values!=null)for(Bundle value:values)current.add(new SurfaceSnapshot(value));surfaces=Collections.unmodifiableList(current);
 }
 public Bundle toBundle(){Bundle b=new Bundle();b.putInt("protocolMajor",protocolMajor);b.putInt("maxWidth",maxWidth);b.putInt("maxHeight",maxHeight);b.putInt("maxDamageRects",maxDamageRects);b.putInt("bufferSlots",bufferSlots);b.putBoolean("screenOn",screenOn);b.putBoolean("windowOpen",windowOpen);b.putBoolean("windowVisible",windowVisible);b.putLong("windowGeneration",windowGeneration);b.putInt("windowWidth",windowWidth);b.putInt("windowHeight",windowHeight);b.putByteArray("restorationToken",restorationToken==null?null:restorationToken.clone());b.putString("grants",grants.toString());b.putString("sharedStyle",sharedStyle.toString());b.putString("extensions",extensions.toString());b.putString("hostState",hostState.toString());b.putString("capabilities",capabilities.toString());ArrayList<Bundle> values=new ArrayList<>();for(SurfaceSnapshot surface:surfaces){Bundle value=new Bundle();value.putString("id",surface.id);value.putInt("width",surface.width);value.putInt("height",surface.height);value.putLong("generation",surface.generation);value.putBoolean("visible",surface.visible);value.putBoolean("screenOn",surface.screenOn);values.add(value);}b.putParcelableArrayList("surfaces",values);return b;}
 private static JSONObject json(Bundle b,String key) { try{return new JSONObject(b.getString(key,"{}"));}catch(Exception ignored){return new JSONObject();} }
 public JSONObject toJson() {
  org.json.JSONArray surfaceValues=new org.json.JSONArray();for(SurfaceSnapshot surface:surfaces)surfaceValues.put(surface.toJson());
  return Protocol.object("protocolMajor",protocolMajor,"maxWidth",maxWidth,"maxHeight",maxHeight,
   "maxDamageRects",maxDamageRects,"bufferSlots",bufferSlots,"screenOn",screenOn,
   "windowOpen",windowOpen,"windowVisible",windowVisible,"windowGeneration",windowGeneration,
   "windowWidth",windowWidth,"windowHeight",windowHeight,"grants",grants,"sharedStyle",sharedStyle,
   "extensions",extensions,"hostState",hostState,"capabilities",capabilities,"surfaces",surfaceValues,"surfaceCount",surfaces.size(),"hasRestorationToken",restorationToken!=null);
 }
 private HostSnapshot(Parcel in){this(in.readBundle(HostSnapshot.class.getClassLoader()));}
 @Override public void writeToParcel(Parcel out,int flags){out.writeBundle(toBundle());}
 @Override public int describeContents(){return 0;}
 public static final Creator<HostSnapshot> CREATOR=new Creator<HostSnapshot>(){public HostSnapshot createFromParcel(Parcel in){return new HostSnapshot(in);}public HostSnapshot[] newArray(int size){return new HostSnapshot[size];}};
}
