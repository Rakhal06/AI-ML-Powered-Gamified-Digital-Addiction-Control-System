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

    // Runtime permission launcher for POST_NOTIFICATIONS (Android 13+)
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Toast.makeText(this, "Notifications enabled!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this,
                "Notifications blocked — violation alerts won't appear",
                Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Create notification channels first
        NotificationHelper.createChannels(this)

        // Request notification permission on Android 13+
        requestNotificationPermission()

        // Start background tracking service
        TrackingService.start(this)

        val prefs = getSharedPreferences("softcontrol", Context.MODE_PRIVATE)
        if (!prefs.contains("last_label")) {
            SimulationHelper.preloadDefaultData(this)
        }

        // Request usage stats permission if not granted
        if (!hasUsagePermission()) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            Toast.makeText(this, "Please grant Usage Access permission", Toast.LENGTH_LONG).show()
        }

        // Main buttons
        binding.btnAnalyze.setOnClickListener { analyzeNow() }
        binding.btnFocus.setOnClickListener {
            startActivity(Intent(this, FocusActivity::class.java))
        }
        binding.btnReport.setOnClickListener {
            startActivity(Intent(this, ReportActivity::class.java))
        }
        binding.btnCoach.setOnClickListener {
            startActivity(Intent(this, CoachActivity::class.java))
        }

        // Simulation buttons
        binding.btnSimGood.setOnClickListener {
            SimulationHelper.preloadGoodSession(this)
            loadFromPrefs()
            Toast.makeText(this, "✅ Good session loaded!", Toast.LENGTH_SHORT).show()
        }
        binding.btnSimDefault.setOnClickListener {
            SimulationHelper.preloadDefaultData(this)
            loadFromPrefs()
            Toast.makeText(this, "😤 Default session loaded!", Toast.LENGTH_SHORT).show()
        }
        binding.btnSimBad.setOnClickListener {
            SimulationHelper.preloadBadSession(this)
            loadFromPrefs()
            Toast.makeText(this, "💀 Bad session loaded!", Toast.LENGTH_SHORT).show()
        }

        analyzeNow()
    }

    // ── Request POST_NOTIFICATIONS permission (Android 13+) ──
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    // Already granted — nothing to do
                }
                else -> {
                    // Ask the user
                    notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
        // Below Android 13 — no runtime permission needed, notifications work automatically
    }

    // ── Analyze: calls Flask backend ─────────────────────────
    private fun analyzeNow() {
        val hour       = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val prefs      = getSharedPreferences("softcontrol", Context.MODE_PRIVATE)
        val violations = prefs.getInt("violations", 0)

        val timeSpent = if (hasUsagePermission())
            UsageStatsHelper.getTodayScreenTimeMinutes(this)
        else
            prefs.getFloat("time_spent", 30f)

        val appSwitches = if (hasUsagePermission())
            UsageStatsHelper.getAppSwitchCount(this)
        else
            prefs.getInt("app_switches", 5)

        val request = AnalyzeRequest(
            time_spent   = timeSpent,
            app_switches = appSwitches,
            hour_of_day  = hour,
            violations   = violations
        )

        lifecycleScope.launch {
            try {
                val response = RetrofitClient.instance.analyze(request)
                if (response.isSuccessful) {
                    response.body()?.let { data ->
                        updateUI(data)
                        saveToPrefs(data)
                    }
                } else {
                    loadFromPrefs()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "Cannot connect to server — showing cached data",
                    Toast.LENGTH_LONG
                ).show()
                loadFromPrefs()
            }
        }
    }

    // ── Update UI from live API response ─────────────────────
    private fun updateUI(data: AnalyzeResponse) {
        val monsterEmoji = when (data.monster_level) {
            0 -> "😴"; 1 -> "😐"; 2 -> "😤"; 3 -> "😡"; 4 -> "👹"; 5 -> "💀"; else -> "😴"
        }
        binding.tvMonster.text      = monsterEmoji
        binding.tvMonsterLabel.text = "Addiction Monster — Level ${data.monster_level}/5"

        val displayLabel = if (data.label == data.ensemble_label)
            data.label.uppercase()
        else
            "${data.label.uppercase()} (Ensemble: ${data.ensemble_label.uppercase()})"

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
                "🚨 BINGE SESSION DETECTED — Anomaly score: ${data.anomaly_score}\n" +
                        "Isolation Forest flagged this session as abnormal usage."
        } else {
            binding.cardBinge.visibility = View.GONE
        }

        binding.tvTopFactor.text  = "Top contributing factor: ${data.top_factor.replace('_',' ').uppercase()}"
        binding.tvShapDetails.text = buildShapText(data)
        binding.tvInsight.text    = buildRichInsight(data)
        binding.tvCoachTip.text   = data.coach_tip
    }

    // ── Rich insight — replaces plain backend string ──────────
    private fun buildRichInsight(data: AnalyzeResponse): String {
        val riskPct  = (data.risk_score * 100).toInt()
        val hour     = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val prefs    = getSharedPreferences("softcontrol", Context.MODE_PRIVATE)
        val timeSpent = prefs.getFloat("time_spent", 0f).toInt()
        val switches  = prefs.getInt("app_switches", 0)
        val violations = prefs.getInt("violations", 0)

        val lines = mutableListOf<String>()

        // Behavior classification reason
        when (data.label) {
            "addicted" -> {
                lines.add("🔴 You are classified as ADDICTED.")
                if (timeSpent > 150) lines.add("You have spent ${timeSpent}min on your phone today — that is ${timeSpent/60}h ${timeSpent%60}min, well above the healthy limit of 2h.")
                if (violations >= 3) lines.add("You broke focus 3 times — a strong signal of compulsive usage.")
            }
            "distracted" -> {
                lines.add("🟡 You are classified as DISTRACTED.")
                if (switches > 15) lines.add("You switched apps $switches times today — constant task-switching reduces productivity by up to 40%.")
                if (violations > 0) lines.add("You logged $violations focus violation(s) — each one reinforces impulsive behavior.")
            }
            "focused" -> {
                lines.add("🟢 You are classified as FOCUSED — great discipline today!")
                if (violations == 0) lines.add("Zero violations — you maintained strong self-control throughout.")
            }
        }

        // Relapse risk explanation
        when {
            riskPct >= 80 -> lines.add("⚠️ Relapse risk is CRITICAL ($riskPct%). Your pattern strongly matches past addictive behavior. Take a break NOW.")
            riskPct >= 60 -> lines.add("⚠️ Relapse risk is HIGH ($riskPct%). You are on the edge — avoid opening social media for the next 2 hours.")
            riskPct >= 40 -> lines.add("🟡 Relapse risk is MODERATE ($riskPct%). Stay mindful. Do not open distracting apps after 9 PM.")
            else          -> lines.add("🟢 Relapse risk is LOW ($riskPct%). Keep up this pattern.")
        }

        // Time of day insight
        when {
            hour in 22..23 || hour in 0..4 ->
                lines.add("🌙 It is ${hour}:00 — late night usage is the #1 cause of next-day fatigue and increased addiction risk.")
            hour in 5..8 ->
                lines.add("🌅 Morning usage detected. Starting your day on your phone sets a distracted tone — try a 30-min phone-free morning.")
            hour in 13..15 ->
                lines.add("☀️ Afternoon slump detected. This is a common relapse window — stay mindful.")
        }

        // Cluster insight
        when (data.cluster) {
            "Night Owl"      -> lines.add("👤 Profile: Night Owl — your peak usage is after 10 PM. This pattern disrupts sleep and worsens addiction scores over time.")
            "Binge User"     -> lines.add("👤 Profile: Binge User — you use your phone in long uninterrupted bursts. The AI flagged your session length as abnormal.")
            "Impulsive User" -> lines.add("👤 Profile: Impulsive User — you pick up your phone frequently without clear intent. Your $switches app switches today confirm this.")
        }

        // Top SHAP factor
        val topFactor = data.top_factor
        lines.add("🔍 SHAP Analysis: The #1 reason for your classification is '${topFactor.replace('_',' ')}'. This factor had the highest impact on the AI's decision.")

        // Binge
        if (data.is_binge_session) {
            lines.add("🚨 Isolation Forest flagged this as a BINGE session — your usage pattern is statistically abnormal compared to healthy users.")
        }

        // Weekly forecast
        lines.add("📅 At this rate, you will spend ${data.weekly_screen_time_hours}h on your phone this week.")

        return lines.joinToString("\n\n")
    }

    // ── Load from SharedPrefs (offline fallback) ──────────────
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

        val monsterEmoji = when (monster) {
            0 -> "😴"; 1 -> "😐"; 2 -> "😤"; 3 -> "😡"; 4 -> "👹"; 5 -> "💀"; else -> "😴"
        }
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
        "focused"    -> 0xFF00FF88.toInt()
        "distracted" -> 0xFFFFAA00.toInt()
        "addicted"   -> 0xFFFF4444.toInt()
        else         -> 0xFFFFFFFF.toInt()
    }
    private fun scoreColor(score: Int) = when {
        score >= 70 -> 0xFF00FF88.toInt()
        score >= 40 -> 0xFFFFAA00.toInt()
        else        -> 0xFFFF4444.toInt()
    }
    private fun riskColor(riskPct: Int) = when {
        riskPct < 40 -> 0xFF00FF88.toInt()
        riskPct < 70 -> 0xFFFFAA00.toInt()
        else         -> 0xFFFF4444.toInt()
    }

    private fun hasUsagePermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode   = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }
}