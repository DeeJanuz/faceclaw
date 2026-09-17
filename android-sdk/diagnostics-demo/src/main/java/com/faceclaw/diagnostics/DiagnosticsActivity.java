package com.faceclaw.diagnostics;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Launcher-visible control and report screen. */
public final class DiagnosticsActivity extends Activity {
  private TextView state, recent;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Runnable refresh = new Runnable() { public void run() { update(); main.postDelayed(this, 1000); } };

  @Override public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState); DiagnosticStore.init(this);
    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
    startMonitor(null);
    ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(Color.rgb(18, 20, 24));
    LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(20), dp(12), dp(20), dp(28)); scroll.addView(content); setContentView(scroll);
    heading(content, "Faceclaw Diagnostics");
    text(content, "Keeps a bounded local record of slow frames, transport failures, SDK disconnects, crashes, ANRs, and notification handoffs. Logs exclude pixels, notification text, prompts, and audio.", 15, Color.LTGRAY);
    state = text(content, "", 16, Color.WHITE);
    row(content, button("Mark issue now", () -> startMonitor(DiagnosticsService.ACTION_MARK)), button("Open on glasses", () -> toast(DiagnosticsService.openGlassesWindow() ? "Open requested" : "Waiting for Faceclaw SDK connection")));
    row(content, button("Export report", this::export), button("Clear history", () -> { DiagnosticStore.clear(); update(); }));
    row(content, button("Start monitor", () -> startMonitor(null)), button("Stop monitor", () -> startMonitor(DiagnosticsService.ACTION_STOP)));
    heading(content, "Recent incidents"); recent = text(content, "", 12, Color.rgb(190, 205, 220)); recent.setTypeface(android.graphics.Typeface.MONOSPACE);
  }

  @Override protected void onResume() { super.onResume(); main.post(refresh); }
  @Override protected void onPause() { main.removeCallbacks(refresh); super.onPause(); }

  private void update() {
    if (state == null) return;
    state.setText((DiagnosticsService.monitoring ? "MONITORING" : "STOPPED") + "  ·  SDK " + (DiagnosticsService.sdkConnected ? "CONNECTED" : "WAITING") + "\n" + DiagnosticStore.summary() + "\nTransport: " + DiagnosticsService.transport);
    recent.setText(DiagnosticStore.recentText());
  }

  private void startMonitor(String action) {
    Intent intent = new Intent(this, DiagnosticsService.class); if (action != null) intent.setAction(action);
    startForegroundService(intent);
  }

  private void export() {
    try {
      File report = DiagnosticStore.createReport();
      if (Build.VERSION.SDK_INT >= 29) {
        ContentValues values = new ContentValues(); values.put(MediaStore.Downloads.DISPLAY_NAME, report.getName()); values.put(MediaStore.Downloads.MIME_TYPE, "text/plain"); values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/Faceclaw Diagnostics");
        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values); if (uri == null) throw new IOException("Downloads unavailable");
        try (InputStream in = new FileInputStream(report); OutputStream out = getContentResolver().openOutputStream(uri)) { byte[] data = new byte[8192]; int count; while ((count = in.read(data)) >= 0) out.write(data, 0, count); }
        toast("Saved to Downloads/Faceclaw Diagnostics");
      } else toast("Report saved: " + report.getAbsolutePath());
      DiagnosticStore.event("export", "report exported");
    } catch (Throwable error) { toast("Export failed: " + error.getClass().getSimpleName()); DiagnosticStore.event("export", "failed: " + error.getClass().getSimpleName()); }
  }

  private Button button(String label, Runnable action) { Button value = new Button(this); value.setText(label); value.setAllCaps(false); value.setOnClickListener(v -> action.run()); return value; }
  private void row(LinearLayout parent, View first, View second) { LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.addView(first, new LinearLayout.LayoutParams(0, dp(52), 1)); row.addView(second, new LinearLayout.LayoutParams(0, dp(52), 1)); parent.addView(row, new LinearLayout.LayoutParams(-1, -2)); }
  private void heading(LinearLayout parent, String value) { TextView text = text(parent, value, 24, Color.WHITE); text.setTypeface(null, android.graphics.Typeface.BOLD); text.setPadding(0, dp(18), 0, dp(5)); }
  private TextView text(LinearLayout parent, String value, int size, int color) { TextView text = new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(color); text.setPadding(0, dp(6), 0, dp(10)); parent.addView(text, new LinearLayout.LayoutParams(-1, -2)); return text; }
  private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
  private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
}
