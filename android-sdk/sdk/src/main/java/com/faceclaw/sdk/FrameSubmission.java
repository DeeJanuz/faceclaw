package com.faceclaw.sdk;

import android.os.Parcel;
import android.os.Parcelable;

/** Typed metadata for pixels already written into a registered SharedMemory slot. */
public final class FrameSubmission implements Parcelable {
 public final String surfaceId,traceId;public final long generation,sequence,creditId,clientFrameId,contentVersion;public final int slotId;public final boolean requestNextFrame;public final int[] damage;public final byte[] draws;
 public FrameSubmission(String surfaceId,long generation,int slotId,long sequence,long creditId,long clientFrameId,long contentVersion,boolean requestNextFrame,String traceId,int[] damage,byte[] draws){this.surfaceId=surfaceId;this.generation=generation;this.slotId=slotId;this.sequence=sequence;this.creditId=creditId;this.clientFrameId=clientFrameId;this.contentVersion=contentVersion;this.requestNextFrame=requestNextFrame;this.traceId=traceId==null?"":traceId;this.damage=damage==null?null:damage.clone();this.draws=draws==null?null:draws.clone();}
 private FrameSubmission(Parcel in){surfaceId=in.readString();generation=in.readLong();slotId=in.readInt();sequence=in.readLong();creditId=in.readLong();clientFrameId=in.readLong();contentVersion=in.readLong();requestNextFrame=in.readInt()!=0;traceId=in.readString();damage=in.createIntArray();draws=in.createByteArray();}
 @Override public void writeToParcel(Parcel out,int flags){out.writeString(surfaceId);out.writeLong(generation);out.writeInt(slotId);out.writeLong(sequence);out.writeLong(creditId);out.writeLong(clientFrameId);out.writeLong(contentVersion);out.writeInt(requestNextFrame?1:0);out.writeString(traceId);out.writeIntArray(damage);out.writeByteArray(draws);}
 @Override public int describeContents(){return 0;}
 public static final Creator<FrameSubmission> CREATOR=new Creator<FrameSubmission>(){public FrameSubmission createFromParcel(Parcel in){return new FrameSubmission(in);}public FrameSubmission[] newArray(int size){return new FrameSubmission[size];}};
}
