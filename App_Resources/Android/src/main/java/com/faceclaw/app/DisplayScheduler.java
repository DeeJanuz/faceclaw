package com.faceclaw.app;

import android.os.Handler;
import android.os.SystemClock;
import java.util.*;

/** Global latest-wins render-credit scheduler shared by every external surface. */
final class DisplayScheduler {
 enum Priority { SYSTEM_SHELL, DIRECT_INPUT, FOCUSED_ANIMATION, VISIBLE_EXTENSION, BACKGROUND }
 private static final long MIN_GRANT_INTERVAL_MS=16;
 private final Handler handler;private final Map<String,Pending> pending=new HashMap<>();private long lastGrantAt;private boolean scheduled;
 private static final class Pending {final String key;final Priority priority;final long readyAt;final Runnable grant;Pending(String key,Priority priority,long readyAt,Runnable grant){this.key=key;this.priority=priority;this.readyAt=readyAt;this.grant=grant;}}
 private final Runnable flush=()->{Pending next; synchronized(this){scheduled=false;long now=SystemClock.elapsedRealtime();next=pending.values().stream().filter(value->value.readyAt<=now).min(Comparator.comparingInt(value->value.priority.ordinal())).orElse(null);if(next==null){scheduleLocked();return;}pending.remove(next.key);lastGrantAt=now;}next.grant.run();synchronized(this){scheduleLocked();}};
 DisplayScheduler(Handler handler){this.handler=handler;}
 synchronized void offer(String key,Priority priority,Runnable grant){offer(key,priority,0,grant);}
 synchronized void offer(String key,Priority priority,long delayMs,Runnable grant){Pending existing=pending.get(key);Priority selected=existing==null||priority.ordinal()<existing.priority.ordinal()?priority:existing.priority;pending.put(key,new Pending(key,selected,SystemClock.elapsedRealtime()+Math.max(0,delayMs),grant));if(scheduled){handler.removeCallbacks(flush);scheduled=false;}scheduleLocked();}
 synchronized void cancel(String key){pending.remove(key);}
 private void scheduleLocked(){if(scheduled||pending.isEmpty())return;scheduled=true;long now=SystemClock.elapsedRealtime(),readyAt=pending.values().stream().mapToLong(value->value.readyAt).min().orElse(now);long delay=Math.max(Math.max(0,MIN_GRANT_INTERVAL_MS-(now-lastGrantAt)),Math.max(0,readyAt-now));handler.postDelayed(flush,delay);}
}
