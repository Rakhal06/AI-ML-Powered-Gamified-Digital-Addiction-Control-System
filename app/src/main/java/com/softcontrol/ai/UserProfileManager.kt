package com.softcontrol.ai

import android.content.Context
import java.util.UUID

object UserProfileManager {

    private const val PREFS = "softcontrol"

    fun getUserId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString("user_id", null) ?: run {
            val newId = UUID.randomUUID().toString()
            prefs.edit().putString("user_id", newId).apply()
            newId
        }
    }

    fun getDisplayName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString("display_name", "Player") ?: "Player"
    }

    fun setDisplayName(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("display_name", name.trim().ifEmpty { "Player" }).apply()
    }
}