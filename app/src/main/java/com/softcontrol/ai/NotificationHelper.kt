package com.softcontrol.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object NotificationHelper {

    const val CHANNEL_SERVICE = "sc_service_channel"
    const val CHANNEL_FOCUS   = "sc_focus_channel"
    const val CHANNEL_DAILY   = "sc_daily_channel"

    const val NOTIF_TRACKING  = 1001
    const val NOTIF_VIOLATION = 1002
    const val NOTIF_SESSION   = 1003
    const val NOTIF_DAILY     = 1004

    fun createChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Background tracking — low priority (silent)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                "Background Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).also { it.description = "Keeps SoftControl AI running in background" }
        )

        // Focus alerts — HIGH priority so it shows as heads-up popup
        NotificationChannel(
            CHANNEL_FOCUS,
            "Focus Alerts",
            NotificationManager.IMPORTANCE_HIGH   // ← must be HIGH for popup
        ).also {
            it.description       = "Focus violation and session alerts"
            it.enableVibration(true)
            it.enableLights(true)
            it.setShowBadge(true)
            nm.createNotificationChannel(it)
        }

        // Daily report — default priority
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DAILY,
                "Daily Report",
                NotificationManager.IMPORTANCE_DEFAULT
            ).also { it.description = "Your daily behaviour summary" }
        )
    }

    /**
     * Called on every violation.
     * Shows a heads-up notification with:
     *  - violation number (1st / 2nd / 3rd)
     *  - which app was opened (if auto-detected)
     *  - action advice
     */
    fun sendViolationNotification(
        context: Context,
        violationCount: Int,
        appName: String = ""        // ← pass app name from FocusActivity
    ) {
        val ordinal = when (violationCount) {
            1    -> "1st"
            2    -> "2nd"
            3    -> "3rd"
            else -> "${violationCount}th"
        }

        val title = when (violationCount) {
            1    -> "⚠️ $ordinal Violation — Stay Focused!"
            2    -> "🟠 $ordinal Violation — DANGER ZONE!"
            else -> "🔴 $ordinal Violation — SESSION FAILED!"
        }

        val appLine = if (appName.isNotEmpty()) "Close $appName immediately. " else ""

        val body = when (violationCount) {
            1    -> "${appLine}1 more warning before danger zone."
            2    -> "${appLine}One more violation and your session FAILS."
            else -> "${appLine}You broke focus 3 times. Session has ended."
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_FOCUS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)   // ← MAX for heads-up
            .setDefaults(NotificationCompat.DEFAULT_ALL)    // ← sound + vibration
            .setAutoCancel(true)
            .setContentIntent(buildFocusPendingIntent(context))

        // Use unique ID per violation so each one shows separately
        safeNotify(context, NOTIF_VIOLATION + violationCount, builder)
    }

    fun sendSessionCompleteNotification(context: Context) {
        val builder = NotificationCompat.Builder(context, CHANNEL_FOCUS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("🎉 Focus Session Complete!")
            .setContentText("Great discipline! Tap to view your AI report.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(buildMainPendingIntent(context))

        safeNotify(context, NOTIF_SESSION, builder)
    }

    fun sendDailyReportNotification(context: Context, label: String, score: Int) {
        val builder = NotificationCompat.Builder(context, CHANNEL_DAILY)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("📊 Daily Report Ready")
            .setContentText("Status: ${label.uppercase()}  |  Score: $score/100 — tap to view")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(buildMainPendingIntent(context))

        safeNotify(context, NOTIF_DAILY, builder)
    }

    fun buildServiceNotification(context: Context) =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("SoftControl AI is active")
            .setContentText("Monitoring your digital behaviour...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(buildMainPendingIntent(context))
            .build()

    private fun safeNotify(
        context: Context,
        id: Int,
        builder: NotificationCompat.Builder
    ) {
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted on API 33+ — permission not given yet
        }
    }

    private fun buildMainPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun buildFocusPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 1,
            Intent(context, FocusActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}