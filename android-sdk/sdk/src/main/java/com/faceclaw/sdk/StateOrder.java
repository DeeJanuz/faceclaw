package com.faceclaw.sdk;

/** Session-local ordering. Once an ordered host is observed, unversioned state is stale. */
final class StateOrder {
 private long revision;
 synchronized boolean accept(long next){
  if(next<0||next==0&&revision>0||next>0&&next<=revision)return false;
  revision=next;return true;
 }
 synchronized void reset(){revision=0;}
}
