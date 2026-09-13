package com.faceclaw.sdk;

import android.app.PendingIntent;
import android.os.Parcel;
import android.os.Parcelable;

/** Explicit host-selection UI request returned during authentication. */
public final class ConsentRequest implements Parcelable {
 public final PendingIntent consent; public final String sessionId;
 public ConsentRequest(PendingIntent consent,String sessionId){this.consent=consent;this.sessionId=sessionId==null?"":sessionId;}
 private ConsentRequest(Parcel in){consent=in.readParcelable(PendingIntent.class.getClassLoader());sessionId=in.readString();}
 @Override public void writeToParcel(Parcel out,int flags){out.writeParcelable(consent,flags);out.writeString(sessionId);}
 @Override public int describeContents(){return 0;}
 public static final Creator<ConsentRequest> CREATOR=new Creator<ConsentRequest>(){public ConsentRequest createFromParcel(Parcel in){return new ConsentRequest(in);}public ConsentRequest[] newArray(int size){return new ConsentRequest[size];}};
}
