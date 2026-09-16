package com.faceclaw.textdensity;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.Log;
import com.faceclaw.sdk.ControlEvent;
import com.faceclaw.sdk.DrawBatch;
import com.faceclaw.sdk.FaceclawAppService;
import com.faceclaw.sdk.FaceclawInputEvent;
import com.faceclaw.sdk.FaceclawSession;
import com.faceclaw.sdk.FrameLease;
import com.faceclaw.sdk.FrameMetadata;
import com.faceclaw.sdk.FrameOutcome;
import com.faceclaw.sdk.HostSnapshot;
import com.faceclaw.sdk.InvalidateReason;
import com.faceclaw.sdk.RenderRequest;
import com.faceclaw.sdk.RenderSurface;
import com.faceclaw.sdk.ResourceHandle;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DensityService extends FaceclawAppService {
 private static final String TAG="DensityLab",FONT_KEY="density-mono-14-v1";
 private static final int LEVELS=8,HEADER_HEIGHT=54,GLYPH_WIDTH=9,GLYPH_HEIGHT=18,GLYPH_BASELINE=14;
 private static final long DURATION_NS=700_000_000L;
 private static volatile DensityService active;
 private final Object stateLock=new Object();
 private final ExecutorService renderer=Executors.newSingleThreadExecutor();
 private FaceclawSession current;private RenderSurface surface;
 private int level,targetLevel,direction,lastSubmittedOffset;
 private boolean animating,copyEnabled=true,cacheEnabled=true;
 private long animationStartNs,prefetchAfterFrameId;
 private String cacheStatus="COLD";
 private Bitmap bitmap;private int[] argb;
 private volatile GlyphResources glyphs;
 private final Paint text=new Paint(Paint.ANTI_ALIAS_FLAG),rule=new Paint();

 private static final class GlyphResources {
  final Map<Character,ResourceHandle> byCharacter;final List<ResourceHandle> all;
  GlyphResources(Map<Character,ResourceHandle> values){byCharacter=values;all=new ArrayList<>(values.values());}
 }

 @Override public void onCreate(){super.onCreate();active=this;copyEnabled=getSharedPreferences("density-lab",MODE_PRIVATE).getBoolean("copy",true);cacheEnabled=getSharedPreferences("density-lab",MODE_PRIVATE).getBoolean("cache",true);text.setTypeface(Typeface.MONOSPACE);text.setColor(Color.WHITE);rule.setColor(Color.rgb(80,80,80));}
 @Override public void onDestroy(){if(active==this)active=null;renderer.shutdownNow();closeGlyphs();super.onDestroy();}
 @Override protected void onSessionReady(FaceclawSession session){current=session;surface=session.windowSurface();surface.setRasterRenderer(renderer,this::render);}
 @Override protected void onHostSnapshot(HostSnapshot snapshot){if(surface!=null&&snapshot.windowOpen)surface.invalidate(InvalidateReason.STATE);}
 @Override protected void onControlEvent(ControlEvent event){
  if(event.type.equals("resource-prefetch-result")){synchronized(stateLock){cacheStatus=event.data.optString("state","UNKNOWN").toUpperCase(Locale.US);}Log.i(TAG,"prefetch "+event.data);return;}
  if(surface!=null&&(event.type.equals("open")||event.type.equals("resize")||event.type.equals("visibility")))surface.invalidate(InvalidateReason.STATE);
 }
 @Override protected void onFrameOutcome(FrameOutcome outcome){
  boolean shouldPrefetch=false;
  synchronized(stateLock){if(outcome.clientFrameId==prefetchAfterFrameId&&(outcome.status==FrameOutcome.Status.DISPLAY_ACKED||outcome.status==FrameOutcome.Status.PREVIEW_COMMITTED||outcome.status==FrameOutcome.Status.DEDUPLICATED)){prefetchAfterFrameId=0;shouldPrefetch=cacheEnabled;}}
  if(shouldPrefetch)prefetchGlyphs();
 }
 @Override protected void onInput(RenderSurface inputSurface,FaceclawInputEvent event){
  if("long-press".equals(event.type)){boolean enabled; synchronized(stateLock){copyEnabled=!copyEnabled;enabled=copyEnabled;}getSharedPreferences("density-lab",MODE_PRIVATE).edit().putBoolean("copy",enabled).apply();inputSurface.invalidate(InvalidateReason.INPUT);return;}
  if("double-click".equals(event.type)||"back".equals(event.type)||"scroll-up".equals(event.type))start(-1);
  else if("click".equals(event.type)||"pointer-click".equals(event.type)||"scroll-down".equals(event.type))start(1);
 }

 static void openWindow(){DensityService service=active;if(service!=null)service.requestOpenWindow();}
 static void step(int direction){DensityService service=active;if(service!=null)service.start(direction);}
 static void reset(){DensityService service=active;if(service!=null){synchronized(service.stateLock){service.animating=false;service.level=0;service.targetLevel=0;service.lastSubmittedOffset=0;service.cacheStatus="COLD";}if(service.surface!=null)service.surface.invalidate(InvalidateReason.STATE);}}
 static void setCopyEnabled(boolean value){DensityService service=active;if(service!=null){synchronized(service.stateLock){service.copyEnabled=value;}if(service.surface!=null)service.surface.invalidate(InvalidateReason.STATE);}}
 static void setCacheEnabled(boolean value){DensityService service=active;if(service!=null){synchronized(service.stateLock){service.cacheEnabled=value;service.cacheStatus=value?"COLD":"OFF";}if(service.surface!=null)service.surface.invalidate(InvalidateReason.STATE);}}

 private void start(int step){RenderSurface nextSurface;synchronized(stateLock){if(animating)return;direction=step<0?-1:1;targetLevel=Math.floorMod(level+direction,LEVELS);animationStartNs=SystemClock.elapsedRealtimeNanos();lastSubmittedOffset=0;animating=true;nextSurface=surface;}if(nextSurface!=null)nextSurface.invalidate(InvalidateReason.ANIMATION);}

 private void render(FrameLease frame,RenderRequest request){
  int width=frame.width(),height=frame.height();ensureBitmap(width,height);ensureGlyphs();
  final int from,to,move,previousOffset,offset;final boolean moving,useCopy,useCache,continueAnimation;
  long sample=Math.max(SystemClock.elapsedRealtimeNanos(),request.credit.targetPresentationTimeNanos);
  synchronized(stateLock){
   from=level;to=targetLevel;move=direction;moving=animating;useCopy=copyEnabled;useCache=cacheEnabled;
   if(moving){double raw=Math.max(0,Math.min(1,(sample-animationStartNs)/(double)DURATION_NS));double eased=raw*raw*(3-2*raw);offset=(int)Math.round((move>0?-width:width)*eased);continueAnimation=raw<1;}
   else{offset=0;continueAnimation=false;}
   previousOffset=lastSubmittedOffset;lastSubmittedOffset=offset;
   if(moving&&!continueAnimation){level=to;targetLevel=to;animating=false;lastSubmittedOffset=0;}
  }

  Canvas canvas=new Canvas(bitmap);canvas.drawColor(Color.BLACK);boolean displayTransition=moving&&continueAnimation;
  drawHeader(canvas,moving&&!continueAnimation?to:from,to,displayTransition,useCopy,useCache);
  canvas.save();canvas.clipRect(0,HEADER_HEIGHT,width,height);
  if(displayTransition){drawPage(canvas,from,offset,height);drawPage(canvas,to,offset+(move>0?width:-width),height);}else drawPage(canvas,moving?to:from,0,height);
  canvas.restore();bitmap.getPixels(argb,0,width,0,0,width,height);ByteBuffer gray=frame.gray8();
  for(int value:argb){int alpha=value>>>24,luminance=(((value>>16)&255)*54+((value>>8)&255)*183+(value&255)*19)>>8;gray.put((byte)(luminance*alpha/255));}

  FaceclawSession session=current;long clientFrameId=session.nextClientFrameId();FrameMetadata.Builder metadata=FrameMetadata.builder(clientFrameId,session.nextContentVersion()).fullDamage(width,height).traceId(request.credit.traceId).requestNextFrame(continueAnimation);
  if(useCache&&glyphs!=null){DrawBatch.Builder draws=DrawBatch.builder();if(displayTransition){appendPageDraws(draws,from,offset,width,height);appendPageDraws(draws,to,offset+(move>0?width:-width),width,height);}else appendPageDraws(draws,moving?to:from,0,width,height);metadata.draws(draws.build());}
  int delta=offset-previousOffset;if(moving&&useCopy&&delta!=0&&Math.abs(delta)<width&&height>HEADER_HEIGHT){if(delta<0)metadata.retainedCopy(-delta,HEADER_HEIGHT,width+delta,height-HEADER_HEIGHT,0,HEADER_HEIGHT);else metadata.retainedCopy(0,HEADER_HEIGHT,width-delta,height-HEADER_HEIGHT,delta,HEADER_HEIGHT);}
  if(!continueAnimation)synchronized(stateLock){prefetchAfterFrameId=clientFrameId;}frame.submit(metadata.build());
 }

 private void prefetchGlyphs(){GlyphResources ready=glyphs;FaceclawSession session=current;if(ready==null||session==null)return;boolean sent=session.resources().prefetch(ready.all);synchronized(stateLock){cacheStatus=sent?"WARMING":"UNSUPPORTED";}}
 private void ensureBitmap(int width,int height){if(bitmap==null||bitmap.getWidth()!=width||bitmap.getHeight()!=height){if(bitmap!=null)bitmap.recycle();bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);argb=new int[width*height];}}
 private void ensureGlyphs(){
  if(glyphs!=null)return;LinkedHashSet<Character> characters=new LinkedHashSet<>();for(int density=0;density<LEVELS;density++)for(int row=0;row<rowsFor(density);row++)for(char value:rowText(row,density,charsFor(density)).toCharArray())if(value!=' ')characters.add(value);
  Paint glyphPaint=new Paint(Paint.ANTI_ALIAS_FLAG);glyphPaint.setTypeface(Typeface.MONOSPACE);glyphPaint.setTextSize(14);glyphPaint.setColor(Color.WHITE);LinkedHashMap<Character,ResourceHandle> values=new LinkedHashMap<>();
  for(char value:characters){Bitmap glyph=Bitmap.createBitmap(GLYPH_WIDTH,GLYPH_HEIGHT,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(glyph);canvas.drawColor(Color.TRANSPARENT);canvas.drawText(String.valueOf(value),0,GLYPH_BASELINE,glyphPaint);int[] pixels=new int[GLYPH_WIDTH*GLYPH_HEIGHT];glyph.getPixels(pixels,0,GLYPH_WIDTH,0,0,GLYPH_WIDTH,GLYPH_HEIGHT);glyph.recycle();byte[] gray=new byte[pixels.length];for(int i=0;i<pixels.length;i++){int pixel=pixels[i],alpha=pixel>>>24,luminance=(((pixel>>16)&255)*54+((pixel>>8)&255)*183+(pixel&255)*19)>>8;gray[i]=(byte)(luminance*alpha/255);}values.put(value,current.resources().registerGlyph(FONT_KEY,value,GLYPH_WIDTH,GLYPH_HEIGHT,ByteBuffer.wrap(gray)));}
  glyphs=new GlyphResources(values);
 }
 private void closeGlyphs(){GlyphResources existing=glyphs;glyphs=null;if(existing!=null)for(ResourceHandle glyph:existing.all)glyph.close();}
 private void drawHeader(Canvas canvas,int from,int to,boolean moving,boolean useCopy,boolean useCache){
  text.setTextSize(17);String label=moving?String.format(Locale.US,"DENSITY %d -> %d",from+1,to+1):String.format(Locale.US,"DENSITY %d / %d",from+1,LEVELS);canvas.drawText(label,14,22,text);
  text.setTextSize(12);text.setColor(Color.rgb(150,150,150));int shown=moving?Math.max(from,to):from;String status; synchronized(stateLock){status=cacheStatus;}String mode=(useCache?"GLYPHS "+status:"GLYPHS OFF")+" / "+(useCopy?"COPY ON":"COPY OFF")+"  "+rowsFor(shown)+"x"+charsFor(shown);canvas.drawText(mode,14,42,text);text.setColor(Color.WHITE);canvas.drawLine(0,HEADER_HEIGHT-1,bitmap.getWidth(),HEADER_HEIGHT-1,rule);
 }
 private void drawPage(Canvas canvas,int density,int pageX,int height){text.setTextSize(14);int rowStep=Math.max(GLYPH_HEIGHT,Math.max(1,height-HEADER_HEIGHT)/rowsFor(density));for(int row=0;row<rowsFor(density);row++){String value=rowText(row,density,charsFor(density));int top=HEADER_HEIGHT+row*rowStep;for(int col=0;col<value.length();col++){char character=value.charAt(col);if(character!=' ')canvas.drawText(String.valueOf(character),pageX+14+col*GLYPH_WIDTH,top+GLYPH_BASELINE,text);}}}
 private void appendPageDraws(DrawBatch.Builder out,int density,int pageX,int width,int height){GlyphResources ready=glyphs;if(ready==null)return;int rowStep=Math.max(GLYPH_HEIGHT,Math.max(1,height-HEADER_HEIGHT)/rowsFor(density));for(int row=0;row<rowsFor(density);row++){String value=rowText(row,density,charsFor(density));int y=HEADER_HEIGHT+row*rowStep;for(int col=0;col<value.length();col++){ResourceHandle glyph=ready.byCharacter.get(value.charAt(col));int x=pageX+14+col*GLYPH_WIDTH;if(glyph!=null&&x>=0&&x<width)out.glyph(glyph,x,y,255);}}}
 private static int rowsFor(int density){return 2+density*2;}
 private static int charsFor(int density){return 18+density*7;}
 private static String rowText(int row,int density,int max){String base=String.format(Locale.US,"%02d  session-%02d  Sep %02d  %02d:%02d  ",row+1,(density+1)*10+row,10+(row%18),8+(row%12),(row*7)%60);String tail="project-alpha / animation transport / retained text benchmark ";StringBuilder out=new StringBuilder(base);while(out.length()<max)out.append(tail);return out.substring(0,Math.min(max,out.length()));}
}
