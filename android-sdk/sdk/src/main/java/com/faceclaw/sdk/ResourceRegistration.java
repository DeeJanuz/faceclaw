package com.faceclaw.sdk;

import android.os.Parcel;
import android.os.Parcelable;

/** Immutable application-scoped grayscale resource registration. */
public final class ResourceRegistration implements Parcelable {
 public final int id,width,height;public final String type,sha256;public final byte[] pixels;
 public ResourceRegistration(int id,String type,int width,int height,String sha256,byte[] pixels){this.id=id;this.type=type;this.width=width;this.height=height;this.sha256=sha256;this.pixels=pixels==null?null:pixels.clone();}
 private ResourceRegistration(Parcel in){id=in.readInt();type=in.readString();width=in.readInt();height=in.readInt();sha256=in.readString();pixels=in.createByteArray();}
 @Override public void writeToParcel(Parcel out,int flags){out.writeInt(id);out.writeString(type);out.writeInt(width);out.writeInt(height);out.writeString(sha256);out.writeByteArray(pixels);}
 @Override public int describeContents(){return 0;}
 public static final Creator<ResourceRegistration> CREATOR=new Creator<ResourceRegistration>(){public ResourceRegistration createFromParcel(Parcel in){return new ResourceRegistration(in);}public ResourceRegistration[] newArray(int size){return new ResourceRegistration[size];}};
}
