package com.faceclaw.diagnostics;

import android.app.*;
import android.content.Intent;
import android.graphics.*;
import android.os.*;
import android.util.Log;
import com.faceclaw.sdk.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.regex.*;

/** Persistent, content-free Faceclaw transport and frame monitor. */
public final class DiagnosticsService extends FaceclawAppService {
  static final String ACTION_MARK = "com.faceclaw.diagnostics.MARK";
  static final String ACTION_STOP = "com.faceclaw.diagnostics.STOP";
  private static final String CHANNEL = "faceclaw-diagnostics";
  private static final Pattern FINISHED = Pattern.compile("-> (.+) in (\\d+)ms");
  private static final Pattern TIMED_OUT = Pattern.compile("timed out after (\\d+)ms");
  private static volatile DiagnosticsService active;
  static volatile boolean monitoring, sdkConnected;
  static volatile String transport = "Waiting for Faceclaw transport evidence";
  private final ExecutorService work = Executors.newFixedThreadPool(2);
  private volatile boolean stopping;
  private volatile java.lang.Process logcat;
  private FaceclawSession current;
  private RenderSurface surface;
  private Bitmap bitmap;
  private int[] argb;

  @Override public void onCreate() {
    super.onCreate(); active = this; DiagnosticStore.init(this); startForegroundMonitor(); startLogReader();
    DiagnosticStore.event("monitor", "foreground monitor started");
  }

