package com.faceclaw.sdk;

import android.graphics.Canvas;

/** App-owned presentation logic. Local preview cannot construct a Binder session or grant host authority. */
public interface AppPresentation {
 void paint(Canvas canvas,int width,int height);
 void input(String type,String source);
 default void visible(boolean visible){}
}
