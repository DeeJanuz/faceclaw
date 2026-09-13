package com.faceclaw.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.ContactsContract;
import android.provider.Telephony;
import android.telephony.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.Executors;
import com.faceclaw.sdk.Protocol;

/** Host-private SMS provider. Only the host review broker calls send; no exported send service. */
public final class FaceclawSms {
 private static FaceclawSms instance;
 public static synchronized FaceclawSms get(Context c) { if(instance==null) instance=new FaceclawSms(c.getApplicationContext()); return instance; }
 private final Context context; private final FaceclawMessagingStore store;
 private JSONArray operations; private boolean failed;
 private volatile long authorityEpoch;
 public void invalidateReviews() { authorityEpoch++; }
 private final java.util.concurrent.ExecutorService worker=Executors.newSingleThreadExecutor();
 private FaceclawSms(Context c) {
  context=c; store=new FaceclawMessagingStore(c,"sms-operations");
  try { operations=new JSONArray(store.read()); if(operations.length()>2048) throw new IllegalStateException();
   for(int i=0;i<operations.length();i++) { JSONObject op=operations.getJSONObject(i); if(op.optString("status").equals("submitting")) op.put("status","unknown"); }
   store.write(operations.toString());
  } catch(Exception error) { failed=true; operations=new JSONArray(); }
 }
 private boolean has(String permission) { return context.checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED; }
 public void request(String method,String json,FaceclawMessagingCallback callback) {
  final long epoch=authorityEpoch;
  worker.execute(()->{ JSONObject result; try { result=rpc(method,new JSONObject(json),epoch); } catch(Exception error) { result=Protocol.object("error",error instanceof SecurityException?"SMS permission unavailable":"SMS request failed or outcome unknown");
    if(method.equals("send")) {
     synchronized(this) {
      String state="failed";
      try { String requested=new JSONObject(json).optString("operationId"); for(int i=0;i<operations.length();i++) if(operations.getJSONObject(i).optString("id").equals(requested)) state=operations.getJSONObject(i).optString("status","unknown"); }
      catch(Exception malformed) { state="unknown"; }
      result=Protocol.object("status",state);
     }
    } }
   final String encoded=result.toString(); new Handler(Looper.getMainLooper()).post(()->callback.onResult(encoded)); });
 }
 private List<SubscriptionInfo> subscriptions() {
  if(!has(Manifest.permission.READ_PHONE_STATE)) return Collections.emptyList();
  try {
   List<SubscriptionInfo> list=context.getSystemService(SubscriptionManager.class).getActiveSubscriptionInfoList(); return list==null?Collections.emptyList():list;
  } catch(SecurityException revoked) { return Collections.emptyList(); }
 }
 private String account(SubscriptionInfo s) { return "sms:"+s.getSubscriptionId()+":"+s.getSimSlotIndex()+":"+(Build.VERSION.SDK_INT>=29?s.getCarrierId():0); }
 private SubscriptionInfo subscription(String id) { for(SubscriptionInfo s:subscriptions()) if(account(s).equals(id)) return s; throw new IllegalArgumentException("SIM changed"); }
 private String number(String raw) {
  String value=PhoneNumberUtils.normalizeNumber(raw);
  if(!value.startsWith("+")) {
   String country=context.getSystemService(TelephonyManager.class).getNetworkCountryIso();
   value=country==null||country.isEmpty()?null:PhoneNumberUtils.formatNumberToE164(value,country.toUpperCase(Locale.ROOT));
  }
  if(value==null||!value.matches("\\+[1-9][0-9]{6,14}")) throw new IllegalArgumentException("Use a full international phone number"); return value;
 }
 private JSONObject recipient(String number,String title,SubscriptionInfo sim) {
  return Protocol.object("id","sms:"+number,"title",title,"address",number,"accountId",account(sim),"accountLabel",sim.getDisplayName().toString()+" (SIM "+(sim.getSimSlotIndex()+1)+")","channel","sms");
 }
 private synchronized JSONObject rpc(String method,JSONObject p,long epoch) throws Exception {
  if(failed) throw new IllegalStateException("Storage unavailable");
  if(method.equals("status")) {
   JSONArray sims=new JSONArray(); for(SubscriptionInfo sim:subscriptions()) sims.put(Protocol.object("accountId",account(sim),"label",sim.getDisplayName().toString(),"slot",sim.getSimSlotIndex()+1));
   return Protocol.object("available",has(Manifest.permission.SEND_SMS),"history",has(Manifest.permission.READ_SMS),"contacts",has(Manifest.permission.READ_CONTACTS),"accounts",sims,"defaultSubscriptionId",SubscriptionManager.getDefaultSmsSubscriptionId(),"textOnly",true);
  }
  if(method.equals("search")) {
   String query=p.getString("query"); if(query.trim().isEmpty()||query.length()>128) throw new IllegalArgumentException();
   LinkedHashMap<String,String> found=new LinkedHashMap<>();
   if(query.matches("[+0-9 ()-]+")) { String n=number(query); found.put(n,n); }
   else if(has(Manifest.permission.READ_CONTACTS)) {
    Uri uri=Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,Uri.encode(query));
    try(Cursor cursor=context.getContentResolver().query(uri,new String[]{"display_name","data1"},null,null,"display_name ASC")) {
     int scanned=0; while(cursor!=null&&cursor.moveToNext()&&found.size()<10&&scanned++<100) try { String n=number(cursor.getString(1)); String label=cursor.getString(0); found.put(n,label==null||label.isEmpty()?n:label.substring(0,Math.min(160,label.length()))); } catch(IllegalArgumentException ignored) {}
    }
   }
   JSONArray result=new JSONArray(); for(SubscriptionInfo sim:subscriptions()) for(Map.Entry<String,String> row:found.entrySet()) if(result.length()<20) result.put(recipient(row.getKey(),row.getValue(),sim));
   return Protocol.object("recipients",result);
  }
  if(method.equals("operation")) {
   String id=p.getString("operationId"); for(int i=0;i<operations.length();i++) { JSONObject op=operations.getJSONObject(i); if(op.getString("id").equals(id)&&op.getString("accountId").equals(p.getString("accountId"))&&op.getString("recipientId").equals(p.getString("recipientId"))) return Protocol.object("status",op.getString("status")); }
   return Protocol.object("status","unknown");
  }
  SubscriptionInfo sim=subscription(p.getString("accountId")); String id=p.getString("recipientId");
  if(!id.startsWith("sms:")) throw new IllegalArgumentException(); String n=number(id.substring(4)); if(!id.equals("sms:"+n)) throw new IllegalArgumentException();
  if(method.equals("resolve")) {
   String title=n;
   if(has(Manifest.permission.READ_CONTACTS)) try(Cursor cursor=context.getContentResolver().query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI,Uri.encode(n)),new String[]{"display_name"},null,null,null)) {
    if(cursor!=null&&cursor.moveToFirst()&&cursor.getString(0)!=null&&!cursor.getString(0).isEmpty()) { String label=cursor.getString(0); title=label.substring(0,Math.min(160,label.length())); }
   }
   return recipient(n,title,sim);
  }
  if(method.equals("history")) {
   if(!has(Manifest.permission.READ_SMS)) throw new SecurityException();
   JSONArray messages=new JSONArray(); int characters=0;
   // Bound candidate reads by number suffix, then require exact E.164 equality. National formatting never broadens the returned conversation.
   String selection="REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(address, ' ', ''), '-', ''), '(', ''), ')', ''), '.', '') LIKE ?";
   try(Cursor cursor=context.getContentResolver().query(Telephony.Sms.CONTENT_URI.buildUpon().appendQueryParameter("limit","500").build(),new String[]{"address","body","date","type"},selection,new String[]{"%"+n.substring(n.length()-7)},"date DESC")) {
    while(cursor!=null&&cursor.moveToNext()&&messages.length()<32) {
     try { if(!number(cursor.getString(0)).equals(n)) continue; } catch(IllegalArgumentException unmatched) { continue; }
     String body=cursor.getString(1); if(body==null) continue; if(body.length()>8000||characters+body.length()>12000) break;
     messages.put(Protocol.object("text",body,"timestamp",cursor.getLong(2),"outgoing",cursor.getInt(3)==Telephony.Sms.MESSAGE_TYPE_SENT)); characters+=body.length();
    }
   }
   return Protocol.object("messages",messages,"scope","recent SMS only; exact normalized recipient match");
  }
  if(!method.equals("send")) throw new IllegalArgumentException();
  if(!has(Manifest.permission.SEND_SMS)) throw new SecurityException();
  String body=p.getString("text"), operation=p.getString("operationId");
  if(body.trim().isEmpty()||body.length()>8000||!operation.matches("[A-Za-z0-9-]{1,128}")||p.getLong("expiresAt")<=System.currentTimeMillis()||p.getLong("expiresAt")>System.currentTimeMillis()+30000) throw new IllegalArgumentException();
  for(int i=0;i<operations.length();i++) { JSONObject previous=operations.getJSONObject(i); if(previous.getString("id").equals(operation)) throw new IllegalArgumentException("Duplicate operation");
   if(previous.getString("accountId").equals(account(sim))&&previous.getString("recipientId").equals(id)&&Arrays.asList("submitting","unknown","partial").contains(previous.getString("status"))) throw new IllegalStateException("Unresolved send"); }
  if(operations.length()>=2048) throw new IllegalStateException("Operation ledger full");
  SmsManager manager=SmsManager.getSmsManagerForSubscriptionId(sim.getSubscriptionId());
  ArrayList<String> parts=manager.divideMessage(body); if(parts.size()>64||parts.isEmpty()) throw new IllegalArgumentException();
  JSONObject op=Protocol.object("id",operation,"accountId",account(sim),"recipientId",id,"status","submitting","parts",parts.size(),"results",new JSONObject());
  operations.put(op); persist(); // Durable evidence precedes every external dispatch.
  ArrayList<PendingIntent> callbacks=new ArrayList<>();
  for(int i=0;i<parts.size();i++) {
   Intent intent=new Intent(context,FaceclawSmsResultReceiver.class).setAction(context.getPackageName()+".SMS_SENT").setData(Uri.parse("faceclaw-sms:"+operation+"/"+i));
   callbacks.add(PendingIntent.getBroadcast(context,0,intent,PendingIntent.FLAG_ONE_SHOT|PendingIntent.FLAG_IMMUTABLE));
  }
  try {
   if(epoch!=authorityEpoch||p.getLong("expiresAt")<=System.currentTimeMillis()) { op.put("status","failed"); persist(); return Protocol.object("status","failed"); }
   manager.sendMultipartTextMessage(n,null,parts,callbacks,null);
  } catch(Exception error) { op.put("status","unknown"); persist(); }
  return Protocol.object("status",op.getString("status"));
 }
 private void persist() { try { store.write(operations.toString()); } catch(RuntimeException error) { failed=true; throw error; } }
 synchronized void received(String operation,int index,int resultCode) {
  if(failed) return;
  try { for(int i=0;i<operations.length();i++) { JSONObject op=operations.getJSONObject(i); if(!op.getString("id").equals(operation)||index<0||index>=op.getInt("parts")) continue;
   JSONObject results=op.getJSONObject("results"); if(results.has(String.valueOf(index))) return;
   results.put(String.valueOf(index),resultCode==Activity.RESULT_OK); int sent=0; for(Iterator<String> keys=results.keys();keys.hasNext();) if(results.getBoolean(keys.next())) sent++;
   if(results.length()==op.getInt("parts")) op.put("status",sent==op.getInt("parts")?"sent":sent==0?"failed":"partial"); else if(results.length()>sent) op.put("status",sent>0?"partial":"unknown");
   persist(); return;
  } } catch(Exception error) { failed=true; }
 }
 public static void showPermissions(Activity activity) {
  new AlertDialog.Builder(activity).setTitle("SMS assistant setup").setMessage("Allow SMS sending, optional conversation history and contact lookup, and SIM selection. The assistant still needs separate history permission on the glasses and review before every send. Your existing messaging app stays selected.")
   .setNegativeButton("Cancel",null).setPositiveButton("Choose permissions",(d,w)->activity.requestPermissions(new String[]{Manifest.permission.SEND_SMS,Manifest.permission.READ_SMS,Manifest.permission.READ_PHONE_STATE,Manifest.permission.READ_CONTACTS},7405)).show();
 }
}
