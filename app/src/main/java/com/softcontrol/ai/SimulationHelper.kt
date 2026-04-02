package com.softcontrol.ai

import android.content.Context

object SimulationHelper {

    private const val PREFS = "softcontrol"

    // Default/moderate demo state (distracted user)
    fun preloadDefaultData(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("last_label",   "distracted")
            .putInt("last_score",      54)
            .putFloat("last_risk",     0.63f)
            .putString("last_insight",
                "You tend to switch apps frequently between 10 PM and midnight. " +
                        "Your longest uninterrupted streak today was only 8 minutes.")
            .putString("last_tip",
                "Try activating Do Not Disturb after 9 PM and keep your phone " +
                        "face-down while studying or working.")
            .putString("last_cluster", "Night Owl")
            .putFloat("weekly_hours",  42.5f)
            .putInt("violations",      2)
            .putInt("monster_level",   3)
            .putFloat("time_spent",    87f)
            .putInt("app_switches",    34)
            .apply()
    }

    // Bad session — for demo: shows monster at level 5, addicted label, red scores
    fun preloadBadSession(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("last_label",   "addicted")
            .putInt("last_score",      18)
            .putFloat("last_risk",     0.91f)
            .putString("last_insight",
                "Critical: You used your phone for over 3 hours past midnight. " +
                        "Your session failure rate this week is 80%.")
            .putString("last_tip",
                "Delete social media apps from your home screen immediately. " +
                        "Set a hard screen time limit of 2 hours per day.")
            .putString("last_cluster", "Binge User")
            .putFloat("weekly_hours",  71f)
            .putInt("violations",      3)
            .putInt("monster_level",   5)
            .putFloat("time_spent",    182f)
            .putInt("app_switches",    65)
            .apply()
    }

    // Good session — for demo: shows monster sleeping, focused label, green scores
    fun preloadGoodSession(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("last_label",   "focused")
            .putInt("last_score",      92)
            .putFloat("last_risk",     0.09f)
            .putString("last_insight",
                "Excellent discipline today! Zero violations across 3 focus sessions. " +
                        "Your self-control score improved 38 points compared to yesterday.")
            .putString("last_tip",
                "Keep this streak going — you are building a powerful habit. " +
                        "Try extending your sessions to 30 minutes tomorrow.")
            .putString("last_cluster", "Regular User")
            .putFloat("weekly_hours",  18.5f)
            .putInt("violations",      0)
            .putInt("monster_level",   0)
            .putFloat("time_spent",    22f)
            .putInt("app_switches",    8)
            .apply()
    }
}