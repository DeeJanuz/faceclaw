package com.faceclaw.sdk;

import android.os.Parcel;
import android.os.Parcelable;
import org.json.JSONObject;

/** One terminal result for an accepted client frame. */
public final class FrameOutcome implements Parcelable {
 public enum Status { DISPLAY_ACKED, PREVIEW_COMMITTED, DEDUPLICATED, SUPERSEDED_BEFORE_COMPOSE,
  SUPERSEDED_BEFORE_SEND, THROTTLED, HIDDEN, STALE_GENERATION, TORN_WRITE,
  BLE_TIMEOUT, SESSION_LOST, CANCELLED }
 public final String surfaceId,traceId,diagnostic;
 public final long clientFrameId,contentVersion;
 public final Status status;
 public final boolean metadataDropped;
 public FrameOutcome(String surfaceId,long clientFrameId,long contentVersion,Status status,String traceId,String diagnostic,boolean metadataDropped) {
  this.surfaceId=surfaceId;this.clientFrameId=clientFrameId;this.contentVersion=contentVersion;this.status=status;
  this.traceId=traceId==null?"":traceId;this.diagnostic=diagnostic==null?"":diagnostic;this.metadataDropped=metadataDropped;
 }
 public JSONObject toJson() { return Protocol.object("surfaceId",surfaceId,"clientFrameId",clientFrameId,
  "contentVersion",contentVersion,"status",status.name(),"traceId",traceId,"diagnostic",diagnostic,
  "metadataDropped",metadataDropped); }
 private FrameOutcome(Parcel in){this(in.readString(),in.readLong(),in.readLong(),Status.valueOf(in.readString()),in.readString(),in.readString(),in.readInt()!=0);}
 @Override public void writeToParcel(Parcel out,int flags){out.writeString(surfaceId);out.writeLong(clientFrameId);out.writeLong(contentVersion);out.writeString(status.name());out.writeString(traceId);out.writeString(diagnostic);out.writeInt(metadataDropped?1:0);}
 @Override public int describeContents(){return 0;}
 public static final Creator<FrameOutcome> CREATOR=new Creator<FrameOutcome>(){public FrameOutcome createFromParcel(Parcel in){return new FrameOutcome(in);}public FrameOutcome[] newArray(int size){return new FrameOutcome[size];}};
}
