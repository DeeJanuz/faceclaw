package com.faceclaw.sdk;

import java.util.*;
import java.util.concurrent.*;

/** Application-side retained scene. The complete accepted state survives host reconnection. */
public final class SceneController {
 private static final ScheduledExecutorService TIMEOUTS=Executors.newSingleThreadScheduledExecutor(r->{Thread thread=new Thread(r,"FaceclawSceneTimeout");thread.setDaemon(true);return thread;});
 private static final long RESULT_TIMEOUT_MS=15000;
 private final RenderSurface surface;
 private long acceptedVersion;
 private long pendingVersion;
 private LinkedHashMap<Long,SceneNode> pendingNodes;
 private ArrayList<Long> pendingOrder;
 private LinkedHashMap<Long,SceneNode> nodes=new LinkedHashMap<>();
 private ArrayList<Long> order=new ArrayList<>();
 private ScheduledFuture<?> pendingTimeout;

 SceneController(RenderSurface surface){this.surface=surface;}

 public boolean commit(SceneTransaction transaction){
  if(transaction==null)return false;
  boolean invalid;
  synchronized(this){
   if(transaction.version<=acceptedVersion||pendingVersion!=0)return false;
   LinkedHashMap<Long,SceneNode> next=transaction.clear?new LinkedHashMap<>():new LinkedHashMap<>(nodes);
   ArrayList<Long> nextOrder=transaction.clear?new ArrayList<>():new ArrayList<>(order);
   for(long id:transaction.removes){next.remove(id);nextOrder.remove(id);}
   for(SceneNode node:transaction.upserts){next.put(node.id,node);if(!nextOrder.contains(node.id))nextOrder.add(node.id);}
   if(transaction.order.length>0){
    HashSet<Long> seen=new HashSet<>();ArrayList<Long> explicit=new ArrayList<>();
    for(long id:transaction.order)if(next.containsKey(id)&&seen.add(id))explicit.add(id);
    for(long id:nextOrder)if(next.containsKey(id)&&seen.add(id))explicit.add(id);
    nextOrder=explicit;
   }
   invalid=next.size()>Protocol.MAX_SCENE_NODES||!validGraph(next);
   if(!invalid){pendingNodes=next;pendingOrder=nextOrder;pendingVersion=transaction.version;}
  }
  if(invalid){surface.invalidate(InvalidateReason.STATE);return false;}
  surface.consumeSceneCredit();
  boolean sent=surface.session().commitScene(surface,transaction);long version=transaction.version;
  synchronized(this){if(pendingVersion==version){if(sent)pendingTimeout=TIMEOUTS.schedule(()->timeout(version),RESULT_TIMEOUT_MS,TimeUnit.MILLISECONDS);else clearPending();}}
  if(!sent)surface.invalidate(InvalidateReason.STATE);
  return sent;
 }

 void result(long version,boolean accepted){
  synchronized(this){if(version!=pendingVersion)return;if(accepted){nodes=pendingNodes;order=pendingOrder;acceptedVersion=version;}clearPending();}
  if(!accepted)surface.invalidate(InvalidateReason.STATE);
 }

 public synchronized long acceptedVersion(){return acceptedVersion;}
 public synchronized long pendingVersion(){return pendingVersion;}

 boolean replay(){
  SceneTransaction transaction;
  synchronized(this){clearPending();if(acceptedVersion==0||nodes.isEmpty())return false;SceneTransaction.Builder builder=SceneTransaction.builder(acceptedVersion).clear();for(long id:order){SceneNode node=nodes.get(id);if(node!=null)builder.upsert(node);}builder.order(toArray(order));transaction=builder.build();}
  surface.consumeSceneCredit();boolean sent=surface.session().commitScene(surface,transaction);if(!sent)surface.invalidate(InvalidateReason.RECOVERY);return true;
 }

 private static long[] toArray(List<Long> values){long[] result=new long[values.size()];for(int i=0;i<result.length;i++)result[i]=values.get(i);return result;}
 private static boolean validGraph(Map<Long,SceneNode> nodes){for(SceneNode node:nodes.values()){HashSet<Long> seen=new HashSet<>();long parent=node.parentId;while(parent!=0){if(!seen.add(parent))return false;SceneNode group=nodes.get(parent);if(!(group instanceof SceneNode.Group))return false;parent=group.parentId;}}return true;}
 synchronized void reset(){acceptedVersion=0;clearPending();nodes.clear();order.clear();}
 private void timeout(long version){synchronized(this){if(version!=pendingVersion)return;clearPending();}surface.invalidate(InvalidateReason.RECOVERY);}
 private void clearPending(){if(pendingTimeout!=null)pendingTimeout.cancel(false);pendingTimeout=null;pendingVersion=0;pendingNodes=null;pendingOrder=null;}
}
