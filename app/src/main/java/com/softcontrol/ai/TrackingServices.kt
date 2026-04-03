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
    private var sessionsSinceUpload = 0

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
        val notification = NotificationHelper.buildServiceNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NotificationHelper.NOTIF_TRACKING, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NotificationHelper.NOTIF_TRACKING, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serviceScope.launch {
            // Feature 3: Intervention check runs every 5 min (more frequent)
            launch { interventionLoop() }
            // Full analysis + app usage logging every 15 min
            launch { trackingLoop() }
        }
        return START_STICKY
    }

    // ── Feature 3: Real-Time Intervention Engine ─────────────
    private suspend fun interventionLoop() {
        delay(2 * 60 * 1000L)  // Wait 2 min before first check
        while (true) {
            checkInterventionNeeded()
            delay(5 * 60 * 1000L)   // Check every 5 minutes
        }
    }

    private suspend fun checkInterventionNeeded() {
        val prefs      = getSharedPreferences("softcontrol", Context.MODE_PRIVATE)
        val lastRisk   = prefs.getFloat("last_risk", 0f)
        val violations = prefs.getInt("violations", 0)
        val hour       = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val userId     = UserProfileManager.getUserId(this)
        val streak     = GamificationManager.getStreak(this)

        // Build recent sessions sliding window from SharedPrefs history
        val historyJson = prefs.getString("session_history", "[]") ?: "[]"
        val recentSessions = buildRecentSessionsFromHistory(historyJson)

        try {
            val response = RetrofitClient.instance.checkIntervention(
                InterventionRequest(
                    user_id        = userId,
                    risk_score     = lastRisk,
                    hour_of_day    = hour,
                    violations     = violations,
                    streak         = streak,
                    recent_sessions = recentSessions
                )
            )
            if (response.isSuccessful) {
                response.body()?.let { data ->
                    if (data.intervene && data.message != null) {
                        NotificationHelper.sendInterventionNotification(this, data.message)
                    }
                }
            }
        } catch (_: Exception) {
            // Server unreachable — skip intervention
        }
    }

    // ── Full tracking loop every 15 min ──────────────────────
    private suspend fun trackingLoop() {
        while (true) {
            analyzeAndSave()
            delay(15 * 60 * 1000L)
        }
    }

    private suspend fun analyzeAndSave() {
        val prefs       = getSharedPreferences("softcontrol", Context.MODE_PRIVATE)
        val violations  = prefs.getInt("violations", 0)
        val hour        = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val timeSpent   = UsageStatsHelper.getTodayScreenTimeMinutes(this)
        val appSwitches = UsageStatsHelper.getAppSwitchCount(this)

        prefs.edit()
            .putFloat("time_spent", timeSpent)
            .putInt("app_switches", appSwitches)
            .apply()

        // Feature 1: Collect context
        val ctx    = ContextCollector.getContextSnapshot(this)
        val userId = UserProfileManager.getUserId(this)
        val name   = UserProfileManager.getDisplayName(this)
        val streak = GamificationManager.getStreak(this)

        val request = AnalyzeRequest(
            time_spent          = timeSpent,
            app_switches        = appSwitches,
            hour_of_day         = hour,
            violations          = violations,
            focus_completed     = false,
            user_id             = userId,
            display_name        = name,
            location_type       = ctx.locationType,
            day_type            = ctx.dayType,
            battery_level       = ctx.batteryLevel,
            headphone_connected = ctx.headphoneConnected,
            streak              = streak
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

                    // Daily report at 9 PM
                    if (hour == 21) {
                        NotificationHelper.sendDailyReportNotification(
                            this, data.label, data.self_control_score)
                    }
                }
            }
        } catch (_: Exception) { }

        // Feature 0: Log per-app usage to backend every 15 min
        logAppUsageToBackend(userId)

        // TE-2: Submit real training data every 5 tracking cycles
        sessionsSinceUpload++
        if (sessionsSinceUpload >= 5) {
            submitRecentSessionsAsTrainingData(userId)
            sessionsSinceUpload = 0
        }
    }

    /** Feature 0: Send per-app usage breakdown to backend. */
    private suspend fun logAppUsageToBackend(userId: String) {
        try {
            val appUsageList = UsageStatsHelper.getTopAppsUsage(this, n = 15)
            if (appUsageList.isNotEmpty()) {
                val logEntries = appUsageList.map { appUsage ->
                    AppLogEntry(
                        package_name      = appUsage.packageName,
                        app_name          = appUsage.appName,
                        duration_minutes  = appUsage.durationMinutes,
                        category          = appUsage.category
                    )
                }
                RetrofitClient.instance.logUsage(UsageLogRequest(userId, logEntries))
            }
        } catch (_: Exception) { }
    }

    /** TE-2: Submit real session data to backend for model training. */
    private suspend fun submitRecentSessionsAsTrainingData(userId: String) {
        try {
            val prefs       = getSharedPreferences("softcontrol", Context.MODE_PRIVATE)
            val historyJson = prefs.getString("session_history", "[]") ?: "[]"
            val sessions    = buildRecentSessionsFromHistory(historyJson)
            if (sessions.size >= 3) {
                RetrofitClient.instance.submitTrainingData(TrainingDataRequest(userId, sessions))
            }
        } catch (_: Exception) { }
    }

    /** Build recent sessions list from local SharedPrefs history JSON. */
    private fun buildRecentSessionsFromHistory(historyJson: String): List<Map<String, Any>> {
        return try {
            val arr = org.json.JSONArray(historyJson)
            val result = mutableListOf<Map<String, Any>>()
            val start = maxOf(0, arr.length() - 5)
            for (i in start until arr.length()) {
                val obj = arr.getJSONObject(i)
                val map = mutableMapOf<String, Any>()
                map["time_spent"]    = obj.optInt("time_spent", 30)
                map["violations"]    = obj.optInt("violations", 0)
                map["hour_of_day"]   = obj.optInt("hour", 12)
                map["risk_score"]    = obj.optDouble("risk", 0.3) / 100.0
                map["focus_completed"] = obj.optBoolean("focus_done", false)
                result.add(map)
            }
            result
        } catch (_: Exception) { emptyList() }
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