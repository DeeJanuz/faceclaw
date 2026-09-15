package com.faceclaw.sdk;

import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;

/** Phone-only presentation view. It contains no host, microphone or messaging transport. */
public final class LocalPresentation extends View {
 private final AppPresentation app;
 public LocalPresentation(Context context,AppPresentation app){super(context);this.app=app;setContentDescription("App preview. Tap to interact.");setFocusable(true);setClickable(true);}
 @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);app.paint(canvas,getWidth(),getHeight());}
 @Override public boolean onTouchEvent(MotionEvent event){if(event.getAction()==MotionEvent.ACTION_UP)return performClick();return true;}
 @Override public boolean performClick(){super.performClick();app.input("click","phone");invalidate();return true;}
 @Override protected void onAttachedToWindow(){super.onAttachedToWindow();app.visible(true);}
 @Override protected void onDetachedFromWindow(){app.visible(false);super.onDetachedFromWindow();}
}
