package com.faceclaw.sdk.fixture;
import android.app.Service;
import android.content.Intent;
import android.os.*;
import com.faceclaw.sdk.*;
import org.json.JSONObject;
/** Deliberately bypasses the SDK to exercise the host's trust boundary with malformed frames. */
public class AdversarialService extends Service {
 Messenger host; String session; long generation,sequence;
 final Messenger incoming=new Messenger(new Handler(Looper.getMainLooper(),m->{
  try {
   Bundle b=m.getData();
   if(m.what==Protocol.HELLO) { host=m.replyTo; session=b.getString("session"); host.send(Protocol.message(Protocol.READY,session,"ready",Protocol.object("version",1))); }
   if(m.what==Protocol.EVENT) {
    String type=b.getString("type"); JSONObject data=Protocol.json(b);
    if(type.equals("open")||type.equals("resize")) generation=data.getLong("generation");
    if(type.equals("fixture")) {
     String attack=data.getString("attack");
     if(attack.equals("notification")) { host.send(Protocol.message(Protocol.EVENT,session,"notification",Protocol.object("id","x","target","synthetic","title","Synthetic","text","Synthetic"))); }
     else if(attack.startsWith("system-menu")) { host.send(Protocol.message(Protocol.EVENT,attack.equals("system-menu-stale")?"wrong-session":session,"request-system-menu",Protocol.object("windowId","foreign-window","action","close"))); }
     else if(attack.equals("consent-broadcast")) {
      android.app.PendingIntent pi=android.app.PendingIntent.getBroadcast(this,0,new Intent("com.faceclaw.fixture.NO_ACTION").setPackage(getPackageName()),android.app.PendingIntent.FLAG_IMMUTABLE);
      Message consent=Protocol.message(Protocol.CONSENT,session,"consent",null); consent.getData().putParcelable("consent",pi); host.send(consent);
     }
     else {
      Message frame=Protocol.message(Protocol.FRAME,attack.equals("session")?"wrong-session":session,"frame",null);
      Bundle f=frame.getData(); f.putInt("width",32); f.putInt("height",16); f.putLong("generation",attack.equals("generation")?generation-1:generation); f.putLong("sequence",++sequence);
      f.putByteArray("pixels",new byte[attack.equals("length")?1:512]); host.send(frame);
     }
    }
   }
  } catch(Exception e) { throw new RuntimeException(e); }
  return true;
 }));
 @Override public IBinder onBind(Intent intent) { return incoming.getBinder(); }
}
