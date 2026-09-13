package com.faceclaw.app;

import com.faceclaw.sdk.FrameOutcome;

/** Completion hook kept entirely on the Java side of the host rendering path. */
public interface ExternalFrameOutcomeListener { void onOutcome(FrameOutcome.Status status,String diagnostic); }
