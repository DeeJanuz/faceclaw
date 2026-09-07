package com.faceclaw.sdk.fixture;
import com.faceclaw.sdk.*;
import android.graphics.*;
import android.os.SystemClock;
import org.json.JSONObject;
/** Test APK only: pins its synthetic host without a user setup prompt. Never ship this subclass. */
public class CanvasService extends FaceclawAppService {
 int width=32,height=16;
 @Override protected void onHostEvent(String type,JSONObject data) {
  if(type.equals("test-search-request")) requestSearchDictation("synthetic-search", "synthetic-directory", "Search by name");
  if(type.equals("test-publish-reply")) postNotification("fixture-message","fixture-target","Synthetic sender","Synthetic body",System.currentTimeMillis()+60000,data.optString("token","fixture-token"));
  if(type.equals("notification-reply")) reportNotificationReplyResult(data.optString("id"),data.optString("replyToken"),"draft-saved");
  if(type.equals("open")||type.equals("resize")) { width=data.optInt("width"); height=data.optInt("height"); }
  if(type.equals("visibility")||type.equals("render")||type.equals("burst")) {
   Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888); Canvas canvas=new Canvas(bitmap); canvas.drawColor(Color.BLACK); Ui.card(canvas,0,0,width,height,0,Color.WHITE);
   int count=type.equals("burst")?data.optInt("count",1):1;
   for(int frame=0;frame<count;frame++) {
    long timestamp=SystemClock.elapsedRealtimeNanos();
    for(int i=0;i<8;i++) { int digit=(int)(timestamp%255)+1; timestamp/=255; bitmap.setPixel(i,0,Color.rgb(digit,digit,digit)); }
    submitBitmap(bitmap);
   }
   bitmap.recycle();
  }
 }
}
