package com.faceclaw.app;

import com.faceclaw.sdk.FrameOutcome;
import java.nio.ByteBuffer;

/** Transport seam used by the host boundary fixture; physical BLE is deliberately absent. */
public final class FaceclawBleCommunicator {
 public static final class TexturePrefetchResult {public final String state;public final int requested,resident,uploadBytes,cacheBytes;TexturePrefetchResult(String state,int requested,int resident,int uploadBytes,int cacheBytes){this.state=state;this.requested=requested;this.resident=resident;this.uploadBytes=uploadBytes;this.cacheBytes=cacheBytes;}}
 public static FaceclawBleCommunicator getActive(){return null;}
 public boolean isDisplayAvailable(){return false;}
 public long renderCreditDelayMs(){return 0;}
 public String getFirmwareFingerprint(){return "";}
 public TexturePrefetchResult prefetchTextures(int[] kinds,int[] ids,int[] encodings,boolean replace){return new TexturePrefetchResult("unsupported",ids==null?0:ids.length,0,0,0);}
 public TexturePrefetchResult prefetchTextureImages(int[] imageIds,boolean replace){return new TexturePrefetchResult("unsupported",imageIds==null?0:imageIds.length,0,0,0);}
 public void submitExternalSurfaceFrame(ByteBuffer pixels,String id,int width,int height,String fingerprint,ByteBuffer draws,ExternalFrameOutcomeListener outcome){if(outcome!=null)outcome.onOutcome(FrameOutcome.Status.PREVIEW_COMMITTED,"Host test transport");}
 public void submitExternalSurfaceFrame(ByteBuffer pixels,String id,int width,int height,int[] damage,String fingerprint,ByteBuffer draws,ExternalFrameOutcomeListener outcome){if(outcome!=null)outcome.onOutcome(FrameOutcome.Status.PREVIEW_COMMITTED,"Host test transport");}
 public void submitExternalSurfaceFrame(ByteBuffer pixels,String id,int width,int height,int[] damage,String fingerprint,ByteBuffer draws,ExternalFrameOutcomeListener outcome,String traceId){if(outcome!=null)outcome.onOutcome(FrameOutcome.Status.PREVIEW_COMMITTED,"Host test transport");}
 public void submitExternalSurfaceFrame(ByteBuffer pixels,String id,int width,int height,int[] damage,String fingerprint,ByteBuffer draws,int[] retainedCopies,ExternalFrameOutcomeListener outcome,String traceId){if(outcome!=null)outcome.onOutcome(FrameOutcome.Status.PREVIEW_COMMITTED,"Host test transport");}
}
