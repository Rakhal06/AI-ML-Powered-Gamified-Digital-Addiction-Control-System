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
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class FocusActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFocusBinding
    private var timeLeftSeconds = 25 * 60
    private var violations      = 0
    private var isRunning       = false
    private var timer: CountDownTimer? = null
    private var appSwitches     = 0
    private var autoDetectJob: Job? = null
    private val violationAppLog = mutableListOf<String>()

    // ── Extended distraction app list ─────────────────────────
    private val socialApps = mapOf(
        "com.instagram.android" to "Instagram", "com.zhiliaoapp.musically" to "TikTok",
        "com.ss.android.ugc.trill" to "TikTok", "com.snapchat.android" to "Snapchat",
        "com.facebook.katana" to "Facebook", "com.twitter.android" to "Twitter/X",
        "com.reddit.frontpage" to "Reddit", "com.pinterest" to "Pinterest",
        "com.linkedin.android" to "LinkedIn", "com.tumblr" to "Tumblr",
        "com.bereal.android" to "BeReal"
    )
    private val entertainmentApps = mapOf(
        "com.google.android.youtube" to "YouTube", "com.netflix.mediaclient" to "Netflix",
        "com.amazon.avod.thirdpartyclient" to "Prime Video", "com.hotstar.android" to "Hotstar",
        "com.jio.media.ondemand" to "JioCinema", "com.sony.liv.android" to "SonyLIV",
        "com.zee5.android" to "ZEE5", "air.tv.twitch.android" to "Twitch",
        "com.spotify.music" to "Spotify", "com.google.android.apps.youtube.music" to "YouTube Music"
    )
    private val messagingApps = mapOf(
        "com.whatsapp" to "WhatsApp", "org.telegram.messenger" to "Telegram",
        "com.discord" to "Discord", "com.facebook.orca" to "Messenger",
        "jp.naver.line.android" to "LINE", "com.viber.voip" to "Viber"
    )
    private val gamingApps = mapOf(
        "com.pubg.imobile" to "PUBG", "com.activision.callofduty.shooter" to "Call of Duty",
        "com.garena.game.freefire" to "Free Fire", "com.mojang.minecraftpe" to "Minecraft",
        "com.supercell.clashofclans" to "Clash of Clans", "com.riotgames.league.wildrift" to "Wild Rift",
        "com.miniclip.eightballpool" to "8 Ball Pool", "com.king.candycrushsaga" to "Candy Crush",
        "com.innersloth.spacemafia" to "Among Us", "com.dts.freefireth" to "Free Fire (TH)"
    )

    private val allDistractionApps: Map<String, String> by lazy {
        socialApps + entertainmentApps + messagingApps + gamingApps
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFocusBinding.inflate(layoutInflater)
        setContentView(binding.root)

        getSharedPreferences("softcontrol", Context.MODE_PRIVATE)
            .edit().putString("violation_apps", "").apply()

        updateTimerDisplay()
        updateViolationDisplay()

        binding.btnStartStop.setOnClickListener { if (isRunning) stopTimer() else startTimer() }
        binding.btnViolation.setOnClickListener { addViolation(manual = true) }
        binding.btnFinish.setOnClickListener    { finishSession() }
        binding.btnBack.setOnClickListener      { finish() }
    }

    // ── Timer ─────────────────────────────────────────────────
    private fun startTimer() {
        isRunning = true
        binding.btnStartStop.text = "PAUSE"
        binding.btnStartStop.backgroundTintList =
            android.content.res.ColorStateList.valueOf(0xFFDC2626.toInt())

        timer = object : CountDownTimer(timeLeftSeconds * 1000L, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                timeLeftSeconds = (millisUntilFinished / 1000).toInt()
                updateTimerDisplay()
            }
            override fun onFinish() {
                timeLeftSeconds = 0; updateTimerDisplay(); isRunning = false
                binding.btnStartStop.text = "START"
                stopAutoDetection()
                Toast.makeText(this@FocusActivity, "Focus session complete!", Toast.LENGTH_LONG).show()
                NotificationHelper.sendSessionCompleteNotification(this@FocusActivity)
                saveAndAnalyze(focusCompleted = true)
            }
        }.start()

        if (hasUsagePermission()) {
            startAutoDetection()
            binding.tvStatus.text = "Focused — Auto-detection ON"
        } else {
            binding.tvStatus.text = "Focused — Manual mode"
        }
    }

    private fun stopTimer() {
        timer?.cancel(); isRunning = false; stopAutoDetection()
        binding.btnStartStop.text = "RESUME"
        binding.btnStartStop.backgroundTintList =
            android.content.res.ColorStateList.valueOf(0xFF059669.toInt())
        appSwitches++
    }

    // ── Auto Detection ─────────────────────────────────────────
    private fun startAutoDetection() {
        autoDetectJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isRunning) {
                delay(5000L)
                val pkg = getForegroundApp()
                if (pkg != null && allDistractionApps.containsKey(pkg)) {
                    val appName = allDistractionApps[pkg] ?: pkg
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@FocusActivity, "Auto-detected: $appName opened!", Toast.LENGTH_SHORT).show()
                        addViolation(manual = false, appName = appName)
                    }
                    delay(10000L)
                }
            }
        }
    }

    private fun stopAutoDetection() { autoDetectJob?.cancel(); autoDetectJob = null }

    private fun getForegroundApp(): String? = try {
        val usm   = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now   = System.currentTimeMillis()
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 10000L, now)
        stats?.filter { it.lastTimeUsed > 0 }?.maxByOrNull { it.lastTimeUsed }?.packageName
    } catch (_: Exception) { null }

    // ── Violation Logic ────────────────────────────────────────
    private fun addViolation(manual: Boolean, appName: String = "") {
        violations++; appSwitches++
        val timeStr  = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val logEntry = if (manual) "Manual tap ($timeStr)" else "$appName ($timeStr)"
        violationAppLog.add(logEntry)
        getSharedPreferences("softcontrol", Context.MODE_PRIVATE)
            .edit().putString("violation_apps", violationAppLog.joinToString(" | ")).apply()
        updateViolationDisplay()
        NotificationHelper.sendViolationNotification(this, violations, if (manual) "" else appName)
        if (violations >= 2) triggerPunishmentMode()
        if (violations >= 3) {
            Toast.makeText(this, "SESSION FAILED! Too many violations!", Toast.LENGTH_LONG).show()
            timer?.cancel(); isRunning = false; stopAutoDetection()
            saveAndAnalyze(focusCompleted = false)
        }
    }

    private fun triggerPunishmentMode() {
        binding.root.setBackgroundColor(0xFFFF0000.toInt())
        Handler(Looper.getMainLooper()).postDelayed({
            binding.root.setBackgroundColor(0xFF0D0D1A.toInt())
        }, 600)
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(600, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    // ── UI Updates ─────────────────────────────────────────────
    private fun updateViolationDisplay() {
        binding.tvViolations.text = "Violations: $violations / 3"
        binding.tvViolations.setTextColor(when {
            violations == 0 -> 0xFF00FF88.toInt()
            violations == 1 -> 0xFFFFAA00.toInt()
            violations == 2 -> 0xFFFF6600.toInt()
            else            -> 0xFFFF0000.toInt()
        })
        binding.tvStatus.text = when {
            violations == 0 -> if (isRunning && hasUsagePermission()) "Focused — Auto-detection ON" else "Focused"
            violations == 1 -> "Warning — 1 violation"
            violations == 2 -> "Danger! — 2 violations"
            else            -> "FAILED"
        }
    }

    private fun updateTimerDisplay() {
        val m = timeLeftSeconds / 60; val s = timeLeftSeconds % 60
        binding.tvTimer.text = String.format("%02d:%02d", m, s)
    }

    // ── Session End ────────────────────────────────────────────
    private fun finishSession() {
        timer?.cancel(); stopAutoDetection()
        saveAndAnalyze(focusCompleted = violations < 3)
        finish()
    }

    private fun saveAndAnalyze(focusCompleted: Boolean) {
        val prefs     = getSharedPreferences("softcontrol", Context.MODE_PRIVATE)
        val timeSpent = ((25 * 60 - timeLeftSeconds) / 60f).coerceAtLeast(1f)
        val hour      = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

        prefs.edit()
            .putFloat("time_spent", timeSpent)
            .putInt("violations",   violations)
            .putInt("app_switches", appSwitches)
            .apply()

        saveSessionToHistory(prefs, timeSpent, focusCompleted, hour)

        // Feature 1: Collect context for analysis
        val ctx    = ContextCollector.getContextSnapshot(this)
        val userId = UserProfileManager.getUserId(this)
        val name   = UserProfileManager.getDisplayName(this)
        val streak = GamificationManager.getStreak(this)

        val request = AnalyzeRequest(
            time_spent          = timeSpent,
            app_switches        = appSwitches,
            hour_of_day         = hour,
            violations          = violations,
            focus_completed     = focusCompleted,
            user_id             = userId,            // Feature 2: Personal model
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
                        prefs.edit()
                            .putString("last_label",  data.label)
                            .putInt("last_score",      data.self_control_score)
                            .putFloat("last_risk",     data.risk_score)
                            .putString("last_insight", data.insight)
                            .putString("last_tip",     data.coach_tip)
                            .putString("last_cluster", data.cluster)
                            .putString("top_factor",   data.top_factor)
                            .putInt("monster_level",   data.monster_level)
                            .putFloat("weekly_hours",  data.weekly_screen_time_hours)
                            .apply()

                        updateHistoryWithAI(prefs, data)

                        // Feature 4: Award XP for focus session
                        val award = GamificationManager.awardXP(
                            this@FocusActivity, data.xp_earned, focusCompleted, violations)
                        val xpMsg = if (data.xp_earned >= 0) "+${data.xp_earned} XP!" else "${data.xp_earned} XP"
                        Toast.makeText(this@FocusActivity,
                            "AI Analysis complete! $xpMsg", Toast.LENGTH_SHORT).show()
                        if (award.leveledUp) {
                            Toast.makeText(this@FocusActivity,
                                "LEVEL UP! Level ${award.newLevel}: ${GamificationManager.getLevelName(award.newLevel)}",
                                Toast.LENGTH_LONG).show()
                        }

                        // TE-3: RL action
                        RLManager.handleRLAction(this@FocusActivity, data.rl_action, data.rl_message, lifecycleScope)
                    }
                }
            } catch (_: Exception) { }
        }
    }

    // ── Session History ────────────────────────────────────────
    private fun saveSessionToHistory(
        prefs: android.content.SharedPreferences,
        timeSpent: Float, focusCompleted: Boolean, hour: Int
    ) {
        val historyJson = prefs.getString("session_history", "[]") ?: "[]"
        val history     = JSONArray(historyJson)
        val now         = System.currentTimeMillis()
        val entry = JSONObject().apply {
            put("id",         now)
            put("date",       SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date(now)))
            put("time",       SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now)))
            put("hour",       hour)
            put("time_spent", timeSpent.toInt())
            put("violations", violations)
            put("apps",       violationAppLog.joinToString(", ").ifEmpty { "None" })
            put("focus_done", focusCompleted)
            put("label",      "pending"); put("score", 0); put("risk", 0)
            put("monster", 0); put("cluster", "—")
        }
        history.put(entry)
        val trimmed = JSONArray()
        val start   = maxOf(0, history.length() - 14)
        for (i in start until history.length()) trimmed.put(history.get(i))
        prefs.edit().putString("session_history", trimmed.toString()).apply()
    }

    private fun updateHistoryWithAI(
        prefs: android.content.SharedPreferences, data: AnalyzeResponse
    ) {
        val historyJson = prefs.getString("session_history", "[]") ?: "[]"
        val history     = JSONArray(historyJson)
        if (history.length() == 0) return
        val last = history.getJSONObject(history.length() - 1)
        last.put("label",   data.label)
        last.put("score",   data.self_control_score)
        last.put("risk",    (data.risk_score * 100).toInt())
        last.put("monster", data.monster_level)
        last.put("cluster", data.cluster)
        prefs.edit().putString("session_history", history.toString()).apply()
    }

    private fun hasUsagePermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode   = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    override fun onDestroy() {
        super.onDestroy(); timer?.cancel(); stopAutoDetection()
    }
}