package com.softcontrol.ai

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GamificationManager {

    private const val PREFS = "softcontrol_xp"

    private val LEVEL_XP    = intArrayOf(0,100,250,500,1000,2000,4000,7000,12000,20000)
    private val LEVEL_NAMES = arrayOf("Beginner","Aware","Focused","Disciplined",
        "Consistent","Master","Expert","Elite","Champion","Grandmaster")

    // Badges: key → display label
    val ALL_BADGES = mapOf(
        "first_session"  to "🎯 First Session",
        "zero_hero"      to "🏆 Zero Hero",
        "week_warrior"   to "🔥 Week Warrior",
        "month_master"   to "🌟 Month Master",
        "century"        to "💯 Centurion",
        "comeback"       to "💪 Comeback Kid"
    )

    fun getTotalXP(ctx: Context)  = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("total_xp", 0)
    fun getStreak(ctx: Context)   = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("streak", 0)
    fun getLevel(totalXp: Int): Int {
        for (i in LEVEL_XP.indices.reversed()) if (totalXp >= LEVEL_XP[i]) return i + 1
        return 1
    }
    fun getLevelName(level: Int) = LEVEL_NAMES.getOrElse(level - 1) { "Grandmaster" }
    fun getXpForNextLevel(totalXp: Int): Int {
        val lvl = getLevel(totalXp)
        return if (lvl >= LEVEL_XP.size) Int.MAX_VALUE else LEVEL_XP[lvl]
    }
    fun getXpProgress(totalXp: Int): Float {
        val lvl   = getLevel(totalXp)
        val start = LEVEL_XP.getOrElse(lvl - 1) { 0 }
        val end   = LEVEL_XP.getOrElse(lvl) { Int.MAX_VALUE }
        return if (end == Int.MAX_VALUE) 1f else (totalXp - start).toFloat() / (end - start)
    }

    fun awardXP(ctx: Context, xpEarned: Int, focusCompleted: Boolean, violations: Int): AwardResult {
        val prefs    = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today    = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val lastDay  = prefs.getString("last_session_date", "") ?: ""
        val sessions = prefs.getInt("total_sessions", 0) + 1

        // Streak logic
        val yesterday = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(Date(System.currentTimeMillis() - 86400000L))
        val newStreak = when {
            lastDay == today     -> prefs.getInt("streak", 0)           // same day — no change
            lastDay == yesterday -> prefs.getInt("streak", 0) + 1       // consecutive day
            else                 -> if (focusCompleted) 1 else 0        // streak broken or first
        }

        val oldXP    = prefs.getInt("total_xp", 0)
        val newXP    = maxOf(0, oldXP + xpEarned)
        val oldLevel = getLevel(oldXP)
        val newLevel = getLevel(newXP)
        val leveledUp = newLevel > oldLevel

        // Badge checks
        val earnedBadges = mutableListOf<String>()
        val badges = prefs.getStringSet("badges", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        fun tryBadge(key: String) { if (!badges.contains(key)) { badges.add(key); earnedBadges.add(key) } }
        if (sessions == 1)         tryBadge("first_session")
        if (violations == 0 && focusCompleted) tryBadge("zero_hero")
        if (newStreak >= 7)        tryBadge("week_warrior")
        if (newStreak >= 30)       tryBadge("month_master")
        if (sessions >= 100)       tryBadge("century")
        if (xpEarned > 0 && oldXP < newXP && oldLevel > 1 && !focusCompleted) tryBadge("comeback")

        prefs.edit()
            .putInt("total_xp", newXP)
            .putInt("streak", newStreak)
            .putString("last_session_date", today)
            .putInt("total_sessions", sessions)
            .putStringSet("badges", badges)
            .apply()

        return AwardResult(
            newTotalXP   = newXP,
            xpChange     = xpEarned,
            newLevel     = newLevel,
            leveledUp    = leveledUp,
            streak       = newStreak,
            newBadges    = earnedBadges.mapNotNull { ALL_BADGES[it] }
        )
    }

    fun getBadges(ctx: Context): List<String> {
        val owned = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet("badges", emptySet()) ?: emptySet()
        return owned.mapNotNull { ALL_BADGES[it] }
    }

    data class AwardResult(
        val newTotalXP: Int,
        val xpChange: Int,
        val newLevel: Int,
        val leveledUp: Boolean,
        val streak: Int,
        val newBadges: List<String>
    )
}