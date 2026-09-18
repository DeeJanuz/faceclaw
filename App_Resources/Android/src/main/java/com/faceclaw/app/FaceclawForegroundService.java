package com.faceclaw.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.tns.NativeScriptActivity;

public class FaceclawForegroundService extends Service {
    public static final String ACTION_START = "com.faceclaw.app.action.START";
    public static final String ACTION_UPDATE = "com.faceclaw.app.action.UPDATE";
    public static final String ACTION_STOP = "com.faceclaw.app.action.STOP";
    public static final String EXTRA_TEXT = "text";

    private static final String CHANNEL_ID = "faceclaw-dashboard";
    private static final int NOTIFICATION_ID = 4201;
    private String currentText;
    private boolean foregroundStarted;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        String text = intent != null ? intent.getStringExtra(EXTRA_TEXT) : null;

        if (ACTION_STOP.equals(action)) {
            try {
                FaceclawSettings.getInstance(this).setBooleanSync("g2.backgroundRestoreIntent", false);
            } catch (RuntimeException ignored) {
                // The JavaScript controller normally clears this first. Keep
                // the service stop path safe if it is invoked independently.
            }
            stopForeground(STOP_FOREGROUND_REMOVE);
            currentText = null;
            foregroundStarted = false;
            stopSelf();
            return START_NOT_STICKY;
        }

        String nextText = text != null && !text.trim().isEmpty() ? text : "Keeping the dashboard connected";
        if (ACTION_UPDATE.equals(action) && foregroundStarted && nextText.equals(currentText)) {
            return START_STICKY;
        }
        ensureNotificationChannel();
        Notification notification = buildNotification(nextText);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, foregroundServiceType());
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (SecurityException typedStartFailure) {
            // A vendor policy or a permission transition can reject the typed
            // overload even though the process is otherwise healthy. Retry
            // the manifest-declared connected-device service once; if Android
            // still rejects it, stop cleanly instead of crashing the app while
            // the sticky restore path is running in the background.
            Log.e("FaceclawFgService", "typed foreground start rejected", typedStartFailure);
            try {
                startForeground(NOTIFICATION_ID, notification);
            } catch (RuntimeException fallbackFailure) {
                Log.e("FaceclawFgService", "connected-device foreground start rejected", fallbackFailure);
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        foregroundStarted = true;
        currentText = nextText;

        return START_STICKY;
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Faceclaw dashboard",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Keeps the Faceclaw dashboard connected to the glasses.");

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent launchIntent = new Intent(this, NativeScriptActivity.class);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, launchIntent, flags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return builder
                .setContentTitle("faceclaw dashboard")
                .setContentText(text)
                .setSmallIcon(getApplicationInfo().icon)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private int foregroundServiceType() {
        // This service owns the glasses transport. Android 14/16 validates
        // every claimed type at startForeground(), so opportunistically adding
        // microphone or location here can crash the whole process when those
        // permissions are absent or while-in-use. Audio and navigation should
        // claim their own typed service only when they actually run.
        return ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
    }
}
