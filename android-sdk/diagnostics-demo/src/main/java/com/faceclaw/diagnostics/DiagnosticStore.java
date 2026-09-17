package com.faceclaw.diagnostics;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Content-free, size-bounded incident history. */
final class DiagnosticStore {
  private static final long MAX_FILE = 1_500_000;
  private static final int FILES = 4, RECENT = 80;
  private static final Pattern MAC = Pattern.compile("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}");
  private static final Pattern URL = Pattern.compile("(?i)(?:wss?|https?)://[^\\s]+", Pattern.CASE_INSENSITIVE);
  private static final ArrayDeque<String> recent = new ArrayDeque<>();
  private static Context app;
  private static File dir, current;
  private static SharedPreferences prefs;

  static synchronized void init(Context context) {
    if (app != null) return;
    app = context.getApplicationContext();
    File root = app.getExternalFilesDir(null);
    dir = new File(root == null ? app.getFilesDir() : root, "diagnostics");
    dir.mkdirs(); current = new File(dir, "faceclaw-diagnostics.log");
    prefs = app.getSharedPreferences("diagnostic-stats", Context.MODE_PRIVATE);
    event("monitor", "store initialized; readLogs=" + permission("android.permission.READ_LOGS") + " dump=" + permission("android.permission.DUMP"));
  }

  static synchronized void event(String category, String message) {
    write(category, message, true);
  }

  static synchronized void detail(String category, String message) {
    write(category, message, false);
  }

  private static void write(String category, String message, boolean showRecent) {
    if (app == null) return;
    String clean = redact(message == null ? "" : message.replace('\n', ' ').replace('\r', ' '));
    if (clean.length() > 700) clean = clean.substring(0, 700) + "…";
    String line = stamp() + "  " + category + "  " + clean;
    if (showRecent) { recent.addFirst(line); while (recent.size() > RECENT) recent.removeLast(); }
    try { rotate(); try (Writer out = new OutputStreamWriter(new FileOutputStream(current, true), StandardCharsets.UTF_8)) { out.write(line); out.write('\n'); } }
    catch (IOException ignored) {}
  }

  static synchronized void frame(long durationMs, String outcome) {
    long count = prefs.getLong("frames", 0) + 1;
    long slow = prefs.getLong("slow", 0), severe = prefs.getLong("severe", 0), timeouts = prefs.getLong("timeouts", 0);
    if (durationMs >= 150) slow++;
    if (durationMs >= 500) severe++;
    if (outcome.contains("timeout")) timeouts++;
    prefs.edit().putLong("frames", count).putLong("slow", slow).putLong("severe", severe).putLong("timeouts", timeouts)
      .putLong("max", Math.max(durationMs, prefs.getLong("max", 0))).apply();
    if (durationMs >= 500) event("frame-severe", durationMs + "ms " + outcome);
    else if (durationMs >= 150) event("frame-slow", durationMs + "ms " + outcome);
    else if (outcome.contains("timeout")) event("frame-timeout", durationMs + "ms " + outcome);
    else if (!outcome.startsWith("sent") && !outcome.contains("no change from displayed image") && !outcome.contains("handled by shell"))
      event("frame-outcome", durationMs + "ms " + outcome);
  }

  static synchronized void disconnect(String detail) {
    prefs.edit().putLong("disconnects", prefs.getLong("disconnects", 0) + 1).apply();
    event("disconnect", detail);
  }

  static synchronized String summary() {
    if (prefs == null) return "Starting…";
    return "Frames " + prefs.getLong("frames", 0) + "  ≥150ms " + prefs.getLong("slow", 0)
      + "  ≥500ms " + prefs.getLong("severe", 0) + "  timeout " + prefs.getLong("timeouts", 0)
      + "\nDisconnects " + prefs.getLong("disconnects", 0) + "  max " + prefs.getLong("max", 0) + "ms"
      + "\nREAD_LOGS " + permission("android.permission.READ_LOGS") + "  DUMP " + permission("android.permission.DUMP");
  }

  static synchronized String recentText() {
    StringBuilder out = new StringBuilder(); for (String line : recent) out.append(line).append('\n'); return out.toString();
  }

  static synchronized File createReport() throws IOException {
    File report = new File(dir, "faceclaw-report-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt");
    try (Writer out = new OutputStreamWriter(new FileOutputStream(report), StandardCharsets.UTF_8)) {
      out.write("Faceclaw Diagnostics\n"); out.write("Created " + stamp() + "\n");
      out.write("Device " + Build.MANUFACTURER + " " + Build.MODEL + " Android " + Build.VERSION.RELEASE + " (" + Build.VERSION.SDK_INT + ")\n");
      out.write(summary() + "\n\n");
      for (int index = FILES - 1; index >= 1; index--) append(out, new File(dir, "faceclaw-diagnostics." + index + ".log"));
      append(out, current);
    }
    return report;
  }

  static synchronized void clear() {
    for (int i = 1; i < FILES; i++) new File(dir, "faceclaw-diagnostics." + i + ".log").delete();
    if (current != null) current.delete(); recent.clear(); prefs.edit().clear().apply(); event("monitor", "history cleared");
  }

  static String stamp() { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()) + " +" + (SystemClock.elapsedRealtime() / 1000) + "s"; }
  static String redact(String value) { return URL.matcher(MAC.matcher(value).replaceAll("XX:XX:XX:XX:XX:XX")).replaceAll("<url>"); }
  private static boolean permission(String name) { return app != null && app.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED; }
  private static void append(Writer out, File file) throws IOException { if (!file.exists()) return; try (Reader in = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) { char[] data = new char[8192]; int read; while ((read = in.read(data)) >= 0) out.write(data, 0, read); } }
  private static void rotate() {
    if (!current.exists() || current.length() < MAX_FILE) return;
    new File(dir, "faceclaw-diagnostics." + (FILES - 1) + ".log").delete();
    for (int i = FILES - 2; i >= 1; i--) new File(dir, "faceclaw-diagnostics." + i + ".log").renameTo(new File(dir, "faceclaw-diagnostics." + (i + 1) + ".log"));
    current.renameTo(new File(dir, "faceclaw-diagnostics.1.log"));
  }
  private DiagnosticStore() {}
}
