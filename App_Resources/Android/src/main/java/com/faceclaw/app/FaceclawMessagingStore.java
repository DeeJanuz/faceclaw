package com.faceclaw.app;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Private, non-backup storage. Missing keys or damaged ciphertext fail closed. */
public final class FaceclawMessagingStore {
 public static String fingerprint(String value) {
  try {
   byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
   StringBuilder result=new StringBuilder();for(byte b:digest)result.append(String.format("%02x",b));return result.toString();
  } catch(Exception error) { throw new IllegalStateException("Messaging identity unavailable"); }
 }
 private final AtomicFile file;
 private final String alias;
 public FaceclawMessagingStore(Context context,String namespace) {
  if(!namespace.matches("[a-z-]{1,40}")) throw new IllegalArgumentException("Invalid namespace");
  file=new AtomicFile(new File(context.getNoBackupFilesDir(),"messaging-"+namespace+".bin"));
  alias="faceclaw.messaging."+namespace;
 }
 private SecretKey key(boolean create) throws Exception {
  KeyStore store=KeyStore.getInstance("AndroidKeyStore"); store.load(null);
  if(store.containsAlias(alias)) return (SecretKey)store.getKey(alias,null);
  if(!create) throw new IOException("Messaging key unavailable");
  KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
  generator.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
  return generator.generateKey();
 }
 public synchronized String read() {
  if(!file.getBaseFile().exists()&&!new File(file.getBaseFile()+".bak").exists()) return "[]";
  try(FileInputStream in=file.openRead()) {
   if(in.getChannel().size()>2_000_000) throw new IOException("Messaging store exceeds limit");
   ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buffer=new byte[4096]; int n;
   while((n=in.read(buffer))!=-1) { out.write(buffer,0,n); if(out.size()>2_000_000) throw new IOException("Messaging store exceeds limit"); }
   byte[] bytes=out.toByteArray(); if(bytes.length<29||bytes[0]!=1) throw new IOException("Invalid messaging store");
   Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE,key(false),new GCMParameterSpec(128,bytes,1,12));
   cipher.updateAAD(alias.getBytes(StandardCharsets.UTF_8));
   return new String(cipher.doFinal(bytes,13,bytes.length-13),StandardCharsets.UTF_8);
  } catch(Exception error) { throw new IllegalStateException("Messaging storage unavailable"); }
 }
 public synchronized void write(String json) {
  FileOutputStream out=null;
  try {
   byte[] plain=json.getBytes(StandardCharsets.UTF_8); if(plain.length>1_900_000) throw new IOException("Messaging store full");
   boolean exists=file.getBaseFile().exists()||new File(file.getBaseFile()+".bak").exists();
   Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key(!exists)); cipher.updateAAD(alias.getBytes(StandardCharsets.UTF_8));
   byte[] encrypted=cipher.doFinal(plain); out=file.startWrite(); out.write(1); out.write(cipher.getIV()); out.write(encrypted); file.finishWrite(out);
  } catch(Exception error) { if(out!=null) file.failWrite(out); throw new IllegalStateException("Messaging storage unavailable"); }
 }
}
