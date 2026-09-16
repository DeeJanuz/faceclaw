package com.faceclaw.textdensity;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

public final class DensityActivity extends Activity {
 @Override public void onCreate(Bundle state){
  super.onCreate(state);
  LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(36,36,36,36);root.setBackgroundColor(Color.rgb(245,245,245));
  TextView title=text("Text Density Lab",28);root.addView(title);
  TextView instructions=text("Open this app from Faceclaw. Click on the glasses to swipe to the next, denser text page. Double-click or scroll up moves back. Long-press toggles retained-pixel copy. Use the switch below for the same A/B test.",17);LinearLayout.LayoutParams copy=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);copy.setMargins(0,20,0,24);root.addView(instructions,copy);
  Switch optimization=new Switch(this);optimization.setText("Retained copy + repair");optimization.setTextSize(18);optimization.setChecked(getPreferences().getBoolean("copy",true));optimization.setOnCheckedChangeListener((button,value)->{getPreferences().edit().putBoolean("copy",value).apply();DensityService.setCopyEnabled(value);});root.addView(optimization);
  Button open=button("Open test on glasses");open.setOnClickListener(v->DensityService.openWindow());root.addView(open);
  Button next=button("Next density");next.setOnClickListener(v->DensityService.step(1));root.addView(next);
  Button previous=button("Previous density");previous.setOnClickListener(v->DensityService.step(-1));root.addView(previous);
  Button reset=button("Reset to density 1");reset.setOnClickListener(v->DensityService.reset());root.addView(reset);
  setContentView(root);
 }
 private android.content.SharedPreferences getPreferences(){return getSharedPreferences("density-lab",MODE_PRIVATE);}
 private TextView text(String value,int size){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(Color.rgb(25,25,25));return view;}
 private Button button(String value){Button button=new Button(this);button.setText(value);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);params.setMargins(0,18,0,0);button.setLayoutParams(params);return button;}
}
