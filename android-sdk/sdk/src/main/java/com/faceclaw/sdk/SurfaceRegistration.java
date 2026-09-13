package com.faceclaw.sdk;

import android.os.Parcel;
import android.os.Parcelable;
import android.os.SharedMemory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One generation's persistent three-slot Gray8 pool registration. */
public final class SurfaceRegistration implements Parcelable {
 public final String surfaceId;public final long generation;public final int width,height;public final List<SharedMemory> buffers;
 public SurfaceRegistration(String surfaceId,long generation,int width,int height,List<SharedMemory> buffers){this.surfaceId=surfaceId;this.generation=generation;this.width=width;this.height=height;this.buffers=Collections.unmodifiableList(new ArrayList<>(buffers));}
 private SurfaceRegistration(Parcel in){surfaceId=in.readString();generation=in.readLong();width=in.readInt();height=in.readInt();ArrayList<SharedMemory> values=in.createTypedArrayList(SharedMemory.CREATOR);buffers=Collections.unmodifiableList(values==null?new ArrayList<>():values);}
 @Override public void writeToParcel(Parcel out,int flags){out.writeString(surfaceId);out.writeLong(generation);out.writeInt(width);out.writeInt(height);out.writeTypedList(buffers);}
 @Override public int describeContents(){return Parcelable.CONTENTS_FILE_DESCRIPTOR;}
 public static final Creator<SurfaceRegistration> CREATOR=new Creator<SurfaceRegistration>(){public SurfaceRegistration createFromParcel(Parcel in){return new SurfaceRegistration(in);}public SurfaceRegistration[] newArray(int size){return new SurfaceRegistration[size];}};
}
