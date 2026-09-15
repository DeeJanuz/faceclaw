package com.faceclaw.example;
import com.faceclaw.sdk.*;
public final class CounterService extends FaceclawAppService {
 private HostPresentation presentation;
 @Override protected void onSessionReady(FaceclawSession session){presentation=new HostPresentation(session,Runnable::run,new CounterPresentation(this));}
 @Override protected void onControlEvent(ControlEvent event){if(presentation!=null&&event.type.equals("open"))presentation.invalidate();}
 @Override protected void onInput(RenderSurface surface,FaceclawInputEvent event){if(presentation!=null)presentation.input(event.type,event.source);}
}
