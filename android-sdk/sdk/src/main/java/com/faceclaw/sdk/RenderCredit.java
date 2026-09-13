package com.faceclaw.sdk;

import android.os.Parcel;
import android.os.Parcelable;

public final class RenderCredit implements Parcelable {
 public final String surfaceId,traceId; public final long generation,creditId,targetPresentationTimeNanos; public final int maxDamageRects;
 public RenderCredit(String surfaceId,long generation,long creditId,long targetPresentationTimeNanos,int maxDamageRects,String traceId) {
  this.surfaceId=surfaceId;this.generation=generation;this.creditId=creditId;this.targetPresentationTimeNanos=targetPresentationTimeNanos;
  this.maxDamageRects=maxDamageRects;this.traceId=traceId==null?"":traceId;
 }
 private RenderCredit(Parcel in){this(in.readString(),in.readLong(),in.readLong(),in.readLong(),in.readInt(),in.readString());}
 @Override public void writeToParcel(Parcel out,int flags){out.writeString(surfaceId);out.writeLong(generation);out.writeLong(creditId);out.writeLong(targetPresentationTimeNanos);out.writeInt(maxDamageRects);out.writeString(traceId);}
 @Override public int describeContents(){return 0;}
 public static final Creator<RenderCredit> CREATOR=new Creator<RenderCredit>(){public RenderCredit createFromParcel(Parcel in){return new RenderCredit(in);}public RenderCredit[] newArray(int size){return new RenderCredit[size];}};
}
