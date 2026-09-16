package com.faceclaw.app;

import com.faceclaw.sdk.FrameOutcome;
import java.nio.ByteBuffer;

/** Transport seam used by the host boundary fixture; physical BLE is deliberately absent. */
public final class FaceclawBleCommunicator {
 public static FaceclawBleCommunicator getActive(){return null;}
 public boolean isDisplayAvailable(){return false;}
 public long renderCreditDelayMs(){return 0;}
 public String getFirmwareFingerprint(){return "";}
 public void submitExternalSurfaceFrame(ByteBuffer pixels,String id,int width,int height,String fingerprint,ByteBuffer draws,ExternalFrameOutcomeListener outcome){if(outcome!=null)outcome.onOutcome(FrameOutcome.Status.PREVIEW_COMMITTED,"Host test transport");}
 public void submitExternalSurfaceFrame(ByteBuffer pixels,String id,int width,int height,int[] damage,String fingerprint,ByteBuffer draws,ExternalFrameOutcomeListener outcome){if(outcome!=null)outcome.onOutcome(FrameOutcome.Status.PREVIEW_COMMITTED,"Host test transport");}
 public void submitExternalSurfaceFrame(ByteBuffer pixels,String id,int width,int height,int[] damage,String fingerprint,ByteBuffer draws,ExternalFrameOutcomeListener outcome,String traceId){if(outcome!=null)outcome.onOutcome(FrameOutcome.Status.PREVIEW_COMMITTED,"Host test transport");}
 public void submitExternalSurfaceFrame(ByteBuffer pixels,String id,int width,int height,int[] damage,String fingerprint,ByteBuffer draws,int[] retainedCopies,ExternalFrameOutcomeListener outcome,String traceId){if(outcome!=null)outcome.onOutcome(FrameOutcome.Status.PREVIEW_COMMITTED,"Host test transport");}
}
