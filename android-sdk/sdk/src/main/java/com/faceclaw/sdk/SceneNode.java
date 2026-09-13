package com.faceclaw.sdk;

import android.os.Bundle;
import java.nio.ByteBuffer;

/** Integer-coordinate retained scene primitives. */
public abstract class SceneNode {
 public final long id,parentId; public final int x,y,z,brightness,opacity;
 SceneNode(long id,long parentId,int x,int y,int z,int brightness,int opacity){if(id==0||id==parentId||brightness<0||brightness>255||opacity<0||opacity>255)throw new IllegalArgumentException("Invalid scene node");this.id=id;this.parentId=parentId;this.x=x;this.y=y;this.z=z;this.brightness=brightness;this.opacity=opacity;}
 abstract Bundle wire();
 Bundle base(String kind){Bundle b=new Bundle();b.putString("kind",kind);b.putLong("id",id);b.putLong("parentId",parentId);b.putInt("x",x);b.putInt("y",y);b.putInt("z",z);b.putInt("brightness",brightness);b.putInt("opacity",opacity);return b;}
 public static final class Rect extends SceneNode { public final int width,height,radius;
  public Rect(long id,int x,int y,int width,int height,int radius,int z,int brightness,int opacity){this(id,0,x,y,width,height,radius,z,brightness,opacity);}
  public Rect(long id,long parentId,int x,int y,int width,int height,int radius,int z,int brightness,int opacity){super(id,parentId,x,y,z,brightness,opacity);if(width<=0||height<=0||radius<0)throw new IllegalArgumentException("Invalid rect");this.width=width;this.height=height;this.radius=radius;}
  Bundle wire(){Bundle b=base(radius==0?"rect":"rounded-rect");b.putInt("width",width);b.putInt("height",height);b.putInt("radius",radius);return b;}}
 public static final class Line extends SceneNode { public final int x2,y2,width;
  public Line(long id,int x1,int y1,int x2,int y2,int width,int z,int brightness,int opacity){this(id,0,x1,y1,x2,y2,width,z,brightness,opacity);}
  public Line(long id,long parentId,int x1,int y1,int x2,int y2,int width,int z,int brightness,int opacity){super(id,parentId,x1,y1,z,brightness,opacity);if(width<=0)throw new IllegalArgumentException("Invalid line");this.x2=x2;this.y2=y2;this.width=width;}
  Bundle wire(){Bundle b=base("line");b.putInt("x2",x2);b.putInt("y2",y2);b.putInt("width",width);return b;}}
 public static final class Resource extends SceneNode { public final ResourceHandle resource;
  public Resource(long id,ResourceHandle resource,int x,int y,int z,int brightness,int opacity){this(id,0,resource,x,y,z,brightness,opacity);}
  public Resource(long id,long parentId,ResourceHandle resource,int x,int y,int z,int brightness,int opacity){super(id,parentId,x,y,z,brightness,opacity);this.resource=java.util.Objects.requireNonNull(resource);}
  Bundle wire(){Bundle b=base(resource.type==ResourceHandle.Type.GLYPH?"glyph":"image");b.putInt("resourceId",resource.id);b.putInt("width",resource.width);b.putInt("height",resource.height);return b;}}
 public static final class RasterPatch extends SceneNode { public final ResourceHandle resource;public final int width,height;public final byte[] pixels;
  public RasterPatch(long id,ResourceHandle resource,int x,int y,int z,int brightness,int opacity){this(id,0,resource,x,y,z,brightness,opacity);}
  public RasterPatch(long id,long parentId,ResourceHandle resource,int x,int y,int z,int brightness,int opacity){super(id,parentId,x,y,z,brightness,opacity);this.resource=java.util.Objects.requireNonNull(resource);this.width=resource.width;this.height=resource.height;this.pixels=null;}
  /** Inline mutable-content patch. Its bytes are snapshotted into the retained transaction and replayed after recovery. */
  public RasterPatch(long id,int width,int height,ByteBuffer gray8,int x,int y,int z,int brightness,int opacity){this(id,0,width,height,gray8,x,y,z,brightness,opacity);}
  public RasterPatch(long id,long parentId,int width,int height,ByteBuffer gray8,int x,int y,int z,int brightness,int opacity){super(id,parentId,x,y,z,brightness,opacity);int size=Protocol.frameSize(width,height);if(gray8==null||gray8.remaining()!=size)throw new IllegalArgumentException("Invalid raster patch");this.resource=null;this.width=width;this.height=height;this.pixels=new byte[size];gray8.duplicate().get(this.pixels);}
  Bundle wire(){Bundle b=base("raster-patch");if(resource!=null)b.putInt("resourceId",resource.id);else b.putByteArray("pixels",pixels.clone());b.putInt("width",width);b.putInt("height",height);return b;}}
 public static final class Group extends SceneNode { public final int clipX,clipY,clipWidth,clipHeight;
  public Group(long id,int x,int y,int z,int opacity,int clipX,int clipY,int clipWidth,int clipHeight){this(id,0,x,y,z,opacity,clipX,clipY,clipWidth,clipHeight);}
  public Group(long id,long parentId,int x,int y,int z,int opacity,int clipX,int clipY,int clipWidth,int clipHeight){super(id,parentId,x,y,z,255,opacity);if(clipWidth<0||clipHeight<0)throw new IllegalArgumentException("Invalid group clip");this.clipX=clipX;this.clipY=clipY;this.clipWidth=clipWidth;this.clipHeight=clipHeight;}
  public Group(long id,int x,int y,int z,int brightness,int opacity,int clipX,int clipY,int clipWidth,int clipHeight){this(id,0,x,y,z,brightness,opacity,clipX,clipY,clipWidth,clipHeight);}
  public Group(long id,long parentId,int x,int y,int z,int brightness,int opacity,int clipX,int clipY,int clipWidth,int clipHeight){super(id,parentId,x,y,z,brightness,opacity);if(clipWidth<0||clipHeight<0)throw new IllegalArgumentException("Invalid group clip");this.clipX=clipX;this.clipY=clipY;this.clipWidth=clipWidth;this.clipHeight=clipHeight;}
  Bundle wire(){Bundle b=base("group");b.putInt("clipX",clipX);b.putInt("clipY",clipY);b.putInt("clipWidth",clipWidth);b.putInt("clipHeight",clipHeight);return b;}}
}
