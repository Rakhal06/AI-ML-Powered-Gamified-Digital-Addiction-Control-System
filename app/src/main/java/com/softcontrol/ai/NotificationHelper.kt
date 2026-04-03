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

    const val CHANNEL_SERVICE      = "sc_service_channel"
    const val CHANNEL_FOCUS        = "sc_focus_channel"
    const val CHANNEL_DAILY        = "sc_daily_channel"
    const val CHANNEL_INTERVENTION = "sc_intervention_channel"   // Feature 3: Real-time intervention

    const val NOTIF_TRACKING       = 1001
    const val NOTIF_VIOLATION      = 1002
    const val NOTIF_SESSION        = 1003
    const val NOTIF_DAILY          = 1004
    const val NOTIF_INTERVENTION   = 1005
    const val NOTIF_MISSION        = 1006

    fun createChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Background Tracking",
                NotificationManager.IMPORTANCE_LOW)
                .also { it.description = "Keeps SoftControl AI running in background" })

        NotificationChannel(CHANNEL_FOCUS, "Focus Alerts",
            NotificationManager.IMPORTANCE_HIGH)
            .also {
                it.description     = "Focus violation and session alerts"
                it.enableVibration(true)
                it.enableLights(true)
                it.setShowBadge(true)
                nm.createNotificationChannel(it)
            }

        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DAILY, "Daily Report",
                NotificationManager.IMPORTANCE_DEFAULT)
                .also { it.description = "Your daily behaviour summary" })

        // Feature 3: Real-Time Intervention Engine
        NotificationChannel(CHANNEL_INTERVENTION, "Intervention Alerts",
            NotificationManager.IMPORTANCE_HIGH)
            .also {
                it.description     = "Proactive distraction prevention alerts"
                it.enableVibration(true)
                it.setShowBadge(true)
                nm.createNotificationChannel(it)
            }
    }

    fun sendViolationNotification(context: Context, violationCount: Int, appName: String = "") {
        val ordinal = when (violationCount) { 1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; else -> "${violationCount}th" }
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
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(buildFocusPendingIntent(context))
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

    /** Feature 3: Real-Time Intervention Engine — proactive distraction prevention. */
    fun sendInterventionNotification(context: Context, message: String) {
        val builder = NotificationCompat.Builder(context, CHANNEL_INTERVENTION)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("🧠 SoftControl AI Alert")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            .setAutoCancel(true)
            .setContentIntent(buildMainPendingIntent(context))
        safeNotify(context, NOTIF_INTERVENTION, builder)
    }

    /** Feature 4: Mission completed notification. */
    fun sendMissionCompleteNotification(context: Context, missionTitle: String, xpReward: Int) {
        val body = "Mission complete: \"$missionTitle\" — You earned +$xpReward XP!"
        val builder = NotificationCompat.Builder(context, CHANNEL_INTERVENTION)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("🏆 Mission Complete!")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(buildMainPendingIntent(context))
        safeNotify(context, NOTIF_MISSION, builder)
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

    private fun safeNotify(context: Context, id: Int, builder: NotificationCompat.Builder) {
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (_: SecurityException) { }
    }

    private fun buildMainPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun buildFocusPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(context, 1,
            Intent(context, FocusActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}