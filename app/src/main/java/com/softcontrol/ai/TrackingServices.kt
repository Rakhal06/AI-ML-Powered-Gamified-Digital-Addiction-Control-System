package com.softcontrol.ai

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.*
import java.util.Calendar

class TrackingService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
        val notification = NotificationHelper.buildServiceNotification(this)

        // Android 14+ requires explicit foreground service type
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationHelper.NOTIF_TRACKING,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NotificationHelper.NOTIF_TRACKING, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serviceScope.launch { trackingLoop() }
        return START_STICKY   // OS restarts the service if it gets killed
    }

    private suspend fun trackingLoop() {
        while (true) {
            analyzeAndSave()
            delay(15 * 60 * 1000L)   // run every 15 minutes
        }
    }

    private suspend fun analyzeAndSave() {
        val prefs       = getSharedPreferences("softcontrol", Context.MODE_PRIVATE)
        val violations  = prefs.getInt("violations", 0)
        val hour        = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val timeSpent   = UsageStatsHelper.getTodayScreenTimeMinutes(this)
        val appSwitches = UsageStatsHelper.getAppSwitchCount(this)

        // Persist latest real usage so other screens can read it
        prefs.edit()
            .putFloat("time_spent", timeSpent)
            .putInt("app_switches", appSwitches)
            .apply()

        val request = AnalyzeRequest(
            time_spent      = timeSpent,
            app_switches    = appSwitches,
            hour_of_day     = hour,
            violations      = violations,
            focus_completed = false
        )

        try {
            val response = RetrofitClient.instance.analyze(request)
            if (response.isSuccessful) {
                response.body()?.let { data ->
                    prefs.edit()
                        .putString("last_label",   data.label)
                        .putInt("last_score",       data.self_control_score)
                        .putFloat("last_risk",      data.risk_score)
                        .putString("last_insight",  data.insight)
                        .putString("last_tip",      data.coach_tip)
                        .putString("last_cluster",  data.cluster)
                        .putFloat("weekly_hours",   data.weekly_screen_time_hours)
                        .putInt("monster_level",    data.monster_level)
                        .apply()

                    // Send daily summary notification at 9 PM
                    if (hour == 21) {
                        NotificationHelper.sendDailyReportNotification(
                            this, data.label, data.self_control_score
                        )
                    }
                }
            }
        } catch (_: Exception) {
            // Server unreachable — keep last saved values, continue silently
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            context.startForegroundService(Intent(context, TrackingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackingService::class.java))
        }
    }
}