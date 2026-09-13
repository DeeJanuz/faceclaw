package com.faceclaw.sdk;

import java.util.*;

public final class FrameMetadata {
 public final long clientFrameId,contentVersion; public final boolean requestNextFrame; public final String traceId; public final List<DamageRect> damage; public final DrawBatch draws;
 private FrameMetadata(Builder b) { clientFrameId=b.clientFrameId;contentVersion=b.contentVersion;requestNextFrame=b.requestNextFrame;traceId=b.traceId;draws=b.draws;damage=Collections.unmodifiableList(new ArrayList<>(b.damage)); }
 public static Builder builder(long clientFrameId,long contentVersion) { return new Builder(clientFrameId,contentVersion); }
 public static final class Builder {
  final long clientFrameId,contentVersion; boolean requestNextFrame; String traceId=""; DrawBatch draws; final List<DamageRect> damage=new ArrayList<>();
  Builder(long frame,long version) { if(frame<=0||version<0)throw new IllegalArgumentException("Invalid frame identity");clientFrameId=frame;contentVersion=version; }
  public Builder damage(DamageRect rect) { if(damage.size()>=Protocol.MAX_DAMAGE_RECTS)throw new IllegalStateException("Too many damage rectangles");damage.add(Objects.requireNonNull(rect));return this; }
  public Builder fullDamage(int width,int height) { damage.clear();return damage(new DamageRect(0,0,width,height)); }
  public Builder requestNextFrame(boolean value) { requestNextFrame=value;return this; }
  public Builder traceId(String value) { traceId=value==null?"":value.substring(0,Math.min(128,value.length()));return this; }
  public Builder draws(DrawBatch value) { draws=value;return this; }
  public FrameMetadata build() { return new FrameMetadata(this); }
 }
}
