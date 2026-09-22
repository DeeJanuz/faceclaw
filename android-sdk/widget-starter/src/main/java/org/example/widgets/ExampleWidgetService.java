package org.example.widgets;
import com.faceclaw.sdk.*;
import org.json.*;

/** This complete app depends only on the published SDK artifact. */
public final class ExampleWidgetService extends FaceclawAppService {
 @Override protected JSONObject glanceboardWidgets() {
  return Protocol.object("version",1,"widgets",new JSONArray()
   .put(Protocol.object("id","default","label","Example tasks","kind","list","rows",1,"refreshMs",30000))
   .put(Protocol.object("id","status","label","Example status","kind","scene","rows",2,"refreshMs",60000,"uses",new JSONArray().put("battery"))));
 }
 @Override protected void onControlEvent(ControlEvent event) {
  if(!event.type.equals("glanceboard-request"))return;
  String id=event.data.optString("widgetId","default");long expiry=System.currentTimeMillis()+60000;
  if(id.equals("default"))publishGlanceboardWidget(Protocol.object("version",2,"widgetId",id,"title","Example tasks","emptyText","No tasks waiting","expiresAt",expiry,"entries",new JSONArray().put(Protocol.object("id","first","title","My first SDK widget","detail","No host source changes"))));
  else if(id.equals("status"))publishGlanceboardWidget(new GlanceCanvas(id,expiry).text(16,16,"Portable widget",256,235).rect(16,52,256,2,190).text(16,88,"Two vertically adjacent slots",256,190).build());
 }
}