  @Override public int onStartCommand(Intent intent, int flags, int startId) {
    startForegroundMonitor();
    String action = intent == null ? "" : String.valueOf(intent.getAction());
    if (ACTION_MARK.equals(action)) markIssue("manual marker from phone");
    if (ACTION_STOP.equals(action)) { stopping = true; monitoring = false; if (logcat != null) logcat.destroy(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
    return START_STICKY;
  }

  @Override public void onDestroy() {
    stopping = true; monitoring = false; sdkConnected = false;
    if (logcat != null) logcat.destroy(); work.shutdownNow(); if (bitmap != null) bitmap.recycle();
    DiagnosticStore.event("monitor", "service destroyed"); if (active == this) active = null; super.onDestroy();
  }

  @Override protected void onSessionReady(FaceclawSession session) {
    current = session; surface = session.windowSurface(); surface.setRasterRenderer(work, this::render);
    sdkConnected = true; DiagnosticStore.event("sdk", "host session ready");
  }

  @Override protected void onSessionLost(DisconnectInfo info) {
    sdkConnected = false; DiagnosticStore.disconnect("SDK session lost reason=" + info.reason + " recoverable=" + info.recoverable);
  }

  @Override protected void onHostSnapshot(HostSnapshot value) {
    DiagnosticStore.event("host", "snapshot revision=" + value.stateRevision + " screenOn=" + value.screenOn + " windowOpen=" + value.windowOpen + " visible=" + value.windowVisible + " generation=" + value.windowGeneration + " size=" + value.windowWidth + "x" + value.windowHeight);
    if (surface != null && value.windowOpen) surface.invalidate(InvalidateReason.STATE);
  }

  @Override protected void onControlEvent(ControlEvent event) {
    if (event.type.equals("open") || event.type.equals("resize") || event.type.equals("visibility") || event.type.equals("close") || event.type.equals("disconnected") || event.type.equals("destroyed"))
      DiagnosticStore.event("control", event.type);
    if (surface != null && (event.type.equals("open") || event.type.equals("resize") || event.type.equals("visibility"))) surface.invalidate(InvalidateReason.STATE);
  }

  @Override protected void onFrameOutcome(FrameOutcome outcome) {
    if (outcome.status != FrameOutcome.Status.DISPLAY_ACKED && outcome.status != FrameOutcome.Status.PREVIEW_COMMITTED && outcome.status != FrameOutcome.Status.DEDUPLICATED)
      DiagnosticStore.event("sdk-frame", outcome.status + " surface=" + outcome.surfaceId + " metadataDropped=" + outcome.metadataDropped + " diagnostic=" + outcome.diagnostic);
  }

  @Override protected void onSdkDiagnostic(SdkDiagnostic value) {
    DiagnosticStore.event("sdk-diagnostic", value.category + " operation=" + value.operation + " surface=" + value.surfaceId + " generation=" + value.generation + " recoverable=" + value.recoverable);
  }

  @Override protected void onInput(RenderSurface target, FaceclawInputEvent event) {
    if (event.type.equals("click") || event.type.equals("pointer-click")) markIssue("manual marker from glasses");
    target.invalidate(InvalidateReason.INPUT);
  }

  static void markIssue(String source) {
    DiagnosticsService service = active;
    DiagnosticStore.event("ISSUE-MARKER", source);
    if (service != null) service.work.execute(service::captureSnapshot);
  }

  static boolean openGlassesWindow() {
    DiagnosticsService service = active; return service != null && service.requestOpenWindow();
  }

  private void startForegroundMonitor() {
    NotificationManager manager = getSystemService(NotificationManager.class);
    manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Faceclaw diagnostics", NotificationManager.IMPORTANCE_LOW));
    PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, DiagnosticsActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    Notification notification = new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
      .setContentTitle("Faceclaw diagnostics active").setContentText("Recording transport and slow-frame incidents").setOngoing(true).setContentIntent(open).build();
    startForeground(41, notification); monitoring = true;
  }

  private void startLogReader() {
    work.execute(() -> {
      while (!stopping) {
        try {
          logcat = new ProcessBuilder("logcat", "-v", "threadtime", "-T", "1").redirectErrorStream(true).start();
          DiagnosticStore.event("monitor", "logcat reader attached");
          try (BufferedReader reader = new BufferedReader(new InputStreamReader(logcat.getInputStream()))) {
            String line; while (!stopping && (line = reader.readLine()) != null) acceptLog(line);
          }
          int exit = logcat.waitFor(); if (!stopping) DiagnosticStore.event("monitor", "logcat reader exited=" + exit + "; restarting");
        } catch (Throwable error) {
          if (!stopping) DiagnosticStore.event("monitor", "logcat unavailable: " + error.getClass().getSimpleName());
        }
        if (!stopping) SystemClock.sleep(2000);
      }
    });
  }

  private void acceptLog(String line) {
    if (line.contains(" FrameTimings:")) {
      Matcher completed = FINISHED.matcher(line), timedOut = TIMED_OUT.matcher(line);
      if (completed.find()) {
        String outcome = completed.group(1); long duration = Long.parseLong(completed.group(2));
        DiagnosticStore.frame(duration, outcome);
        if (outcome.startsWith("sent")) transport = "Frames reaching glasses; latest " + duration + "ms";
      }
      else if (timedOut.find()) DiagnosticStore.frame(Long.parseLong(timedOut.group(1)), "timeout");
      return;
    }
    if (line.contains(" FaceclawComm:")) {
      String lower = line.toLowerCase(Locale.US);
      if (lower.contains("transport failure") || lower.contains("not paired") || lower.contains("disconnected") || lower.contains("connect failed")) {
        transport = compact(line); DiagnosticStore.disconnect(transport);
      } else if (lower.contains("connected") || lower.contains("ready") || lower.contains("retrying") || lower.contains("message timed out") || lower.contains("late ack") || lower.contains("state=")) {
        transport = compact(line); DiagnosticStore.event("transport", transport);
      }
      return;
    }
    if ((line.contains(" AndroidRuntime:") && (line.contains("FATAL EXCEPTION") || line.contains("com.faceclaw"))) ||
        (line.contains(" ActivityManager:") && (line.contains("ANR in com.faceclaw") || line.matches(".*Process com\\.faceclaw\\.[^ ]+ .*died.*"))) ||
        line.contains("[T3Notification]") || line.contains("[NotificationHandoff]"))
      DiagnosticStore.event("system", compact(line));
  }

  private static String compact(String line) {
    int marker = line.indexOf(": "); return DiagnosticStore.redact(marker >= 0 ? line.substring(marker + 2) : line);
  }

  private void captureSnapshot() {
    snapshotCommand("meminfo", new String[]{"dumpsys", "meminfo", "com.faceclaw.app"}, 80);
    snapshotCommand("bluetooth", new String[]{"dumpsys", "bluetooth_manager"}, 80);
    DiagnosticStore.event("snapshot", "screenInteractive=" + getSystemService(android.os.PowerManager.class).isInteractive()
      + " battery=" + batteryPercent() + "% diagnosticsWindowConnected=" + sdkConnected + " monitoring=" + monitoring);
  }

  private void snapshotCommand(String category, String[] command, int limit) {
    try {
      java.lang.Process process = new ProcessBuilder(command).redirectErrorStream(true).start(); int lines = 0;
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
        String line; while ((line = reader.readLine()) != null && lines < limit) {
          String lower = line.toLowerCase(Locale.US);
          if (category.equals("meminfo") ? (lower.contains("total pss") || lower.contains("java heap") || lower.contains("native heap") || lower.contains("total rss") || lower.contains("objects"))
            : (lower.contains("state:") || lower.contains("connection") || lower.contains("gatt") || lower.contains("adapter"))) { DiagnosticStore.detail("snapshot-" + category, line.trim()); lines++; }
        }
      }
      process.destroy();
    } catch (Throwable error) { DiagnosticStore.event("snapshot", category + " unavailable: " + error.getClass().getSimpleName()); }
  }

  private int batteryPercent() {
    Intent state = registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    if (state == null) return -1; int level = state.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = state.getIntExtra(BatteryManager.EXTRA_SCALE, 100); return scale <= 0 ? -1 : level * 100 / scale;
  }

  private void render(FrameLease frame, RenderRequest request) {
    int width = frame.width(), height = frame.height();
    if (bitmap == null || bitmap.getWidth() != width || bitmap.getHeight() != height) { if (bitmap != null) bitmap.recycle(); bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); argb = new int[width * height]; }
    Canvas canvas = new Canvas(bitmap); canvas.drawColor(Color.BLACK); Paint text = new Paint(Paint.ANTI_ALIAS_FLAG); text.setColor(Color.WHITE); text.setTypeface(Typeface.MONOSPACE); text.setTextSize(17);
    canvas.drawText("FACECLAW DIAGNOSTICS", 14, 25, text); text.setTextSize(13); int y = 52;
    String body = (monitoring ? "MONITOR ACTIVE" : "MONITOR STOPPED") + " / TEST WINDOW " + (sdkConnected ? "READY" : "WAITING") + "\n" + DiagnosticStore.summary() + "\n" + transport + "\n\nTap: mark issue and capture snapshot";
    for (String raw : body.split("\\n")) { for (String wrapped : wrap(raw, 68)) { if (y > height - 12) break; canvas.drawText(wrapped, 14, y, text); y += 19; } }
    bitmap.getPixels(argb, 0, width, 0, 0, width, height); ByteBuffer gray = frame.gray8();
    for (int value : argb) { int alpha = value >>> 24, luminance = (((value >> 16) & 255) * 54 + ((value >> 8) & 255) * 183 + (value & 255) * 19) >> 8; gray.put((byte)(luminance * alpha / 255)); }
    FaceclawSession session = current; frame.submit(FrameMetadata.builder(session.nextClientFrameId(), session.nextContentVersion()).fullDamage(width, height).traceId(request.credit.traceId).build());
  }

  private static java.util.List<String> wrap(String value, int max) {
    java.util.ArrayList<String> out = new java.util.ArrayList<>(); String rest = value;
    while (rest.length() > max) { int cut = rest.lastIndexOf(' ', max); if (cut < 1) cut = max; out.add(rest.substring(0, cut)); rest = rest.substring(cut).trim(); }
    out.add(rest); return out;
  }
}
