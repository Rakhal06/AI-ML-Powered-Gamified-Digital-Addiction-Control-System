package com.softcontrol.ai

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.softcontrol.ai.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var onDeviceML: OnDeviceMLHelper

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(this,
            "Enable notifications for violation alerts", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // TE-1: Initialize on-device ML
        onDeviceML = OnDeviceMLHelper(this)

        NotificationHelper.createChannels(this)
        requestNotificationPermission()
        TrackingService.start(this)

        val prefs = getSharedPreferences("softcontrol", Context.MODE_PRIVATE)
        if (!prefs.contains("last_label")) SimulationHelper.preloadDefaultData(this)

        if (!hasUsagePermission()) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            Toast.makeText(this, "Please grant Usage Access permission", Toast.LENGTH_LONG).show()
        }

        // Core navigation
        binding.btnAnalyze.setOnClickListener { analyzeNow() }
        binding.btnFocus.setOnClickListener   { startActivity(Intent(this, FocusActivity::class.java)) }
        binding.btnReport.setOnClickListener  { startActivity(Intent(this, ReportActivity::class.java)) }
        binding.btnCoach.setOnClickListener   { startActivity(Intent(this, CoachActivity::class.java)) }

        // Feature 4 / 5 / 7 navigation
        binding.btnGamification.setOnClickListener { startActivity(Intent(this, GamificationActivity::class.java)) }
        binding.btnLeaderboard.setOnClickListener  { startActivity(Intent(this, LeaderboardActivity::class.java)) }
        binding.btnAnalytics.setOnClickListener    { startActivity(Intent(this, AnalyticsDashboardActivity::class.java)) }

        // Demo buttons
        binding.btnSimGood.setOnClickListener {
            SimulationHelper.preloadGoodSession(this); loadFromPrefs()
            Toast.makeText(this, "Good session loaded!", Toast.LENGTH_SHORT).show()
        }
        binding.btnSimDefault.setOnClickListener {
            SimulationHelper.preloadDefaultData(this); loadFromPrefs()
            Toast.makeText(this, "Default session loaded!", Toast.LENGTH_SHORT).show()
        }
        binding.btnSimBad.setOnClickListener {
            SimulationHelper.preloadBadSession(this); loadFromPrefs()
            Toast.makeText(this, "Bad session loaded!", Toast.LENGTH_SHORT).show()
        }

        analyzeNow()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // ── Analyze: call Flask backend with full context ─────────
    private fun analyzeNow() {
        val hour       = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val prefs      = getSharedPreferences("softcontrol", Context.MODE_PRIVATE)
        val violations = prefs.getInt("violations", 0)

        val timeSpent = if (hasUsagePermission())
            UsageStatsHelper.getTodayScreenTimeMinutes(this)
        else prefs.getFloat("time_spent", 30f)

        val appSwitches = if (hasUsagePermission())
            UsageStatsHelper.getAppSwitchCount(this)
        else prefs.getInt("app_switches", 5)

        // Feature 1: Context-Aware Intelligence
        val ctx     = ContextCollector.getContextSnapshot(this)
        val userId  = UserProfileManager.getUserId(this)
        val name    = UserProfileManager.getDisplayName(this)
        val streak  = GamificationManager.getStreak(this)

        val request = AnalyzeRequest(
            time_spent          = timeSpent,
            app_switches        = appSwitches,
            hour_of_day         = hour,
            violations          = violations,
            focus_completed     = false,
            user_id             = userId,          // Feature 2: Personalized models
            display_name        = name,
            location_type       = ctx.locationType,
            day_type            = ctx.dayType,
            battery_level       = ctx.batteryLevel,
            headphone_connected = ctx.headphoneConnected,
            streak              = streak
        )

        lifecycleScope.launch {
            try {
                val response = RetrofitClient.instance.analyze(request)
                if (response.isSuccessful) {
                    response.body()?.let { data ->
                        updateUI(data)
                        saveToPrefs(data)

                        // TE-3: RL action handling
                        RLManager.handleRLAction(this@MainActivity, data.rl_action, data.rl_message, lifecycleScope)

                        // Feature 4: XP award
                        val award = GamificationManager.awardXP(
                            this@MainActivity, data.xp_earned, false, violations
                        )
                        if (award.leveledUp) {
                            Toast.makeText(this@MainActivity,
                                "LEVEL UP! You are now Level ${award.newLevel}: ${GamificationManager.getLevelName(award.newLevel)}",
                                Toast.LENGTH_LONG).show()
                        }
                        award.newBadges.forEach { badge ->
                            Toast.makeText(this@MainActivity, "Badge earned: $badge", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else { loadFromPrefsWithOnDeviceFallback(timeSpent, appSwitches, hour, violations, ctx) }
            } catch (e: Exception) {
                // TE-1: On-device ML fallback when server unreachable
                loadFromPrefsWithOnDeviceFallback(timeSpent, appSwitches, hour, violations, ctx)
                Toast.makeText(this@MainActivity,
                    if (onDeviceML.isAvailable()) "Server offline — using on-device AI"
                    else "Cannot connect to server — showing cached data",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    /** TE-1: Try on-device ML first, then cached data. */
    private fun loadFromPrefsWithOnDeviceFallback(
        timeSpent: Float, appSwitches: Int, hour: Int, violations: Int,
        ctx: ContextCollector.ContextSnapshot
    ) {
        if (onDeviceML.isAvailable()) {
            val prefs       = getSharedPreferences("softcontrol", Context.MODE_PRIVATE)
            val prevUsage   = prefs.getFloat("time_spent", timeSpent * 0.8f)
            val features    = onDeviceML.buildFeatureArray(
                timeSpent, appSwitches, hour, violations,
                sessionGap       = 15f,
                previousUsage    = prevUsage,
                focusSessions    = 0,
                dayOfWeek        = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1,
                locationType     = ctx.locationType,
                dayType          = ctx.dayType,
                batteryLevel     = ctx.batteryLevel,
                headphoneConnected = ctx.headphoneConnected
            )
            val result = onDeviceML.predict(features)
            if (result != null) {
                val (label, conf) = result
                // Update UI partially with on-device result
                binding.tvLabel.text = "${label.uppercase()} (On-Device)"
                binding.tvLabel.setTextColor(labelColor(label))
                binding.tvConfidence.text = "On-device confidence: ${(conf * 100).toInt()}%"
            }
        }
        loadFromPrefs()
    }

    // ── Update UI from live API ─────────────────────────────────────────
    private fun updateUI(data: AnalyzeResponse) {
        val monsterEmoji = when (data.monster_level) {
            0 -> "😴"; 1 -> "😐"; 2 -> "😤"; 3 -> "😡"; 4 -> "👹"; 5 -> "💀"; else -> "😴"
        }
        binding.tvMonster.text      = monsterEmoji
        binding.tvMonsterLabel.text = "Addiction Monster — Level ${data.monster_level}/5"

        val displayLabel = if (data.label == data.ensemble_label)
            data.label.uppercase()
        else "${data.label.uppercase()} (Ensemble: ${data.ensemble_label.uppercase()})"
        binding.tvLabel.text = displayLabel
        binding.tvLabel.setTextColor(labelColor(data.label))
        binding.tvConfidence.text = "Ensemble confidence: ${(data.confidence * 100).toInt()}%"

        binding.tvSelfControl.text = data.self_control_score.toString()
        binding.tvSelfControl.setTextColor(scoreColor(data.self_control_score))

        val riskPct = (data.risk_score * 100).toInt()
        binding.tvRisk.text = "$riskPct%"
        binding.tvRisk.setTextColor(riskColor(riskPct))

        binding.tvCluster.text = data.cluster
        binding.tvWeekly.text  = "${data.weekly_screen_time_hours}h/week"

        if (data.is_binge_session) {
            binding.cardBinge.visibility = View.VISIBLE
            binding.tvBingeAlert.text =
                "BINGE SESSION DETECTED — Anomaly score: ${data.anomaly_score}\n" +
                        "Isolation Forest flagged this session as abnormal usage."
        } else {
            binding.cardBinge.visibility = View.GONE
        }

        binding.tvTopFactor.text   = "Top contributing factor: ${data.top_factor.replace('_',' ').uppercase()}"
        binding.tvShapDetails.text = buildShapText(data)
        binding.tvInsight.text     = buildRichInsight(data)
        binding.tvCoachTip.text    = data.coach_tip

        // Show LSTM trend
        if (data.lstm_trend != "stable" && data.lstm_trend != "insufficient_data") {
            val trendIcon = if (data.lstm_trend == "worsening") "📈 Trend: WORSENING" else "📉 Trend: IMPROVING"
            binding.tvCoachTip.text = "${data.coach_tip}\n\n$trendIcon (LSTM prediction)"
        }
    }

    // ── Rich AI Insight ─────────────────────────────────────
    private fun buildRichInsight(data: AnalyzeResponse): String {
        val prefs      = getSharedPreferences("softcontrol", Context.MODE_PRIVATE)
        val riskPct    = (data.risk_score * 100).toInt()
        val hour       = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val timeSpent  = prefs.getFloat("time_spent", 0f).toInt()
        val switches   = prefs.getInt("app_switches", 0)
        val violations = prefs.getInt("violations", 0)
        val violApps   = prefs.getString("violation_apps", "") ?: ""
        val lines      = mutableListOf<String>()

        // 1. Classification
        when (data.label) {
            "addicted" -> {
                lines.add("AI Classification: ADDICTED (${(data.confidence*100).toInt()}% confidence)")
                lines.add("All ML models classified your session as Addicted. Your usage pattern closely matches addiction signatures.")
                if (timeSpent > 150) lines.add("${timeSpent}min screen time = ${timeSpent/60}h ${timeSpent%60}m — ${timeSpent-120}min over the 2h healthy daily limit.")
                if (violations >= 3) lines.add("3 violations reached the failure threshold — strong indicator of impulsive usage.")
                else if (violations > 0) lines.add("$violations violation(s) recorded.")
            }
            "distracted" -> {
                lines.add("AI Classification: DISTRACTED (${(data.confidence*100).toInt()}% confidence)")
                lines.add("The ML models detected attention fragmentation. Not critical yet, but this pattern worsens over time.")
                if (switches > 15) lines.add("$switches app switches today. Each context switch costs ~23 minutes of focus recovery.")
            }
            else -> {
                lines.add("AI Classification: FOCUSED (${(data.confidence*100).toInt()}% confidence)")
                lines.add("All ML models agree your behavior is healthy today.")
                if (violations == 0) lines.add("Zero violations — you resisted every impulse.")
            }
        }
        lines.add("")

        // 2. Relapse risk
        when {
            riskPct >= 80 -> { lines.add("Relapse Risk: ${riskPct}% — CRITICAL"); lines.add("XGBoost predicts ${riskPct}% probability of relapsing. Action required now.") }
            riskPct >= 60 -> { lines.add("Relapse Risk: ${riskPct}% — HIGH"); lines.add("Elevated risk. Avoid entertainment apps for the next 2 hours.") }
            riskPct >= 40 -> { lines.add("Relapse Risk: ${riskPct}% — MODERATE"); lines.add("Manageable. Consider a focus session.") }
            else -> { lines.add("Relapse Risk: ${riskPct}% — LOW"); lines.add("You're in a healthy zone.") }
        }
        lines.add("")

        // 3. Time context
        when {
            hour in 22..23 || hour in 0..4 -> lines.add("LATE NIGHT (${hour}:00) — Highest-risk window. Willpower at its lowest.")
            hour in 5..8   -> lines.add("EARLY MORNING (${hour}:00) — Try a 30-min phone-free morning routine.")
            hour in 13..16 -> lines.add("AFTERNOON SLUMP (${hour}:00) — Energy dips, willpower weakens.")
            else           -> lines.add("Usage at ${hour}:00 — Normal daytime hours.")
        }
        lines.add("")

        // 4. Violations
        if (violations > 0 && violApps.isNotEmpty() && violApps != "None") {
            lines.add("Violation Details:")
            violApps.split(" | ").forEachIndexed { idx, entry ->
                val ord = when(idx+1){ 1->"1st"; 2->"2nd"; else->"${idx+1}th" }
                lines.add("  $ord → $entry")
            }
            lines.add("")
        }

        // 5. Cluster insight
        when (data.cluster) {
            "Night Owl"      -> lines.add("Profile: Night Owl — 60% higher relapse risk than daytime users. Fix: hard curfew at 10 PM.")
            "Binge User"     -> lines.add("Profile: Binge User — Long sessions flood the brain with dopamine. Fix: 30-min app timers.")
            "Impulsive User" -> lines.add("Profile: Impulsive User — $switches app switches. Fix: phone fasting blocks of 1h.")
        }
        lines.add("")

        // 6. SHAP
        val shapExplain = when (data.top_factor) {
            "time_spent"   -> "${timeSpent}min screen time pushed your classification the most."
            "violations"   -> "$violations violation(s) had the highest model impact."
            "app_switches" -> "$switches app switches had the strongest influence."
            "hour_of_day"  -> "Usage at ${hour}:00 was the top factor."
            else           -> "${data.top_factor.replace("_"," ")} had the strongest influence."
        }
        lines.add("SHAP Top Factor = ${data.top_factor.replace("_"," ").uppercase()}")
        lines.add(shapExplain)
        lines.add("")

        // 7. Used personal model
        if (data.used_personal_model) lines.add("Using your personalized AI model (50+ sessions learned).")

        // 8. Score breakdown
        lines.add("Score: ${data.self_control_score}/100 = 100 - (${violations}×10) - ${(timeSpent/5).toInt().coerceAtMost(30)}")

        // 9. Weekly forecast
        val excessH = (data.weekly_screen_time_hours - 14f).coerceAtLeast(0f)
        lines.add("Weekly Forecast: ${data.weekly_screen_time_hours}h" +
                if (excessH > 0) " (${String.format("%.1f",excessH)}h over the 14h/week healthy limit)"
                else " — within healthy range")

        return lines.joinToString("\n")
    }

    // ── Load from cache ─────────────────────────────────────
    private fun loadFromPrefs() {
        val prefs     = getSharedPreferences("softcontrol", Context.MODE_PRIVATE)
        val label     = prefs.getString("last_label",  "focused") ?: "focused"
        val score     = prefs.getInt("last_score",      100)
        val risk      = prefs.getFloat("last_risk",     0f)
        val insight   = prefs.getString("last_insight", "Tap Analyze to get your AI insight.") ?: ""
        val tip       = prefs.getString("last_tip",     "Complete a focus session first.") ?: ""
        val cluster   = prefs.getString("last_cluster", "—") ?: "—"
        val weekly    = prefs.getFloat("weekly_hours",  0f)
        val monster   = prefs.getInt("monster_level",   0)
        val topFactor = prefs.getString("top_factor",   "violations") ?: "violations"

        val monsterEmoji = when (monster) { 0->"😴"; 1->"😐"; 2->"😤"; 3->"😡"; 4->"👹"; 5->"💀"; else->"😴" }
        binding.tvMonster.text       = monsterEmoji
        binding.tvMonsterLabel.text  = "Addiction Monster — Level $monster/5"
        binding.tvLabel.text         = label.uppercase()
        binding.tvLabel.setTextColor(labelColor(label))
        binding.tvSelfControl.text   = score.toString()
        binding.tvSelfControl.setTextColor(scoreColor(score))
        val riskPct = (risk * 100).toInt()
        binding.tvRisk.text          = "$riskPct%"
        binding.tvRisk.setTextColor(riskColor(riskPct))
        binding.tvCluster.text       = cluster
        binding.tvWeekly.text        = "${weekly}h/week"
        binding.tvInsight.text       = insight
        binding.tvCoachTip.text      = tip
        binding.tvTopFactor.text     = "Top contributing factor: ${topFactor.replace('_',' ').uppercase()}"
        binding.tvConfidence.text    = "Ensemble confidence: —"
        binding.cardBinge.visibility = View.GONE
    }

    private fun saveToPrefs(data: AnalyzeResponse) {
        getSharedPreferences("softcontrol", Context.MODE_PRIVATE).edit()
            .putString("last_label",   data.label)
            .putInt("last_score",       data.self_control_score)
            .putFloat("last_risk",      data.risk_score)
            .putString("last_insight",  buildRichInsight(data))
            .putString("last_tip",      data.coach_tip)
            .putString("last_cluster",  data.cluster)
            .putFloat("weekly_hours",   data.weekly_screen_time_hours)
            .putInt("monster_level",    data.monster_level)
            .putString("top_factor",    data.top_factor)
            .putBoolean("is_binge",     data.is_binge_session)
            .apply()
    }

    private fun buildShapText(data: AnalyzeResponse): String {
        val expl = data.explanations ?: return ""
        return expl.entries
            .sortedByDescending { kotlin.math.abs(it.value) }
            .take(4)
            .joinToString("\n") { (feature, value) ->
                val bar  = if (value > 0) "▲" else "▼"
                val pct  = String.format("%.1f", kotlin.math.abs(value) * 100)
                val name = feature.replace('_', ' ').replaceFirstChar { it.uppercase() }
                "$bar $name: $pct% impact"
            }
    }

    private fun labelColor(label: String) = when (label) {
        "focused"    -> 0xFF00FF88.toInt(); "distracted" -> 0xFFFFAA00.toInt()
        "addicted"   -> 0xFFFF4444.toInt(); else         -> 0xFFFFFFFF.toInt()
    }
    private fun scoreColor(score: Int) = when {
        score >= 70 -> 0xFF00FF88.toInt(); score >= 40 -> 0xFFFFAA00.toInt(); else -> 0xFFFF4444.toInt()
    }
    private fun riskColor(riskPct: Int) = when {
        riskPct < 40 -> 0xFF00FF88.toInt(); riskPct < 70 -> 0xFFFFAA00.toInt(); else -> 0xFFFF4444.toInt()
    }

    private fun hasUsagePermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode   = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    override fun onDestroy() {
        super.onDestroy()
        onDeviceML.close()
    }
}