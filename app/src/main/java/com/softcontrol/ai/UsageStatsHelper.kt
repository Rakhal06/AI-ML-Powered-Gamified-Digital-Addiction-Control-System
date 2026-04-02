package com.softcontrol.ai

import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.Calendar

object UsageStatsHelper {

    // Returns total phone screen time today in minutes
    fun getTodayScreenTimeMinutes(context: Context): Float {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val stats = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startOfDay,
            System.currentTimeMillis()
        )
        if (stats.isNullOrEmpty()) return 30f   // safe fallback for demo

        val totalMs = stats.sumOf { it.totalTimeInForeground }
        return (totalMs / 1000f / 60f).coerceAtLeast(1f)
    }

    // Returns distinct app count used today (proxy for app switches)
    fun getAppSwitchCount(context: Context): Int {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val stats = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startOfDay,
            System.currentTimeMillis()
        )
        // Only count apps used for more than 1 second (filters ghost launches)
        return stats?.count { it.totalTimeInForeground > 1000 }?.coerceAtLeast(1) ?: 5
    }

    // Returns total screen time this week in hours
    fun getWeeklyScreenTimeHours(context: Context): Float {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val sevenDaysAgo = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -7)
        }.timeInMillis

        val stats = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_WEEKLY,
            sevenDaysAgo,
            System.currentTimeMillis()
        )
        val totalMs = stats?.sumOf { it.totalTimeInForeground } ?: 0L
        return (totalMs / 1000f / 3600f).coerceAtLeast(1f)
    }
}