package example.faceclaw;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import com.faceclaw.sdk.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Credit-driven card motion. The host's presentation time is the animation clock. */
public final class AnimatedCardAppService extends FaceclawAppService {
    private final ExecutorService renderer=Executors.newSingleThreadExecutor();
    private RenderSurface surface;
    private int width,height;
    private boolean opened;
    private boolean suspended, neutralHeading;
    private Bitmap body;
    private WindowMotion motion;

    private WindowMotion.Rect compact(){return new WindowMotion.Rect(16,16,Math.max(1,width-32),44);}
    private WindowMotion.Rect expanded(){return new WindowMotion.Rect(0,0,Math.max(1,width-5),Math.max(2,height-5));}

    @Override protected synchronized void onSessionReady(FaceclawSession session){
        suspended=false;motion=null;
        surface=session.windowSurface();surface.setCanvasRenderer(renderer,this::draw);
    }
    @Override protected synchronized void onHostSnapshot(HostSnapshot snapshot){
        if(width!=snapshot.windowWidth||height!=snapshot.windowHeight){motion=null;releaseBody();}
        width=snapshot.windowWidth;height=snapshot.windowHeight;
        suspended=!snapshot.windowOpen||!snapshot.windowVisible||!snapshot.screenOn;
        if(suspended){motion=null;releaseBody();}
    }
    @Override protected synchronized void onControlEvent(ControlEvent event){
        if("shared-style".equals(event.type))invalidateContent();
        else if("close".equals(event.type)){suspended=true;motion=null;releaseBody();}
        else if("visibility".equals(event.type)||"open".equals(event.type)||"resize".equals(event.type)){
            suspended=!event.data.optBoolean("visible",true)||!event.data.optBoolean("screenOn",true);
            if(suspended||"resize".equals(event.type)){motion=null;releaseBody();}
            if(!suspended&&surface!=null)surface.invalidate(InvalidateReason.RECOVERY);
        }
    }
    @Override protected synchronized void onInput(RenderSurface target,FaceclawInputEvent event){
        if(target!=surface||suspended)return;
        if(!("click".equals(event.type)||"double-click".equals(event.type))||width<=32||height<=48)return;
        boolean opening="click".equals(event.type);if(opening==opened)return;opened=opening;neutralHeading=false;
        if(!opening)releaseBody();
        if(motion!=null&&motion.isRunning())motion.retarget(opening?expanded():compact(),!opening);
        else motion=new WindowMotion(opening?compact():expanded(),opening?expanded():compact(),!opening);
        target.invalidate(InvalidateReason.INPUT);
    }
    private synchronized void draw(Canvas canvas,RenderRequest request){
        if(suspended)return;
        if(width!=canvas.getWidth()||height!=canvas.getHeight()){motion=null;releaseBody();}
        width=canvas.getWidth();height=canvas.getHeight();canvas.drawColor(Color.BLACK);
        WindowMotion.Frame state;
        if(motion==null||!motion.isRunning()){WindowMotion settled=new WindowMotion(opened?expanded():compact(),opened?expanded():compact(),!opened);settled.sample(0);state=settled.sample(WindowMotion.DURATION_MS);}
        else{state=motion.sample(request.credit.targetPresentationTimeNanos/1_000_000L);if(state==null)return;if(!state.done)request.requestNextFrame();}
        Ui.transitionCard(canvas,state,neutralHeading?"":"EXAMPLE",bodyCanvas->{if(body==null){body=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);Canvas content=new Canvas(body);Ui.text(content,"Body appears at 90% open.",16,72,17,Color.LTGRAY);Ui.text(content,"Back clears text before reversing.",16,97,17,Color.LTGRAY);}bodyCanvas.drawBitmap(body,0,0,null);});
        if(state.done)motion=null;
    }
    private void releaseBody(){if(body!=null){body.recycle();body=null;}}
    /** App hook, not an SDK callback. Call only when relevant ambient values change. */
    public synchronized void ambientChanged(){if(!suspended&&surface!=null)surface.invalidate(InvalidateReason.STATE);}
    /** Erase cached content, but retain safe closing geometry. */
    public synchronized void invalidateContent(){releaseBody();if(motion!=null&&motion.isRunning()&&!opened)neutralHeading=true;if(!suspended&&surface!=null)surface.invalidate(InvalidateReason.STATE);}
    @Override protected synchronized void onSessionLost(DisconnectInfo info){suspended=true;motion=null;releaseBody();width=height=0;}
    @Override public synchronized void onDestroy(){suspended=true;renderer.shutdownNow();releaseBody();super.onDestroy();}
}
