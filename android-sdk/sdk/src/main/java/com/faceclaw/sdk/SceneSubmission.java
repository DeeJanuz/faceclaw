package com.faceclaw.sdk;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Typed atomic scene envelope. Node bundles are a bounded tagged union. */
public final class SceneSubmission implements Parcelable {
 public final String surfaceId;public final long generation,sceneVersion;public final boolean clear;public final List<Bundle> upserts;public final long[] removes,order;
 public SceneSubmission(String surfaceId,long generation,long sceneVersion,boolean clear,List<Bundle> upserts,long[] removes,long[] order){this.surfaceId=surfaceId;this.generation=generation;this.sceneVersion=sceneVersion;this.clear=clear;ArrayList<Bundle> nodes=new ArrayList<>();for(Bundle node:upserts)nodes.add(new Bundle(node));this.upserts=Collections.unmodifiableList(nodes);this.removes=removes.clone();this.order=order.clone();}
 private SceneSubmission(Parcel in){surfaceId=in.readString();generation=in.readLong();sceneVersion=in.readLong();clear=in.readInt()!=0;ArrayList<Bundle> nodes=in.createTypedArrayList(Bundle.CREATOR);upserts=Collections.unmodifiableList(nodes==null?new ArrayList<>():nodes);removes=in.createLongArray();order=in.createLongArray();}
 @Override public void writeToParcel(Parcel out,int flags){out.writeString(surfaceId);out.writeLong(generation);out.writeLong(sceneVersion);out.writeInt(clear?1:0);out.writeTypedList(upserts);out.writeLongArray(removes);out.writeLongArray(order);}
 @Override public int describeContents(){return 0;}
 public static final Creator<SceneSubmission> CREATOR=new Creator<SceneSubmission>(){public SceneSubmission createFromParcel(Parcel in){return new SceneSubmission(in);}public SceneSubmission[] newArray(int size){return new SceneSubmission[size];}};
}
