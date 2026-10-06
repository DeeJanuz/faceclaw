package com.faceclaw.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

import com.tns.NativeScriptActivity

class FaceclawForegroundService : Service() {
    companion object {
        const val ACTION_START = "com.faceclaw.app.action.START"
        const val ACTION_UPDATE = "com.faceclaw.app.action.UPDATE"
        const val ACTION_STOP = "com.faceclaw.app.action.STOP"
        const val EXTRA_TEXT = "text"

        // Channel id was bumped from "faceclaw-dashboard" when the badge setting
        // changed: Android freezes a channel's showBadge flag at creation, so the
        // old channel (which let Samsung's launcher count the pinned notification
        // as a red "1" badge) is deleted on upgrade rather than reused.
        private const val LEGACY_CHANNEL_ID = "faceclaw-dashboard"
        private const val CHANNEL_ID = "faceclaw-connection"
        private const val NOTIFICATION_ID = 4201
    }

    private var currentText: String? = null
    private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = if (intent != null) intent.action else ACTION_START
        val text = intent?.getStringExtra(EXTRA_TEXT)

        if (ACTION_STOP == action) {
            try {
                FaceclawSettings.getInstance(this).setBooleanSync("g2.backgroundRestoreIntent", false)
            } catch (ignored: RuntimeException) {
                // The JavaScript controller normally clears this first. Keep
                // the service stop path safe if it is invoked independently.
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            currentText = null
            foregroundStarted = false
            stopSelf()
            return START_NOT_STICKY
        }

        val nextText = if (text != null && !text.trim().isEmpty()) text else "Keeping the dashboard connected"
        if (ACTION_UPDATE == action && foregroundStarted && nextText == currentText) {
            return START_STICKY
        }
        ensureNotificationChannel()
        val notification = buildNotification(nextText)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, foregroundServiceType())
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (typedStartFailure: SecurityException) {
            // A vendor policy or a permission transition can reject the typed
            // overload even though the process is otherwise healthy. Retry
            // the manifest-declared connected-device service once; if Android
            // still rejects it, stop cleanly instead of crashing the app while
            // the sticky restore path is running in the background.
            Log.e("FaceclawFgService", "typed foreground start rejected", typedStartFailure)
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (fallbackFailure: RuntimeException) {
                Log.e("FaceclawFgService", "connected-device foreground start rejected", fallbackFailure)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        foregroundStarted = true
        currentText = nextText

        return START_STICKY
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Glasses connection",
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = "Keeps Faceclaw connected to the glasses."
        // The pinned status notification must not count toward the launcher
        // icon's notification badge.
        channel.setShowBadge(false)

        val manager = getSystemService(NotificationManager::class.java)
        if (manager != null) {
            manager.createNotificationChannel(channel)
            manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        }
    }

    private fun buildNotification(text: String): Notification {
        val launchIntent = Intent(this, NativeScriptActivity::class.java)
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }

        val contentIntent = PendingIntent.getActivity(this, 0, launchIntent, flags)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else
            Notification.Builder(this)

        return builder
            .setContentTitle("Faceclaw")
            .setContentText(text)
            .setSmallIcon(applicationInfo.icon)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun foregroundServiceType(): Int {
        // This service owns the glasses transport. Android 14/16 validates
        // every claimed type at startForeground(), so opportunistically adding
        // microphone or location here can crash the whole process when those
        // permissions are absent or while-in-use. Audio and navigation should
        // claim their own typed service only when they actually run.
        return ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    }
}
