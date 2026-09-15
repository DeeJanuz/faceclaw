package com.faceclaw.sdk;

import java.util.concurrent.Executor;

/** Adapter for an already authenticated SDK session. The app retains ownership of its controller. */
public final class HostPresentation implements AutoCloseable {
 private final RenderSurface surface;private final AppPresentation app;
 public HostPresentation(FaceclawSession session,Executor executor,AppPresentation app){this.surface=session.windowSurface();this.app=app;surface.setCanvasRenderer(executor,(canvas,request)->app.paint(canvas,surface.width(),surface.height()));}
 public void input(String type,String source){app.input(type,source);surface.invalidate(InvalidateReason.INPUT);}
 public void visible(boolean value){app.visible(value);if(value)surface.invalidate(InvalidateReason.STATE);}
 public void invalidate(){surface.invalidate(InvalidateReason.STATE);}
 @Override public void close(){app.visible(false);}
}
