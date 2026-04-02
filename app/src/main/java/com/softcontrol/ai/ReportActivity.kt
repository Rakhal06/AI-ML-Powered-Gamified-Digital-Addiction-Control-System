package com.softcontrol.ai

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.softcontrol.ai.databinding.ActivityReportBinding
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class ReportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReportBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Save today's session to history before displaying
        saveTodayToHistory()
        loadReport()
        binding.btnBack.setOnClickListener { finish() }
    }

    // ── Save today's data to a rolling 7-day history ──────────
    private fun saveTodayToHistory() {
        val prefs      = getSharedPreferences("softcontrol", Context.MODE_PRIVATE)
        val label      = prefs.getString("last_label",  "focused") ?: "focused"
        val score      = prefs.getInt("last_score",      100)
        val violations = prefs.getInt("violations",      0)
        val timeSpent  = prefs.getFloat("time_spent",    0f)
        val risk       = prefs.getFloat("last_risk",     0f)
        val violationApps = prefs.getString("violation_apps", "") ?: ""

        val historyJson = prefs.getString("session_history", "[]") ?: "[]"
        val history     = JSONArray(historyJson)

        val today = SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date())

        // Check if we already saved today
        if (history.length() > 0) {
            val last = history.getJSONObject(history.length() - 1)
            if (last.getString("date") == today) return  // already saved
        }

        val entry = JSONObject().apply {
            put("date",        today)
            put("label",       label)
            put("score",       score)
            put("violations",  violations)
            put("time_spent",  timeSpent.toInt())
            put("risk",        (risk * 100).toInt())
            put("apps",        violationApps)
        }

        history.put(entry)

        // Keep only last 7 days
        val trimmed = JSONArray()
        val start   = maxOf(0, history.length() - 7)
        for (i in start until history.length()) trimmed.put(history.get(i))

        prefs.edit().putString("session_history", trimmed.toString()).apply()
    }

    private fun loadReport() {
        val prefs      = getSharedPreferences("softcontrol", Context.MODE_PRIVATE)
        val label      = prefs.getString("last_label",  "focused") ?: "focused"
        val score      = prefs.getInt("last_score",      100)
        val risk       = prefs.getFloat("last_risk",     0f)
        val insight    = prefs.getString("last_insight", "No analysis yet.") ?: ""
        val tip        = prefs.getString("last_tip",     "Complete a session first.") ?: ""
        val cluster    = prefs.getString("last_cluster", "—") ?: "—"
        val weekly     = prefs.getFloat("weekly_hours",  0f)
        val violations = prefs.getInt("violations",      0)
        val monsterLvl = prefs.getInt("monster_level",   0)
        val timeSpent  = prefs.getFloat("time_spent",    0f)
        val violationApps = prefs.getString("violation_apps", "") ?: ""

        // ── Status ────────────────────────────────────────────
        binding.tvStatusLabel.text = label.uppercase()
        binding.tvStatusLabel.setTextColor(colorForLabel(label))

        // ── Scores ────────────────────────────────────────────
        binding.tvScoreValue.text = score.toString()
        binding.tvScoreValue.setTextColor(colorForScore(score))

        val riskPct = (risk * 100).toInt()
        binding.tvRiskValue.text = "$riskPct%"
        binding.tvRiskValue.setTextColor(colorForRisk(riskPct))

        // ── Stats ─────────────────────────────────────────────
        binding.tvClusterValue.text    = cluster
        binding.tvWeeklyValue.text     = "${weekly}h / week"
        binding.tvViolationsValue.text = "$violations violations"
        binding.tvMonsterValue.text    = "Level $monsterLvl / 5"
        binding.tvTimeValue.text       = "${timeSpent.toInt()} minutes"

        // ── Violation Apps ────────────────────────────────────
        if (violationApps.isNotEmpty()) {
            binding.tvViolationApps.text = "Apps opened during violations:\n$violationApps"
        } else {
            binding.tvViolationApps.text = if (violations == 0)
                "No violations — perfect focus! 🏆"
            else
                "Violations recorded (manual mode — app names not tracked)"
        }

        // ── Insight & Tip ─────────────────────────────────────
        binding.tvInsightText.text = insight
        binding.tvTipText.text     = tip

        // ── Remarks ───────────────────────────────────────────
        binding.tvGoodRemarks.text = buildGoodRemarks(label, violations, score)
        binding.tvBadRemarks.text  = buildBadRemarks(label, violations, score, violationApps)

        // ── Timeline ──────────────────────────────────────────
        val historyJson = prefs.getString("session_history", "[]") ?: "[]"
        binding.tvTimeline.text = buildTimeline(historyJson)
    }

    private fun buildTimeline(historyJson: String): String {
        val history = JSONArray(historyJson)
        if (history.length() == 0) return "No history yet. Complete more sessions!"

        val sb = StringBuilder()
        // Show newest first
        for (i in history.length() - 1 downTo 0) {
            val e          = history.getJSONObject(i)
            val date       = e.getString("date")
            val lbl        = e.getString("label")
            val sc         = e.getInt("score")
            val viol       = e.getInt("violations")
            val time       = e.getInt("time_spent")
            val riskPct    = e.getInt("risk")
            val apps       = e.optString("apps", "")

            val emoji = when (lbl) {
                "focused"    -> "✅"
                "distracted" -> "🟡"
                "addicted"   -> "🔴"
                else         -> "⚪"
            }

            sb.append("$emoji $date — ${lbl.uppercase()}\n")
            sb.append("   Score: $sc/100  |  Risk: $riskPct%  |  Time: ${time}min\n")

            if (viol > 0) {
                sb.append("   ⚠️ Violations: $viol")
                if (apps.isNotEmpty()) sb.append(" → $apps")
                sb.append("\n")
            } else {
                sb.append("   🏆 Zero violations\n")
            }
            sb.append("\n")
        }
        return sb.toString().trimEnd()
    }

    private fun buildGoodRemarks(label: String, violations: Int, score: Int): String {
        val items = mutableListOf<String>()
        if (label == "focused") items.add("✅ Maintained focused behavior")
        if (violations == 0)    items.add("✅ Zero violations — perfect session")
        if (score >= 70)        items.add("✅ Strong self-control score")
        if (score >= 90)        items.add("✅ Exceptional discipline today!")
        return if (items.isEmpty()) "Complete a focus session to earn positive remarks." else items.joinToString("\n")
    }

    private fun buildBadRemarks(label: String, violations: Int, score: Int, apps: String): String {
        val items = mutableListOf<String>()
        if (label == "addicted")    items.add("❌ Addictive usage pattern detected")
        if (label == "distracted")  items.add("❌ High distraction detected")
        if (violations >= 3)        items.add("❌ Session failed — $violations violations")
        else if (violations == 2)   items.add("⚠️ Close call — 2 violations")
        else if (violations == 1)   items.add("⚠️ 1 violation recorded")
        if (score < 40)             items.add("❌ Low self-control score ($score/100)")
        if (apps.isNotEmpty())      items.add("❌ Distraction apps: $apps")
        return if (items.isEmpty()) "No negative remarks. Keep it up! 🌟" else items.joinToString("\n")
    }

    private fun colorForLabel(label: String) = when (label) {
        "focused"    -> 0xFF00FF88.toInt()
        "distracted" -> 0xFFFFAA00.toInt()
        "addicted"   -> 0xFFFF4444.toInt()
        else         -> 0xFFFFFFFF.toInt()
    }

    private fun colorForScore(score: Int) = when {
        score >= 70 -> 0xFF00FF88.toInt()
        score >= 40 -> 0xFFFFAA00.toInt()
        else        -> 0xFFFF4444.toInt()
    }

    private fun colorForRisk(riskPct: Int) = when {
        riskPct < 40 -> 0xFF00FF88.toInt()
        riskPct < 70 -> 0xFFFFAA00.toInt()
        else         -> 0xFFFF4444.toInt()
    }
}