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
    private Bitmap body;
    private WindowMotion motion;

    private WindowMotion.Rect compact(){return new WindowMotion.Rect(16,16,Math.max(1,width-32),44);}
    private WindowMotion.Rect expanded(){return new WindowMotion.Rect(0,0,Math.max(1,width-5),Math.max(2,height-5));}

    @Override protected void onSessionReady(FaceclawSession session){
        surface=session.windowSurface();surface.setCanvasRenderer(renderer,this::draw);
    }
    @Override protected void onHostSnapshot(HostSnapshot snapshot){width=snapshot.windowWidth;height=snapshot.windowHeight;}
    @Override protected void onControlEvent(ControlEvent event){if("shared-style".equals(event.type)&&surface!=null)surface.invalidate(InvalidateReason.STATE);}
    @Override protected void onInput(RenderSurface target,FaceclawInputEvent event){
        if(!("click".equals(event.type)||"double-click".equals(event.type))||width<=32||height<=48)return;
        boolean opening="click".equals(event.type)&&!opened;if(opening==opened)return;opened=opening;
        if(!opening)releaseBody();
        if(motion!=null&&motion.isRunning())motion.retarget(opening?expanded():compact(),!opening);
        else motion=new WindowMotion(opening?compact():expanded(),opening?expanded():compact(),!opening);
        target.invalidate(InvalidateReason.INPUT);
    }
    private void draw(Canvas canvas,RenderRequest request){
        width=canvas.getWidth();height=canvas.getHeight();canvas.drawColor(Color.BLACK);
        WindowMotion.Frame state;
        if(motion==null||!motion.isRunning()){WindowMotion settled=new WindowMotion(opened?expanded():compact(),opened?expanded():compact(),!opened);settled.sample(0);state=settled.sample(WindowMotion.DURATION_MS);}
        else{state=motion.sample(request.credit.targetPresentationTimeNanos/1_000_000L);if(state==null)return;if(!state.done)request.requestNextFrame();}
        Ui.transitionCard(canvas,state,"EXAMPLE",bodyCanvas->{if(body==null){body=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);Canvas content=new Canvas(body);Ui.text(content,"Body appears at 90% open.",16,72,17,Color.LTGRAY);Ui.text(content,"Back clears text before reversing.",16,97,17,Color.LTGRAY);}bodyCanvas.drawBitmap(body,0,0,null);});
        if(state.done)motion=null;
    }
    private void releaseBody(){if(body!=null){body.recycle();body=null;}}
    @Override protected void onSessionLost(DisconnectInfo info){if(!info.recoverable){motion=null;releaseBody();width=height=0;}}
    @Override public void onDestroy(){renderer.shutdownNow();releaseBody();super.onDestroy();}
}
