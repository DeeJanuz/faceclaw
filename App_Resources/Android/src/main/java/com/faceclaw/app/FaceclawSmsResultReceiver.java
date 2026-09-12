package com.faceclaw.app;
import android.content.*;
/** Non-exported receiver; only system-held, one-shot explicit PendingIntents report outcomes. */
public final class FaceclawSmsResultReceiver extends BroadcastReceiver {
 public void onReceive(Context context,Intent intent) {
  if(intent==null||!(context.getPackageName()+".SMS_SENT").equals(intent.getAction())||intent.getData()==null) return;
  String[] values=intent.getData().getSchemeSpecificPart().split("/");
  if(values.length!=2||!values[0].matches("[A-Za-z0-9-]{1,128}")) return;
  try { FaceclawSms.get(context).received(values[0],Integer.parseInt(values[1]),getResultCode()); } catch(Exception ignored) {}
 }
}
