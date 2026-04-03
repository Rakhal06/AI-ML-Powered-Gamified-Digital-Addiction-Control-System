package com.softcontrol.ai

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import java.util.Calendar

/**
 * Feature 1: Context-Aware Intelligence
 * Collects environmental context: battery level, headphone status, location type, day type.
 */
object ContextCollector {

    /** Returns battery level 0–100. */
    fun getBatteryLevel(context: Context): Int {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = context.registerReceiver(null, intentFilter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale) else 100
    }

    /** Returns true if any wired/wireless headphones or earphones are connected. */
    fun isHeadphoneConnected(context: Context): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.isWiredHeadsetOn || am.isBluetoothA2dpOn || am.isBluetoothScoOn
    }

    /**
     * Returns location type: "home" | "college" | "other"
     * User sets this manually in SharedPreferences.
     * Default: "other"
     */
    fun getLocationType(context: Context): String {
        return context.getSharedPreferences("softcontrol", Context.MODE_PRIVATE)
            .getString("location_type", "other") ?: "other"
    }

    fun setLocationType(context: Context, locationType: String) {
        context.getSharedPreferences("softcontrol", Context.MODE_PRIVATE)
            .edit().putString("location_type", locationType).apply()
    }

    /** Returns "weekday" or "weekend" based on current day. */
    fun getDayType(): String {
        val dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        return if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) "weekend" else "weekday"
    }

    /** Returns all context as a snapshot map. */
    fun getContextSnapshot(context: Context): ContextSnapshot {
        return ContextSnapshot(
            batteryLevel       = getBatteryLevel(context),
            headphoneConnected = isHeadphoneConnected(context),
            locationType       = getLocationType(context),
            dayType            = getDayType()
        )
    }

    data class ContextSnapshot(
        val batteryLevel: Int,
        val headphoneConnected: Boolean,
        val locationType: String,
        val dayType: String
    )
}