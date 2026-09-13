package com.faceclaw.sdk;

import android.os.Parcel;
import android.os.Parcelable;

/** Classified session loss. Details are bounded and contain no application content. */
public final class DisconnectInfo implements Parcelable {
 public enum Reason { BINDER_DIED, HOST_STOPPED, IDENTITY_CHANGED, REVOKED, UPDATE_REQUIRED, PROTOCOL_ABUSE, TRANSPORT_ERROR, UNKNOWN }
 public final Reason reason; public final boolean recoverable; public final String detail;
 public DisconnectInfo(Reason reason,boolean recoverable,String detail) {
  this.reason=reason==null?Reason.UNKNOWN:reason;this.recoverable=recoverable;this.detail=detail==null?"":detail.substring(0,Math.min(256,detail.length()));
 }
 private DisconnectInfo(Parcel in){this(safeReason(in.readString()),in.readInt()!=0,in.readString());}
 private static Reason safeReason(String value){try{return Reason.valueOf(value);}catch(Exception ignored){return Reason.UNKNOWN;}}
 @Override public void writeToParcel(Parcel out,int flags){out.writeString(reason.name());out.writeInt(recoverable?1:0);out.writeString(detail);}
 @Override public int describeContents(){return 0;}
 public static final Creator<DisconnectInfo> CREATOR=new Creator<DisconnectInfo>(){public DisconnectInfo createFromParcel(Parcel in){return new DisconnectInfo(in);}public DisconnectInfo[] newArray(int size){return new DisconnectInfo[size];}};
}
