package com.softcontrol.ai

import android.content.Context
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.launch

object RLManager {

    /** Call after /analyze response — execute the RL-recommended action. */
    fun handleRLAction(
        ctx: Context,
        action: String?,
        message: String?,
        scope: LifecycleCoroutineScope
    ) {
        when (action) {
            "show_tip" -> {
                NotificationHelper.sendInterventionNotification(
                    ctx, message ?: "Tip: Take a short break from your phone."
                )
            }
            "send_warning" -> {
                NotificationHelper.sendInterventionNotification(
                    ctx, message ?: "⚠️ High distraction risk. Stay focused!"
                )
            }
            "suggest_focus" -> {
                NotificationHelper.sendInterventionNotification(
                    ctx, message ?: "🎯 Start a focus session now!"
                )
            }
            else -> { /* none — do nothing */ }
        }
    }

    /** Send reward signal back to RL agent after outcome is known. */
    fun sendFeedback(
        ctx: Context,
        scope: LifecycleCoroutineScope,
        action: String,
        prevRisk: Float,
        newRisk: Float,
        newScore: Int
    ) {
        val userId = UserProfileManager.getUserId(ctx)
        val reward = if (newScore > 60 && newRisk < prevRisk) 1.0f
        else if (newRisk > prevRisk + 0.1f) -1.0f
        else 0.0f

        val actionInt = mapOf("none" to 0,"show_tip" to 1,"send_warning" to 2,"suggest_focus" to 3)
            .getOrDefault(action, 0)

        scope.launch {
            try {
                val prefs = ctx.getSharedPreferences("softcontrol", Context.MODE_PRIVATE)
                val state = mapOf(
                    "risk" to prevRisk,
                    "hour" to prefs.getInt("last_hour", 12).toFloat(),
                    "violations" to prefs.getInt("violations", 0).toFloat(),
                    "streak" to GamificationManager.getStreak(ctx).toFloat()
                )
                val nextState = state.toMutableMap().also { it["risk"] = newRisk }
                RetrofitClient.instance.sendRLFeedback(
                    RLFeedbackRequest(userId, actionInt, state, nextState, reward)
                )
            } catch (_: Exception) { }
        }
    }
}