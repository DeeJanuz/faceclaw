package com.faceclaw.textdensity;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.SystemClock;
import com.faceclaw.sdk.ControlEvent;
import com.faceclaw.sdk.FaceclawAppService;
import com.faceclaw.sdk.FaceclawInputEvent;
import com.faceclaw.sdk.FaceclawSession;
import com.faceclaw.sdk.FrameLease;
import com.faceclaw.sdk.FrameMetadata;
import com.faceclaw.sdk.HostSnapshot;
import com.faceclaw.sdk.InvalidateReason;
import com.faceclaw.sdk.RenderRequest;
import com.faceclaw.sdk.RenderSurface;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DensityService extends FaceclawAppService {
 private static final int LEVELS=8,HEADER_HEIGHT=54;
 private static final long DURATION_NS=700_000_000L;
 private static volatile DensityService active;
 private final Object stateLock=new Object();
 private final ExecutorService renderer=Executors.newSingleThreadExecutor();
 private FaceclawSession current;private RenderSurface surface;
 private int level,targetLevel,direction,lastSubmittedOffset;
 private boolean animating,copyEnabled=true;
 private long animationStartNs;
 private Bitmap bitmap;private int[] argb;
 private final Paint text=new Paint(Paint.ANTI_ALIAS_FLAG),muted=new Paint(Paint.ANTI_ALIAS_FLAG),rule=new Paint();

 @Override public void onCreate(){super.onCreate();active=this;copyEnabled=getSharedPreferences("density-lab",MODE_PRIVATE).getBoolean("copy",true);text.setTypeface(Typeface.MONOSPACE);text.setColor(Color.WHITE);muted.setTypeface(Typeface.MONOSPACE);muted.setColor(Color.rgb(150,150,150));rule.setColor(Color.rgb(80,80,80));}
 @Override public void onDestroy(){if(active==this)active=null;renderer.shutdownNow();super.onDestroy();}
 @Override protected void onSessionReady(FaceclawSession session){current=session;surface=session.windowSurface();surface.setRasterRenderer(renderer,this::render);}
 @Override protected void onHostSnapshot(HostSnapshot snapshot){if(surface!=null&&snapshot.windowOpen)surface.invalidate(InvalidateReason.STATE);}
 @Override protected void onControlEvent(ControlEvent event){if(surface!=null&&(event.type.equals("open")||event.type.equals("resize")||event.type.equals("visibility")))surface.invalidate(InvalidateReason.STATE);}
 @Override protected void onInput(RenderSurface inputSurface,FaceclawInputEvent event){
  if("long-press".equals(event.type)){boolean enabled; synchronized(stateLock){copyEnabled=!copyEnabled;enabled=copyEnabled;}getSharedPreferences("density-lab",MODE_PRIVATE).edit().putBoolean("copy",enabled).apply();inputSurface.invalidate(InvalidateReason.INPUT);return;}
  if("double-click".equals(event.type)||"back".equals(event.type)||"scroll-up".equals(event.type))start(-1);
  else if("click".equals(event.type)||"pointer-click".equals(event.type)||"scroll-down".equals(event.type))start(1);
 }

 static void openWindow(){DensityService service=active;if(service!=null)service.requestOpenWindow();}
 static void step(int direction){DensityService service=active;if(service!=null)service.start(direction);}
 static void reset(){DensityService service=active;if(service!=null){synchronized(service.stateLock){service.animating=false;service.level=0;service.targetLevel=0;service.lastSubmittedOffset=0;}if(service.surface!=null)service.surface.invalidate(InvalidateReason.STATE);}}
 static void setCopyEnabled(boolean value){DensityService service=active;if(service!=null){synchronized(service.stateLock){service.copyEnabled=value;}if(service.surface!=null)service.surface.invalidate(InvalidateReason.STATE);}}

 private void start(int step){
  RenderSurface nextSurface;
  synchronized(stateLock){if(animating)return;direction=step<0?-1:1;targetLevel=Math.floorMod(level+direction,LEVELS);animationStartNs=SystemClock.elapsedRealtimeNanos();lastSubmittedOffset=0;animating=true;nextSurface=surface;}
  if(nextSurface!=null)nextSurface.invalidate(InvalidateReason.ANIMATION);
 }

 private void render(FrameLease frame,RenderRequest request){
  int width=frame.width(),height=frame.height();ensureBitmap(width,height);
  final int from,to,move,previousOffset,offset;final boolean moving,useCopy,continueAnimation;
  long sample=Math.max(SystemClock.elapsedRealtimeNanos(),request.credit.targetPresentationTimeNanos);
  synchronized(stateLock){
   from=level;to=targetLevel;move=direction;moving=animating;useCopy=copyEnabled;
   if(moving){double raw=Math.max(0,Math.min(1,(sample-animationStartNs)/(double)DURATION_NS));double eased=raw*raw*(3-2*raw);offset=(int)Math.round((move>0?-width:width)*eased);continueAnimation=raw<1;}
   else{offset=0;continueAnimation=false;}
   previousOffset=lastSubmittedOffset;lastSubmittedOffset=offset;
   if(moving&&!continueAnimation){level=to;targetLevel=to;animating=false;lastSubmittedOffset=0;}
  }

  Canvas canvas=new Canvas(bitmap);canvas.drawColor(Color.BLACK);
  boolean displayTransition=moving&&continueAnimation;drawHeader(canvas,width,moving&&!continueAnimation?to:from,to,displayTransition,useCopy,offset);
  canvas.save();canvas.clipRect(0,HEADER_HEIGHT,width,height);
  if(displayTransition){drawPage(canvas,from,offset,width,height);drawPage(canvas,to,offset+(move>0?width:-width),width,height);}
  else drawPage(canvas,moving?to:from,0,width,height);
  canvas.restore();
  bitmap.getPixels(argb,0,width,0,0,width,height);ByteBuffer gray=frame.gray8();
  for(int value:argb){int alpha=value>>>24,luminance=(((value>>16)&255)*54+((value>>8)&255)*183+(value&255)*19)>>8;gray.put((byte)(luminance*alpha/255));}

  FaceclawSession session=current;FrameMetadata.Builder metadata=FrameMetadata.builder(session.nextClientFrameId(),session.nextContentVersion()).fullDamage(width,height).traceId(request.credit.traceId).requestNextFrame(continueAnimation);
  int delta=offset-previousOffset;
  if(moving&&useCopy&&delta!=0&&Math.abs(delta)<width&&height>HEADER_HEIGHT){if(delta<0)metadata.retainedCopy(-delta,HEADER_HEIGHT,width+delta,height-HEADER_HEIGHT,0,HEADER_HEIGHT);else metadata.retainedCopy(0,HEADER_HEIGHT,width-delta,height-HEADER_HEIGHT,delta,HEADER_HEIGHT);}
  frame.submit(metadata.build());
 }

 private void ensureBitmap(int width,int height){if(bitmap==null||bitmap.getWidth()!=width||bitmap.getHeight()!=height){if(bitmap!=null)bitmap.recycle();bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);argb=new int[width*height];}}
 private void drawHeader(Canvas canvas,int width,int from,int to,boolean moving,boolean useCopy,int offset){
  text.setTextSize(17);String label=moving?String.format(Locale.US,"DENSITY %d -> %d",from+1,to+1):String.format(Locale.US,"DENSITY %d / %d",from+1,LEVELS);canvas.drawText(label,14,22,text);
  muted.setTextSize(12);int shown=moving?Math.max(from,to):from;String mode=(useCopy?"COPY+REPAIR ON":"RASTER ONLY")+"   "+rowsFor(shown)+" rows   "+charsFor(shown)+" chars/row";canvas.drawText(mode,14,42,muted);canvas.drawLine(0,HEADER_HEIGHT-1,width,HEADER_HEIGHT-1,rule);
 }
 private void drawPage(Canvas canvas,int density,int pageX,int width,int height){
  int rows=rowsFor(density),characters=charsFor(density),bodyHeight=Math.max(1,height-HEADER_HEIGHT),rowStep=Math.max(12,bodyHeight/rows);text.setTextSize(Math.max(10,Math.min(14,rowStep-2)));muted.setTextSize(text.getTextSize());
  canvas.save();canvas.clipRect(pageX,HEADER_HEIGHT,pageX+width,height);int baseline=HEADER_HEIGHT+Math.min(rowStep-2,14);
  for(int row=0;row<rows;row++){String value=rowText(row,density,characters);Paint paint=(row%4==3)?muted:text;canvas.drawText(value,pageX+14,baseline+row*rowStep,paint);}
  canvas.restore();
 }
 private static int rowsFor(int density){return 2+density*2;}
 private static int charsFor(int density){return 18+density*7;}
 private static String rowText(int row,int density,int max){String base=String.format(Locale.US,"%02d  session-%02d  Sep %02d  %02d:%02d  ",row+1,(density+1)*10+row,10+(row%18),8+(row%12),(row*7)%60);String tail="project-alpha / animation transport / retained text benchmark ";StringBuilder out=new StringBuilder(base);while(out.length()<max)out.append(tail);return out.substring(0,Math.min(max,out.length()));}
}
