package com.faceclaw.sdk;

import java.util.*;

/** Application-side retained scene. The complete accepted state survives host reconnection. */
public final class SceneController {
 private final RenderSurface surface;
 private long acceptedVersion;
 private long pendingVersion;
 private LinkedHashMap<Long,SceneNode> pendingNodes;
 private ArrayList<Long> pendingOrder;
 private LinkedHashMap<Long,SceneNode> nodes=new LinkedHashMap<>();
 private ArrayList<Long> order=new ArrayList<>();

 SceneController(RenderSurface surface){this.surface=surface;}

 public synchronized boolean commit(SceneTransaction transaction){
  if(transaction==null||transaction.version<=acceptedVersion||pendingVersion!=0)return false;
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
  if(next.size()>Protocol.MAX_SCENE_NODES||!validGraph(next)){surface.invalidate(InvalidateReason.STATE);return false;}
  if(!surface.session().commitScene(surface,transaction)){surface.invalidate(InvalidateReason.STATE);return false;}
  pendingNodes=next;pendingOrder=nextOrder;pendingVersion=transaction.version;surface.consumeSceneCredit();return true;
 }

 synchronized void result(long version,boolean accepted){
  if(version!=pendingVersion)return;
  if(accepted){nodes=pendingNodes;order=pendingOrder;acceptedVersion=version;}
  pendingVersion=0;pendingNodes=null;pendingOrder=null;
  if(!accepted)surface.invalidate(InvalidateReason.STATE);
 }

 public synchronized long acceptedVersion(){return acceptedVersion;}

 synchronized void replay(){
  pendingVersion=0;pendingNodes=null;pendingOrder=null;
  if(acceptedVersion==0||nodes.isEmpty())return;
  SceneTransaction.Builder builder=SceneTransaction.builder(acceptedVersion).clear();
  for(long id:order){SceneNode node=nodes.get(id);if(node!=null)builder.upsert(node);}
  builder.order(toArray(order));surface.session().commitScene(surface,builder.build());
 }

 private static long[] toArray(List<Long> values){long[] result=new long[values.size()];for(int i=0;i<result.length;i++)result[i]=values.get(i);return result;}
 private static boolean validGraph(Map<Long,SceneNode> nodes){for(SceneNode node:nodes.values()){HashSet<Long> seen=new HashSet<>();long parent=node.parentId;while(parent!=0){if(!seen.add(parent))return false;SceneNode group=nodes.get(parent);if(!(group instanceof SceneNode.Group))return false;parent=group.parentId;}}return true;}
 synchronized void reset(){acceptedVersion=0;pendingVersion=0;pendingNodes=null;pendingOrder=null;nodes.clear();order.clear();}
}
