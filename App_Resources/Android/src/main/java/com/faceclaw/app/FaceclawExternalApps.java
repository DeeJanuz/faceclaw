package com.faceclaw.app;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.Color;
import android.os.*;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.*;
import com.faceclaw.sdk.*;
import org.json.*;
import java.nio.ByteBuffer;
import java.util.*;

/** Host-side policy owner. Everything from an external UID is untrusted. */
public final class FaceclawExternalApps {
 private static FaceclawExternalApps instance;
 public static synchronized FaceclawExternalApps get(Context c) { if(instance==null) instance=new FaceclawExternalApps(c.getApplicationContext()); return instance; }
 private final Context context; private final Handler main=new Handler(Looper.getMainLooper());
 private final Map<String,Connection> connections=new java.util.concurrent.ConcurrentHashMap<>();
 private FaceclawExternalAppListener listener;
 private final SharedPreferences prefs;
 private static final String[] APPROVAL_CAPABILITIES={"notifications","dictation","previews","suppress"};
 private FaceclawExternalApps(Context c) {
  context=c; prefs=ApprovalStore.open(c,"faceclaw-external-apps");
  IntentFilter filter=new IntentFilter(); filter.addAction(Intent.ACTION_PACKAGE_ADDED); filter.addAction(Intent.ACTION_PACKAGE_REMOVED); filter.addAction(Intent.ACTION_PACKAGE_REPLACED); filter.addDataScheme("package");
  c.registerReceiver(new BroadcastReceiver() { public void onReceive(Context ignored,Intent intent) {
   if(Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction()) && !intent.getBooleanExtra(Intent.EXTRA_REPLACING,false) && intent.getData()!=null) {
    String pkg=intent.getData().getSchemeSpecificPart(); SharedPreferences.Editor edit=prefs.edit();
    for(String key:prefs.getAll().keySet()) if(key.startsWith(pkg+"/")) edit.remove(key); edit.apply();
   }
   refresh();
  } },filter);
 }
 public void setListener(FaceclawExternalAppListener value) { listener=value; refresh(); }
 private List<ResolveInfo> discover() { return context.getPackageManager().queryIntentServices(new Intent(Protocol.ACTION),PackageManager.GET_META_DATA); }
 private String key(ServiceInfo s) { return new ComponentName(s.packageName,s.name).flattenToString(); }
 private boolean validService(ServiceInfo s) { return s.exported && s.enabled && s.applicationInfo.enabled && s.metaData!=null && s.metaData.getInt("com.faceclaw.PROTOCOL_MAJOR",0)==Protocol.VERSION; }
 private boolean approved(ServiceInfo s) {
  try { return validService(s) && PackageIdentity.forPackage(context,s.packageName).equals(prefs.getString(key(s)+":pin","")); } catch(Exception e) { return false; }
 }
 public String installedJson() {
  JSONArray result=new JSONArray();
  for(ResolveInfo r:discover()) if(approved(r.serviceInfo)) {
   Connection c=connections.get(key(r.serviceInfo));
   result.put(Protocol.object("component",key(r.serviceInfo),"name",r.loadLabel(context.getPackageManager()).toString(),"connected",c!=null&&c.ready));
  }
  return result.toString();
 }
 public void refresh() {
  Set<String> present=new HashSet<>();
  for(ResolveInfo r:discover()) if(approved(r.serviceInfo)) { String k=key(r.serviceInfo); present.add(k); if(!connections.containsKey(k)&&connections.size()<8) bind(r.serviceInfo); }
  for(String k:new ArrayList<>(connections.keySet())) if(!present.contains(k)) disconnect(k,false);
  emit("","changed",new JSONObject());
 }
 private void bind(ServiceInfo s) {
  Connection c=new Connection(s); connections.put(c.component,c);
  try { if(!context.bindService(new Intent(Protocol.ACTION).setComponent(new ComponentName(s.packageName,s.name)),c,Context.BIND_AUTO_CREATE)) disconnect(c.component,false); }
  catch(Exception e) { disconnect(c.component,false); }
 }
 private void emit(String component,String type,JSONObject data) { if(listener!=null) listener.onEvent(component,type,data.toString()); }
 public boolean isConnected(String component) { Connection c=connections.get(component); return c!=null&&c.ready&&approved(c.service); }
 public boolean allows(String component,String capability) { return Arrays.asList(APPROVAL_CAPABILITIES).contains(capability)&&isConnected(component)&&prefs.getBoolean(component+":"+capability,"previews".equals(capability)); }
 public boolean replyToNotification(String component,String json) {
  if(json==null||json.length()>Protocol.MAX_JSON||!allows(component,"notifications")||!allows(component,"dictation")) return false;
  Connection c=connections.get(component);
  try {
   JSONObject data=new JSONObject(json);
   if(!Boolean.TRUE.equals(data.opt("confirmed"))||!(data.opt("text") instanceof String)||data.getString("text").trim().isEmpty()||data.getString("text").length()>8000) return false;
   for(String field:new String[]{"id","target","replyToken"}) if(!(data.opt(field) instanceof String)||data.getString(field).isEmpty()||data.getString(field).length()>(field.equals("target")?512:128)) return false;
   c.remote.send(Protocol.message(Protocol.EVENT,c.session,"notification-reply",data)); return true;
  } catch(Exception ignored) { return false; }
 }
 public void send(String component,String type,String json) {
  if(type.equals("notification-reply")) { replyToNotification(component,json); return; }
  Connection c=connections.get(component); if(c==null||!c.ready||!approved(c.service)) return;
  try {
   JSONObject data=new JSONObject(json);
   if(type.equals("open")||type.equals("resize")) {
    c.width=data.getInt("width"); c.height=data.getInt("height"); Protocol.frameSize(c.width,c.height); data.put("generation",++c.generation); c.open=true;
   }
   if(type.equals("close")) { c.open=false; c.visible=false; c.generation++; }
   if(type.equals("visibility")) { c.visible=data.optBoolean("visible"); c.screenOn=data.optBoolean("screenOn"); }
   c.remote.send(Protocol.message(Protocol.EVENT,c.session,type,data));
  } catch(Exception e) { disconnect(component,true); }
 }
 private void publishCapabilities(String component) {
  send(component,"capabilities",Protocol.object("notifications",allows(component,"notifications"),"dictation",allows(component,"dictation"),"previews",allows(component,"previews"),"maxWidth",Protocol.MAX_WIDTH,"maxHeight",Protocol.MAX_HEIGHT,"maxText",8000,"maxNotificationText",4096,"notificationReplies",true,"searchDictation",true).toString());
 }
 private void disconnect(String component,boolean retry) {
  Connection c=connections.remove(component); if(c==null) return;
  c.ready=false; c.generation++;
  try { if(c.remote!=null) c.remote.send(Protocol.message(Protocol.EVENT,c.session,"revoke",null)); } catch(Exception ignored) {}
  try { context.unbindService(c); } catch(Exception ignored) {}
  emit(component,"disconnected",new JSONObject());
  if(retry && approved(c.service)) main.postDelayed(()->{ if(!connections.containsKey(component)&&approved(c.service)) bind(c.service); },5000);
 }
 private final class Connection implements ServiceConnection {
  final ServiceInfo service; final String component,session=UUID.randomUUID().toString(); final Messenger inbox;
  java.lang.ref.WeakReference<Activity> selectionActivity; long selectionUntil;
  Messenger remote; PendingIntent consent; boolean ready,open,visible,screenOn=true; int width,height; long generation,sequence,lastFrame,rateStart; int rate;
  Connection(ServiceInfo service) { this.service=service; component=key(service); inbox=new Messenger(new Handler(Looper.getMainLooper(),m->{ receive(m); return true; })); }
  public void onServiceConnected(ComponentName name,IBinder binder) {
   if(connections.get(component)!=this || !approved(service)) return;
   remote=new Messenger(binder);
   try { Message m=Protocol.message(Protocol.HELLO,session,"hello",Protocol.object("version",Protocol.VERSION)); m.replyTo=inbox; remote.send(m); }
   catch(Exception e) { disconnect(component,true); }
  }
  public void onServiceDisconnected(ComponentName name) { disconnect(component,true); }
  public void onBindingDied(ComponentName name) { disconnect(component,true); }
  public void onNullBinding(ComponentName name) { disconnect(component,false); }
  void receive(Message m) {
   try {
    if(connections.get(component)!=this || m.sendingUid!=service.applicationInfo.uid || !approved(service) || !PackageIdentity.forUid(context,m.sendingUid).equals(prefs.getString(component+":pin",""))) return;
    Bundle b=m.getData(); if(!session.equals(b.getString("session"))) return;
    long now=SystemClock.elapsedRealtime(); if(now-rateStart>1000) { rateStart=now; rate=0; }
    if(++rate>240) { disconnect(component,false); return; }
    if(m.what==Protocol.CONSENT) {
     PendingIntent pi=b.getParcelable("consent");
     if(pi!=null&&pi.getCreatorUid()==m.sendingUid&&service.packageName.equals(pi.getCreatorPackage())&&(Build.VERSION.SDK_INT<31||pi.isActivity())) {
      consent=pi; emit(component,"consent",new JSONObject()); launchRequestedConsent(this,pi);
     } return;
    }
    if(m.what==Protocol.READY) {
     if(Protocol.json(b).optInt("version")!=Protocol.VERSION) return;
     ready=true; consent=null; selectionActivity=null; publishCapabilities(component); emit(component,"connected",new JSONObject()); return;
    }
    if(!ready) return;
    if(m.what==Protocol.FRAME) {
     long seq=b.getLong("sequence");
     try {
      int w=b.getInt("width"),h=b.getInt("height"); int size=Protocol.frameSize(w,h);
      if(!open||!visible||!screenOn||w!=width||h!=height||b.getLong("generation")!=generation||seq<=sequence) return;
      sequence=seq; if(now-lastFrame<16) return; lastFrame=now;
      byte[] copy;
      if(Build.VERSION.SDK_INT>=27 && b.containsKey("memory")) {
       SharedMemory memory=b.getParcelable("memory"); if(memory==null) return;
       try { if(memory.getSize()!=size) return; ByteBuffer mapping=memory.mapReadOnly(); try { copy=new byte[size]; mapping.get(copy); } finally { SharedMemory.unmap(mapping); } } finally { memory.close(); }
      } else { byte[] bytes=b.getByteArray("pixels"); if(bytes==null||bytes.length!=size) return; copy=bytes.clone(); }
      // Private snapshot only; app changes to shared memory can no longer reach native rendering.
      if(listener!=null) listener.onFrame(component,w,h,ByteBuffer.wrap(copy));
     } finally {
      // Close descriptors even on early rejection before mapping.
      if(Build.VERSION.SDK_INT>=27 && b.containsKey("memory")) { SharedMemory mem=b.getParcelable("memory"); if(mem!=null) mem.close(); }
      Message ack=Protocol.message(Protocol.ACK,session,"ack",null); ack.getData().putLong("sequence",seq); remote.send(ack);
     }
     return;
    }
    if(m.what!=Protocol.EVENT) return;
    String type=b.getString("type",""); JSONObject data=Protocol.json(b);
    if(type.equals("host-switched")) {
     prefs.edit().remove(component+":pin").remove(component+":notifications").remove(component+":dictation").remove(component+":previews").remove(component+":suppress").apply();
     disconnect(component,false); return;
    }
    if(type.equals("disconnected")) { disconnect(component,false); return; }
    if(type.equals("notification")||type.equals("remove-notification")||type.equals("notification-reply-result")) { if(!allows(component,"notifications")) return; }
    else if(type.equals("dictation")||type.equals("cancel-dictation")||type.equals("search-dictation")||type.equals("cancel-search-dictation")) { if(!allows(component,"dictation")||!open||!visible||!screenOn) {
      if(type.equals("dictation")||type.equals("search-dictation")) remote.send(Protocol.message(Protocol.EVENT,session,type.equals("search-dictation")?"search-dictation-rejected":"dictation-rejected",Protocol.object("requestId",data.optString("requestId","").substring(0,Math.min(128,data.optString("requestId","").length())),"reason","Dictation permission or visible window required")));
      return;
     } }
    else return;
    emit(component,type,data);
   } catch(Exception ignored) { disconnect(component,false); }
  }
 }
 public boolean isSourceSuppressed(String packageName) {
  if(packageName==null || !packageName.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+")) return false;
  for(Connection c:connections.values()) if(c.ready && allows(c.component,"suppress") && c.service.metaData!=null && packageName.equals(c.service.metaData.getString("com.faceclaw.SUPPRESS_PACKAGE",""))) return true;
  return false;
 }
 public String suppressedPackagesJson() {
  JSONArray values=new JSONArray();
  for(Connection c:connections.values()) if(c.ready && allows(c.component,"suppress") && c.service.metaData!=null) {
   String source=c.service.metaData.getString("com.faceclaw.SUPPRESS_PACKAGE","");
   if(source.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+")) values.put(source);
  }
  return values.toString();
 }
 public void showManager(Activity activity) {
  if(activity==null) return; refresh(); List<ResolveInfo> apps=discover();
  String[] labels=new String[apps.size()]; for(int i=0;i<apps.size();i++) { ResolveInfo r=apps.get(i); labels[i]=r.loadLabel(context.getPackageManager())+(approved(r.serviceInfo)?" (approved)":""); }
  new AlertDialog.Builder(activity).setTitle("Installed Faceclaw apps").setItems(labels,(d,index)->showApp(activity,apps.get(index))).setNegativeButton("Close",null).show();
 }
 /** Navigation only. The package hint never supplies service metadata, identity or grants. */
 public void showAppSettings(Activity activity,String appPackage) {
  if(appPackage!=null && appPackage.length()<=255 && appPackage.matches("[a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+")) {
   for(ResolveInfo app:discover()) if(appPackage.equals(app.serviceInfo.packageName)) {
    showApp(activity,app); return;
   }
  }
  showManager(activity);
 }
 private boolean canShowConsent(Activity activity) {
  return activity!=null&&!activity.isFinishing()&&!activity.isDestroyed()&&activity.getWindow()!=null&&
   activity.getWindow().getDecorView().getWindowVisibility()==android.view.View.VISIBLE;
 }
 private void selectionUnavailable(Activity activity) {
  if(canShowConsent(activity)) new AlertDialog.Builder(activity).setMessage("Could not open the app's host selection. Keep this screen open and try again.").setPositiveButton("OK",null).show();
 }
 /** The explicit user tap gets a new handshake/session, never a cached expired consent token. */
 private void requestHostSelection(Activity activity,String component) {
  if(!canShowConsent(activity)) return;
  Connection previous=connections.get(component);
  if(previous==null||!approved(previous.service)) { selectionUnavailable(activity); return; }
  if(previous.ready) return;
  ServiceInfo service=previous.service; disconnect(component,false); bind(service);
  Connection requested=connections.get(component);
  if(requested==null) { selectionUnavailable(activity); return; }
  requested.selectionActivity=new java.lang.ref.WeakReference<>(activity);
  requested.selectionUntil=SystemClock.elapsedRealtime()+5000;
  main.postDelayed(()->{
   Activity waiting=requested.selectionActivity==null?null:requested.selectionActivity.get();
   requested.selectionActivity=null;
   if(waiting!=null&&!requested.ready) selectionUnavailable(waiting);
  },5000);
 }
 /** Android 14+ needs the visible sender to opt in for this one user-requested launch. */
 private void launchRequestedConsent(Connection connection,PendingIntent consent) {
  Activity activity=connection.selectionActivity==null?null:connection.selectionActivity.get();
  connection.selectionActivity=null;
  if(!canShowConsent(activity)||SystemClock.elapsedRealtime()>connection.selectionUntil||connections.get(connection.component)!=connection||!approved(connection.service)) return;
  ActivityOptions options=ActivityOptions.makeBasic();
  if(Build.VERSION.SDK_INT>=34) options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
  try { consent.send(activity,0,null,null,null,null,options.toBundle()); }
  catch(PendingIntent.CanceledException|SecurityException error) { selectionUnavailable(activity); }
 }
 /** Fresh defaults are a proposal for explicit approval, never read-time grants. */
 private boolean[] approvalChoices(String component) {
  boolean existing=prefs.contains(component+":pin");
  for(String capability:APPROVAL_CAPABILITIES) existing|=prefs.contains(component+":"+capability);
  boolean[] choices=new boolean[APPROVAL_CAPABILITIES.length];
  for(int i=0;i<choices.length;i++) {
   String capability=APPROVAL_CAPABILITIES[i];
   choices[i]=prefs.getBoolean(component+":"+capability,!existing || capability.equals("previews"));
  }
  return choices;
 }
 /** Called only by the approval button; identity and all choices share one durable write. */
 private boolean approveSelection(ServiceInfo requested,String pin,boolean[] choices) {
  if(choices==null || choices.length!=APPROVAL_CAPABILITIES.length) return false;
  try {
   ServiceInfo current=context.getPackageManager().getServiceInfo(new ComponentName(requested.packageName,requested.name),PackageManager.GET_META_DATA);
   if(!validService(current) || !PackageIdentity.forPackage(context,current.packageName).equals(pin)) return false;
   String component=key(current);
   SharedPreferences.Editor edit=prefs.edit().putString(component+":pin",pin);
   for(int i=0;i<choices.length;i++) edit.putBoolean(component+":"+APPROVAL_CAPABILITIES[i],choices[i]);
   return edit.commit();
  } catch(Exception ignored) { return false; }
 }
 private void revokeApproval(String component) {
  SharedPreferences.Editor edit=prefs.edit().remove(component+":pin");
  for(String capability:APPROVAL_CAPABILITIES) edit.remove(component+":"+capability);
  edit.apply(); disconnect(component,false); emit(component,"changed",new JSONObject());
 }
 private void showApp(Activity a,ResolveInfo resolved) {
  ServiceInfo s=resolved.serviceInfo; String k=key(s);
  if(!validService(s)) { new AlertDialog.Builder(a).setMessage("This app requires an incompatible Faceclaw protocol or its service is disabled.").setPositiveButton("OK",null).show(); return; }
  if(!approved(s)) {
   try {
    String pin=PackageIdentity.forPackage(context,s.packageName);
    boolean[] choices=approvalChoices(k);
    int padding=(int)(20*a.getResources().getDisplayMetrics().density);
    LinearLayout content=new LinearLayout(a); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(padding,padding,padding,0);
    TextView explanation=new TextView(a); explanation.setText("Allow this independently installed app to show a glasses window and receive input? Checked permissions will be enabled when you approve. You can uncheck any permission now or change it later."); content.addView(explanation);
    String source=s.metaData.getString("com.faceclaw.SUPPRESS_PACKAGE","");
    String[] labels={"Notifications on glasses","Dictation and review","Message text previews","Suppress declared source on glasses"};
    for(int i=0;i<choices.length;i++) {
     final int index=i; CheckBox choice=new CheckBox(a); choice.setText(labels[i]); choice.setChecked(choices[i]); choice.setFilterTouchesWhenObscured(true);
     choice.setOnCheckedChangeListener((button,checked)->choices[index]=checked); content.addView(choice);
    }
    TextView detail=new TextView(a); detail.setText("Suppression applies only to the app's declared source while connected. Phone notifications stay unchanged."+(source.isEmpty()?"\nNo source is currently declared.":"\nDeclared source: "+source)+"\n\nSigning identity: "+pin); content.addView(detail);
    ScrollView scroll=new ScrollView(a); scroll.addView(content);
    AlertDialog consent=new AlertDialog.Builder(a).setTitle("Approve "+resolved.loadLabel(context.getPackageManager()))
     .setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Approve",(d,w)->{
      if(!approveSelection(s,pin,choices)) { new AlertDialog.Builder(a).setMessage("App identity changed or approval could not be saved. Reopen app settings and try again.").setPositiveButton("OK",null).show(); return; }
      refresh(); main.postDelayed(()->showApp(a,resolved),400);
     }).create();
    consent.show(); consent.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    consent.getButton(AlertDialog.BUTTON_POSITIVE).setFilterTouchesWhenObscured(true);
   } catch(Exception ignored) {} return;
  }
  Connection c=connections.get(k);
  ArrayList<String> options=new ArrayList<>(); options.add(c!=null&&c.ready?"Host selected":"Select this host on app");
  options.add("Notifications: "+prefs.getBoolean(k+":notifications",false)); options.add("Dictation/review: "+prefs.getBoolean(k+":dictation",false));
  options.add("Message text previews: "+prefs.getBoolean(k+":previews",true));
  options.add("Suppress declared source on glasses: "+prefs.getBoolean(k+":suppress",false));
  options.add("Configure bridge connection"); options.add("Revoke app approval");
  new AlertDialog.Builder(a).setTitle(resolved.loadLabel(context.getPackageManager())).setItems(options.toArray(new String[0]),(d,index)->{
   if(index==0) requestHostSelection(a,k);
   else if(index>=1&&index<=4) {
    String flag=new String[]{"","notifications","dictation","previews","suppress"}[index];
    String source=s.metaData.getString("com.faceclaw.SUPPRESS_PACKAGE","");
    if(index==4&&source.isEmpty()) return;
    boolean enabled=prefs.getBoolean(k+":"+flag,flag.equals("previews"));
    Runnable change=()->{ prefs.edit().putBoolean(k+":"+flag,!enabled).apply(); emit(k,"grants-changed",new JSONObject()); publishCapabilities(k); };
    if(!enabled) new AlertDialog.Builder(a).setMessage(index==4?"Suppress only "+source+" notifications on glasses while this app is connected? Phone notifications stay unchanged.":"Enable "+options.get(index).split(":")[0]+" for this app?").setNegativeButton("Cancel",null).setPositiveButton("Enable",(dialog,which)->change.run()).show(); else change.run();
   } else if(index==5) configure(a,k,s); else revokeApproval(k);
  }).setNegativeButton("Close",null).show();
 }
 private void configure(Activity a,String k,ServiceInfo service) {
  if(!isConnected(k)||!"bridge".equals(service.metaData.getString("com.faceclaw.CONFIGURATION",""))) return;
  LinearLayout layout=new LinearLayout(a); layout.setOrientation(LinearLayout.VERTICAL);
  EditText endpoint=new EditText(a); endpoint.setHint("https://private-bridge.example"); endpoint.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
  EditText code=new EditText(a); code.setHint("One-time bridge pairing code"); code.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
  layout.addView(endpoint); layout.addView(code);
  AlertDialog dialog=new AlertDialog.Builder(a).setTitle("Connect app to bridge").setView(layout).setNegativeButton("Cancel",null).setPositiveButton("Pair",(d,w)->{
   String url=endpoint.getText().toString().trim(), token=code.getText().toString().trim();
   if(url.startsWith("https://")&&url.length()<2048&&token.length()>=16&&token.length()<=512) send(k,"configure",Protocol.object("endpoint",url,"code",token).toString());
   code.setText("");
  }).create(); dialog.getWindow(); dialog.show(); dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
 }
}
