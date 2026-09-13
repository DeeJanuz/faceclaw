package com.faceclaw.sdk;

import java.nio.*;

/** Shared-memory frame header shared by the SDK writer and host validator. */
public final class FrameWire {
 public static final int MAGIC=0x46433130;
 private static final int[] CRC32C_TABLE=buildCrc32cTable();
 private static final int MAGIC_AT=0,GENERATION_AT=8,SEQUENCE_AT=16,LENGTH_AT=24,CRC_AT=28,EPOCH_AT=32,CONTENT_AT=40;
 public static ByteBuffer pixels(ByteBuffer mapping,int size){ByteBuffer view=mapping.duplicate();view.position(Protocol.FRAME_HEADER_BYTES);view.limit(Protocol.FRAME_HEADER_BYTES+size);return view.slice();}
 public static void begin(ByteBuffer mapping,long epoch){mapping.order(ByteOrder.LITTLE_ENDIAN).putLong(EPOCH_AT,(epoch|1L));}
 public static void finish(ByteBuffer mapping,long generation,long sequence,long contentVersion,int size,long epoch){
  ByteBuffer header=mapping.order(ByteOrder.LITTLE_ENDIAN);header.putInt(MAGIC_AT,MAGIC);header.putLong(GENERATION_AT,generation);header.putLong(SEQUENCE_AT,sequence);header.putInt(LENGTH_AT,size);header.putInt(CRC_AT,crc32c(pixels(mapping,size)));header.putLong(CONTENT_AT,contentVersion);header.putLong(EPOCH_AT,(epoch+1L)&~1L);
 }
 public static Validation validate(ByteBuffer mapping,long generation,long sequence,int size){
  ByteBuffer in=mapping.duplicate().order(ByteOrder.LITTLE_ENDIAN);long before=in.getLong(EPOCH_AT);if((before&1L)!=0)return Validation.TORN;
  if(in.getInt(MAGIC_AT)!=MAGIC||in.getLong(GENERATION_AT)!=generation||in.getLong(SEQUENCE_AT)!=sequence||in.getInt(LENGTH_AT)!=size)return Validation.INVALID;
  int expected=in.getInt(CRC_AT),actual=crc32c(pixels(mapping,size));long after=in.getLong(EPOCH_AT);if(before!=after||(after&1L)!=0||expected!=actual)return Validation.TORN;return Validation.VALID;
 }
 static int crc32c(ByteBuffer source){int crc=~0;ByteBuffer in=source.duplicate();while(in.hasRemaining())crc=CRC32C_TABLE[(crc^in.get())&0xff]^(crc>>>8);return ~crc;}
 private static int[] buildCrc32cTable(){int[] table=new int[256];for(int i=0;i<table.length;i++){int value=i;for(int bit=0;bit<8;bit++)value=(value>>>1)^((value&1)==0?0:0x82f63b78);table[i]=value;}return table;}
 public enum Validation { VALID, TORN, INVALID }
 private FrameWire(){}
}
