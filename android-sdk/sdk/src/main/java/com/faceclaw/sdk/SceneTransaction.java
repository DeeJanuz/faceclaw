package com.faceclaw.sdk;

import android.os.Bundle;
import java.util.*;

/** Atomic retained-scene mutation. */
public final class SceneTransaction {
 final long version;final boolean clear;final List<SceneNode> upserts;final long[] removes,order;
 private SceneTransaction(Builder b){version=b.version;clear=b.clear;upserts=Collections.unmodifiableList(new ArrayList<>(b.upserts));removes=toArray(b.removes);order=toArray(b.order);}
 public static Builder builder(long sceneVersion){return new Builder(sceneVersion);}
 SceneSubmission wire(String surfaceId,long generation){ArrayList<Bundle> nodes=new ArrayList<>();for(SceneNode n:upserts)nodes.add(n.wire());return new SceneSubmission(surfaceId,generation,version,clear,nodes,removes,order);}
 private static long[] toArray(List<Long> values){long[] out=new long[values.size()];for(int i=0;i<out.length;i++)out[i]=values.get(i);return out;}
 public static final class Builder {final long version;boolean clear;final List<SceneNode> upserts=new ArrayList<>();final List<Long> removes=new ArrayList<>(),order=new ArrayList<>();Builder(long v){if(v<=0)throw new IllegalArgumentException("Invalid scene version");version=v;}public Builder clear(){clear=true;return this;}public Builder upsert(SceneNode node){if(upserts.size()>=Protocol.MAX_SCENE_NODES)throw new IllegalStateException("Too many scene nodes");upserts.add(Objects.requireNonNull(node));return this;}public Builder remove(long id){if(id==0)throw new IllegalArgumentException("Invalid node id");removes.add(id);return this;}public Builder order(long... ids){order.clear();for(long id:ids){if(id==0)throw new IllegalArgumentException("Invalid node id");order.add(id);}return this;}public SceneTransaction build(){long estimate=128L+upserts.size()*128L+(removes.size()+order.size())*8L;for(SceneNode node:upserts)if(node instanceof SceneNode.RasterPatch){byte[] pixels=((SceneNode.RasterPatch)node).pixels;if(pixels!=null)estimate+=pixels.length;}if(estimate>Protocol.MAX_COMMAND_BYTES)throw new IllegalStateException("Scene command batch too large");return new SceneTransaction(this);}}
}
