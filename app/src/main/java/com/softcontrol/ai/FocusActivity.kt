package com.softcontrol.ai

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.softcontrol.ai.databinding.ActivityFocusBinding
import kotlinx.coroutines.*
import java.util.Calendar

class FocusActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFocusBinding
    private var timeLeftSeconds = 25 * 60
    private var violations = 0
    private var isRunning = false
    private var timer: CountDownTimer? = null
    private var appSwitches = 0
    private var autoDetectJob: Job? = null

    // Tracks which apps caused violations
    private val violationAppLog = mutableListOf<String>()

    // Distraction apps — auto-violation triggered if any are opened
    private val distractionApps = setOf(
        "com.instagram.android",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.google.android.youtube",
        "com.snapchat.android",
        "com.facebook.katana",
        "com.twitter.android",
        "com.reddit.frontpage",
        "com.netflix.mediaclient",
        "com.whatsapp",
        "org.telegram.messenger",
        "com.discord"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFocusBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Clear previous session's violation log
        getSharedPreferences("softcontrol", Context.MODE_PRIVATE)
            .edit().putString("violation_apps", "").apply()

        updateTimerDisplay()
        updateViolationDisplay()

        binding.btnStartStop.setOnClickListener {
            if (isRunning) stopTimer() else startTimer()
        }
        binding.btnViolation.setOnClickListener { addViolation(manual = true) }
        binding.btnFinish.setOnClickListener { finishSession() }
        binding.btnBack.setOnClickListener { finish() }
    }

    // ── Timer ──────────────────────────────────────────────────
    private fun startTimer() {
        isRunning = true
        binding.btnStartStop.text = "⏸ PAUSE"
        binding.btnStartStop.backgroundTintList =
            android.content.res.ColorStateList.valueOf(0xFFDC2626.toInt())

        timer = object : CountDownTimer(timeLeftSeconds * 1000L, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                timeLeftSeconds = (millisUntilFinished / 1000).toInt()
                updateTimerDisplay()
            }
            override fun onFinish() {
                timeLeftSeconds = 0
                updateTimerDisplay()
                isRunning = false
                binding.btnStartStop.text = "▶ START"
                stopAutoDetection()
                Toast.makeText(
                    this@FocusActivity,
                    "🎉 Focus session complete! Great job!",
                    Toast.LENGTH_LONG
                ).show()
                NotificationHelper.sendSessionCompleteNotification(this@FocusActivity)
                saveAndAnalyze(focusCompleted = true)
            }
        }.start()

        if (hasUsagePermission()) {
            startAutoDetection()
            binding.tvStatus.text = "🟢 Focused — Auto-detection ON"
        } else {
            binding.tvStatus.text = "🟢 Focused — Manual mode"
        }
    }

    private fun stopTimer() {
        timer?.cancel()
        isRunning = false
        stopAutoDetection()
        binding.btnStartStop.text = "▶ RESUME"
        binding.btnStartStop.backgroundTintList =
            android.content.res.ColorStateList.valueOf(0xFF059669.toInt())
        appSwitches++
    }

    // ── Auto Detection — polls every 5 seconds ─────────────────
    private fun startAutoDetection() {
        autoDetectJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isRunning) {
                delay(5000L)
                val foregroundApp = getForegroundApp()
                if (foregroundApp != null && foregroundApp in distractionApps) {
                    withContext(Dispatchers.Main) {
                        val appName = getAppName(foregroundApp)
                        Toast.makeText(
                            this@FocusActivity,
                            "🚨 Auto-detected: $appName opened!",
                            Toast.LENGTH_SHORT
                        ).show()
                        addViolation(manual = false, appName = appName)
                    }
                    delay(10000L)  // 10 sec cooldown after auto-violation
                }
            }
        }
    }

    private fun stopAutoDetection() {
        autoDetectJob?.cancel()
        autoDetectJob = null
    }

    private fun getForegroundApp(): String? {
        return try {
            val usm   = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now   = System.currentTimeMillis()
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                now - 10000L,
                now
            )
            stats?.filter { it.lastTimeUsed > 0 }
                ?.maxByOrNull { it.lastTimeUsed }
                ?.packageName
        } catch (e: Exception) { null }
    }

    private fun getAppName(packageName: String): String = when (packageName) {
        "com.instagram.android"      -> "Instagram"
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill"   -> "TikTok"
        "com.google.android.youtube" -> "YouTube"
        "com.snapchat.android"       -> "Snapchat"
        "com.facebook.katana"        -> "Facebook"
        "com.twitter.android"        -> "Twitter/X"
        "com.reddit.frontpage"       -> "Reddit"
        "com.netflix.mediaclient"    -> "Netflix"
        "com.whatsapp"               -> "WhatsApp"
        "org.telegram.messenger"     -> "Telegram"
        "com.discord"                -> "Discord"
        else                         -> packageName
    }

    // ── Violation Logic ────────────────────────────────────────
    private fun addViolation(manual: Boolean, appName: String = "") {
        violations++
        appSwitches++

        // Log the violation with app name for the report
        val logEntry = if (manual) "Manual#$violations" else appName
        violationAppLog.add(logEntry)
        getSharedPreferences("softcontrol", Context.MODE_PRIVATE)
            .edit().putString("violation_apps", violationAppLog.joinToString(", ")).apply()

        updateViolationDisplay()

        // Send notification for every violation
        NotificationHelper.sendViolationNotification(this, violations, if (manual) "" else appName)

        // Punishment mode at 2+ violations
        if (violations >= 2) triggerPunishmentMode()

        // Session failed at 3 violations
        if (violations >= 3) {
            Toast.makeText(this, "💀 SESSION FAILED! Too many violations!", Toast.LENGTH_LONG).show()
            timer?.cancel()
            isRunning = false
            stopAutoDetection()
            saveAndAnalyze(focusCompleted = false)
        }
    }

    // ── Red screen flash + vibration ──────────────────────────
    private fun triggerPunishmentMode() {
        binding.root.setBackgroundColor(0xFFFF0000.toInt())
        Handler(Looper.getMainLooper()).postDelayed({
            binding.root.setBackgroundColor(0xFF0D0D1A.toInt())
        }, 600)

        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(600, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        }
    }

    // ── UI Updates ─────────────────────────────────────────────
    private fun updateViolationDisplay() {
        binding.tvViolations.text = "Violations: $violations / 3"
        binding.tvViolations.setTextColor(
            when {
                violations == 0 -> 0xFF00FF88.toInt()
                violations == 1 -> 0xFFFFAA00.toInt()
                violations == 2 -> 0xFFFF6600.toInt()
                else            -> 0xFFFF0000.toInt()
            }
        )
        binding.tvStatus.text = when {
            violations == 0 -> if (isRunning && hasUsagePermission())
                "🟢 Focused — Auto-detection ON" else "🟢 Focused"
            violations == 1 -> "🟡 Warning — 1 violation"
            violations == 2 -> "🟠 Danger! — 2 violations"
            else            -> "🔴 FAILED"
        }
    }

    private fun updateTimerDisplay() {
        val minutes = timeLeftSeconds / 60
        val seconds = timeLeftSeconds % 60
        binding.tvTimer.text = String.format("%02d:%02d", minutes, seconds)
    }

    // ── Session End ────────────────────────────────────────────
    private fun finishSession() {
        timer?.cancel()
        stopAutoDetection()
        saveAndAnalyze(focusCompleted = violations < 3)
        finish()
    }

    private fun saveAndAnalyze(focusCompleted: Boolean) {
        val prefs     = getSharedPreferences("softcontrol", Context.MODE_PRIVATE)
        val timeSpent = ((25 * 60 - timeLeftSeconds) / 60f).coerceAtLeast(1f)

        prefs.edit()
            .putFloat("time_spent",  timeSpent)
            .putInt("violations",    violations)
            .putInt("app_switches",  appSwitches)
            .apply()

        val hour    = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val request = AnalyzeRequest(
            time_spent      = timeSpent,
            app_switches    = appSwitches,
            hour_of_day     = hour,
            violations      = violations,
            focus_completed = focusCompleted
        )

        lifecycleScope.launch {
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
                            .putString("top_factor",    data.top_factor)
                            .putInt("monster_level",    data.monster_level)
                            .putFloat("weekly_hours",   data.weekly_screen_time_hours)
                            .apply()
                        Toast.makeText(
                            this@FocusActivity,
                            "✅ AI Analysis complete! Check your report.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                // Server unreachable — data already saved locally
            }
        }
    }

    // ── Permission Check ───────────────────────────────────────
    private fun hasUsagePermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode   = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    override fun onDestroy() {
        super.onDestroy()
        timer?.cancel()
        stopAutoDetection()
    }
}