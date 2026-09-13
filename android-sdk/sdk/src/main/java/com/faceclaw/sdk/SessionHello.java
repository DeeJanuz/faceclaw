package com.faceclaw.sdk;

import android.os.Parcel;
import android.os.Parcelable;

/** Immutable protocol identity exchanged while establishing one Binder session. */
public final class SessionHello implements Parcelable {
 public final int protocolMajor; public final String peerVersion,sessionId;
 public SessionHello(int protocolMajor,String peerVersion,String sessionId){this.protocolMajor=protocolMajor;this.peerVersion=peerVersion==null?"":peerVersion;this.sessionId=sessionId==null?"":sessionId;}
 private SessionHello(Parcel in){protocolMajor=in.readInt();peerVersion=in.readString();sessionId=in.readString();}
 @Override public void writeToParcel(Parcel out,int flags){out.writeInt(protocolMajor);out.writeString(peerVersion);out.writeString(sessionId);}
 @Override public int describeContents(){return 0;}
 public static final Creator<SessionHello> CREATOR=new Creator<SessionHello>(){public SessionHello createFromParcel(Parcel in){return new SessionHello(in);}public SessionHello[] newArray(int size){return new SessionHello[size];}};
}
