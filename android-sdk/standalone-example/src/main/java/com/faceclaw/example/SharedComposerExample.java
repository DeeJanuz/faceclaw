package com.faceclaw.example;

import com.faceclaw.sdk.AppControls;
import com.faceclaw.sdk.ComposerSession;
import java.util.function.Consumer;

/** Destination adapter: composition is shared, while delivery stays in the app. */
final class SharedComposerExample {
 static ComposerSession open(AppControls controls,String destination,String savedDraft,Consumer<String> confirmed) {
  if(!controls.supports("composer.session"))return null;
  return controls.composer(ComposerSession.Purpose.MESSAGE,destination,"Example message",savedDraft,8000,event->{
   if("confirmed".equals(event.optString("status"))&&event.opt("text") instanceof String)
    confirmed.accept(event.optString("text"));
  });
 }
 private SharedComposerExample() {}
}
