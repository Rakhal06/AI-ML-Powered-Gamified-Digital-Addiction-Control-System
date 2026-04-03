package com.softcontrol.ai

import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.Calendar

object UsageStatsHelper {

    // ── Feature 0: App categories for continuous tracking ─────
    private val SOCIAL_APPS = setOf(
        "com.instagram.android", "com.zhiliaoapp.musically", "com.snapchat.android",
        "com.facebook.katana", "com.twitter.android", "com.reddit.frontpage",
        "com.pinterest", "com.linkedin.android", "com.tumblr", "com.bereal.android"
    )
    private val ENTERTAINMENT_APPS = setOf(
        "com.google.android.youtube", "com.netflix.mediaclient",
        "com.amazon.avod.thirdpartyclient", "com.hotstar.android",
        "com.jio.media.ondemand", "air.tv.twitch.android", "com.spotify.music",
        "com.google.android.apps.youtube.music"
    )
    private val MESSAGING_APPS = setOf(
        "com.whatsapp", "org.telegram.messenger", "com.discord",
        "com.facebook.orca", "jp.naver.line.android"
    )
    private val GAMING_APPS = setOf(
        "com.pubg.imobile", "com.activision.callofduty.shooter",
        "com.garena.game.freefire", "com.mojang.minecraftpe",
        "com.supercell.clashofclans", "com.king.candycrushsaga"
    )

    data class AppUsage(
        val packageName: String,
        val appName: String,
        val durationMinutes: Float,
        val category: String
    )

    /** Returns total phone screen time today in minutes. */
    fun getTodayScreenTimeMinutes(context: Context): Float {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY,
            startOfDay, System.currentTimeMillis())
        if (stats.isNullOrEmpty()) return 30f
        val totalMs = stats.sumOf { it.totalTimeInForeground }
        return (totalMs / 1000f / 60f).coerceAtLeast(1f)
    }

    /** Returns distinct app count used today (proxy for app switches). */
    fun getAppSwitchCount(context: Context): Int {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY,
            startOfDay, System.currentTimeMillis())
        return stats?.count { it.totalTimeInForeground > 1000 }?.coerceAtLeast(1) ?: 5
    }

    /** Returns total screen time this week in hours. */
    fun getWeeklyScreenTimeHours(context: Context): Float {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val sevenDaysAgo = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -7) }.timeInMillis
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_WEEKLY,
            sevenDaysAgo, System.currentTimeMillis())
        val totalMs = stats?.sumOf { it.totalTimeInForeground } ?: 0L
        return (totalMs / 1000f / 3600f).coerceAtLeast(1f)
    }

    /**
     * Feature 0: Per-app usage breakdown for continuous tracking.
     * Returns top N apps by usage time today, with category labels.
     */
    fun getTopAppsUsage(context: Context, n: Int = 10): List<AppUsage> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY,
            startOfDay, System.currentTimeMillis())
            ?: return emptyList()

        val pm = context.packageManager
        return stats
            .filter { it.totalTimeInForeground > 5000 }   // > 5 seconds
            .sortedByDescending { it.totalTimeInForeground }
            .take(n)
            .map { stat ->
                val appName = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(stat.packageName, 0)).toString()
                } catch (_: Exception) { stat.packageName }
                AppUsage(
                    packageName    = stat.packageName,
                    appName        = appName,
                    durationMinutes = (stat.totalTimeInForeground / 1000f / 60f),
                    category       = categorizeApp(stat.packageName)
                )
            }
    }

    private fun categorizeApp(packageName: String): String = when {
        SOCIAL_APPS.contains(packageName)       -> "social"
        ENTERTAINMENT_APPS.contains(packageName)-> "entertainment"
        MESSAGING_APPS.contains(packageName)    -> "messaging"
        GAMING_APPS.contains(packageName)       -> "gaming"
        packageName.contains("browser")         -> "browser"
        packageName.contains("camera")          -> "camera"
        else                                    -> "other"
    }
}