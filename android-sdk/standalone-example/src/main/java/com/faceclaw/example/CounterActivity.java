package com.faceclaw.example;
import android.app.Activity;
import android.os.Bundle;
import com.faceclaw.sdk.LocalPresentation;
public final class CounterActivity extends Activity {
 @Override public void onCreate(Bundle state){super.onCreate(state);setContentView(new LocalPresentation(this,new CounterPresentation(this)));}
}
