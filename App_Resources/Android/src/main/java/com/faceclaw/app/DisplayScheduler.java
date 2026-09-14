package com.faceclaw.app;

import android.os.Handler;
import android.os.SystemClock;
import java.util.*;

/** Global latest-wins render-credit scheduler shared by every external surface. */
final class DisplayScheduler {
 enum Priority { SYSTEM_SHELL, DIRECT_INPUT, FOCUSED_ANIMATION, VISIBLE_EXTENSION, BACKGROUND }
 private static final long MIN_GRANT_INTERVAL_MS=16;
 private static final long VISIBLE_EXTENSION_MAX_WAIT_MS=250;
 private final Handler handler;private final Map<String,Pending> pending=new HashMap<>();private long lastGrantAt,nextOrder;
 private boolean scheduled;
 private static final class Pending {final String key;final Priority priority;final long readyAt,enqueuedAt,order;final Runnable grant;Pending(String key,Priority priority,long readyAt,long enqueuedAt,long order,Runnable grant){this.key=key;this.priority=priority;this.readyAt=readyAt;this.enqueuedAt=enqueuedAt;this.order=order;this.grant=grant;}}
 private final Runnable flush=()->{Pending next; synchronized(this){scheduled=false;long now=SystemClock.elapsedRealtime();next=pending.values().stream().filter(value->value.readyAt<=now).min(Comparator.comparingInt((Pending value)->effectivePriority(value,now)).thenComparingLong(value->value.enqueuedAt).thenComparingLong(value->value.order)).orElse(null);if(next==null){scheduleLocked();return;}pending.remove(next.key);lastGrantAt=now;}next.grant.run();synchronized(this){scheduleLocked();}};
 DisplayScheduler(Handler handler){this.handler=handler;}
 synchronized void offer(String key,Priority priority,Runnable grant){offer(key,priority,0,grant);}
 synchronized void offer(String key,Priority priority,long delayMs,Runnable grant){long now=SystemClock.elapsedRealtime();Pending existing=pending.get(key);Priority selected=existing==null||priority.ordinal()<existing.priority.ordinal()?priority:existing.priority;long readyAt=now+Math.max(0,delayMs);if(existing!=null)readyAt=Math.min(existing.readyAt,readyAt);pending.put(key,new Pending(key,selected,readyAt,existing==null?now:existing.enqueuedAt,existing==null?nextOrder++:existing.order,grant));if(scheduled){handler.removeCallbacks(flush);scheduled=false;}scheduleLocked();}
 synchronized void cancel(String key){pending.remove(key);}
 private static int effectivePriority(Pending value,long now){return value.priority==Priority.VISIBLE_EXTENSION&&now-value.enqueuedAt>=VISIBLE_EXTENSION_MAX_WAIT_MS?Priority.FOCUSED_ANIMATION.ordinal():value.priority.ordinal();}
 private void scheduleLocked(){if(scheduled||pending.isEmpty())return;scheduled=true;long now=SystemClock.elapsedRealtime(),readyAt=pending.values().stream().mapToLong(value->value.readyAt).min().orElse(now);long delay=Math.max(Math.max(0,MIN_GRANT_INTERVAL_MS-(now-lastGrantAt)),Math.max(0,readyAt-now));handler.postDelayed(flush,delay);}
}
