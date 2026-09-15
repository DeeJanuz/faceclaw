package com.faceclaw.example;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import com.faceclaw.sdk.AppPresentation;
final class CounterPresentation implements AppPresentation {
 private final SharedPreferences state;
 CounterPresentation(Context context){state=context.getSharedPreferences("counter",Context.MODE_PRIVATE);}
 public void paint(Canvas canvas,int width,int height){canvas.drawColor(Color.BLACK);Paint text=new Paint(Paint.ANTI_ALIAS_FLAG);text.setColor(Color.WHITE);text.setTextSize(28);canvas.drawText("Count: "+state.getInt("count",0),24,Math.max(40,height/2),text);text.setTextSize(18);canvas.drawText("Tap to add one",24,Math.max(70,height/2+36),text);}
 public void input(String type,String source){if(type.equals("click"))state.edit().putInt("count",state.getInt("count",0)+1).apply();}
}
