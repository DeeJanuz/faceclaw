package com.faceclaw.sdk;
import android.graphics.Canvas;
@FunctionalInterface public interface CanvasRenderer { void render(Canvas canvas,RenderRequest request) throws Exception; }
