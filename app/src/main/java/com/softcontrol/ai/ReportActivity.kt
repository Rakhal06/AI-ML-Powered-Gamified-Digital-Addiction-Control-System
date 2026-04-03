package com.softcontrol.ai

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.softcontrol.ai.databinding.ActivityReportBinding
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*

class ReportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReportBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        loadReport()
        binding.btnBack.setOnClickListener { finish() }
    }

    private fun loadReport() {
        val prefs      = getSharedPreferences("softcontrol", Context.MODE_PRIVATE)
        val label      = prefs.getString("last_label",  "focused")   ?: "focused"
        val score      = prefs.getInt("last_score",      100)
        val risk       = prefs.getFloat("last_risk",     0f)
        val insight    = prefs.getString("last_insight", "No analysis yet. Tap Analyze on the dashboard.") ?: ""
        val tip        = prefs.getString("last_tip",     "Complete a focus session first.") ?: ""
        val cluster    = prefs.getString("last_cluster", "—")         ?: "—"
        val weekly     = prefs.getFloat("weekly_hours",  0f)
        val violations = prefs.getInt("violations",      0)
        val monsterLvl = prefs.getInt("monster_level",   0)
        val timeSpent  = prefs.getFloat("time_spent",    0f)
        val violApps   = prefs.getString("violation_apps", "")        ?: ""

        // ── Status ──────────────────────────────────────────
        binding.tvStatusLabel.text = label.uppercase()
        binding.tvStatusLabel.setTextColor(colorForLabel(label))

        // ── Scores ──────────────────────────────────────────
        binding.tvScoreValue.text = score.toString()
        binding.tvScoreValue.setTextColor(colorForScore(score))

        val riskPct = (risk * 100).toInt()
        binding.tvRiskValue.text = "$riskPct%"
        binding.tvRiskValue.setTextColor(colorForRisk(riskPct))

        // ── Stats ────────────────────────────────────────────
        binding.tvClusterValue.text    = cluster
        binding.tvWeeklyValue.text     = "${weekly}h / week"
        binding.tvViolationsValue.text = "$violations violations"
        binding.tvMonsterValue.text    = "Level $monsterLvl / 5"
        binding.tvTimeValue.text       = "${timeSpent.toInt()} minutes"

        // ── Violation Details ────────────────────────────────
        binding.tvViolationApps.text = buildViolationDetails(violations, violApps)

        // ── AI Insight ───────────────────────────────────────
        binding.tvInsightText.text = if (insight.isNotEmpty()) insight
        else "No AI analysis yet. Go to the Dashboard and tap 'Analyze My Behavior'."

        // ── Coach Tip ────────────────────────────────────────
        binding.tvTipText.text = tip

        // ── Remarks ──────────────────────────────────────────
        binding.tvGoodRemarks.text = buildGoodRemarks(label, violations, score, timeSpent.toInt())
        binding.tvBadRemarks.text  = buildBadRemarks(label, violations, score, violApps)

        // ── Session History Timeline ─────────────────────────
        val historyJson = prefs.getString("session_history", "[]") ?: "[]"
        binding.tvTimeline.text = buildTimeline(historyJson)
    }

    // ── Violation details block ──────────────────────────────
    private fun buildViolationDetails(violations: Int, appsRaw: String): String {
        if (violations == 0) return "🏆 Zero violations this session — perfect focus!"

        val sb = StringBuilder()
        sb.appendLine("Total violations: $violations / 3")
        sb.appendLine()

        if (appsRaw.isNotEmpty() && appsRaw != "None") {
            val entries = appsRaw.split(" | ")
            entries.forEachIndexed { idx, entry ->
                val ordinal = when (idx + 1) { 1 -> "1st" ; 2 -> "2nd" ; else -> "${idx+1}th" }
                sb.appendLine("  $ordinal violation → $entry")
            }
            sb.appendLine()
            // Summarise which apps caused violations
            val autoApps = entries.filter { !it.startsWith("Manual") }
            if (autoApps.isNotEmpty()) {
                sb.appendLine("Auto-detected apps:")
                autoApps.forEach { sb.appendLine("  • ${it.substringBefore(" (")}") }
            }
        } else {
            sb.appendLine("  Violations were logged manually (no auto-detection data).")
        }

        return sb.toString().trimEnd()
    }

    // ── Timeline builder ────────────────────────────────────
    private fun buildTimeline(historyJson: String): String {
        val history = JSONArray(historyJson)
        if (history.length() == 0) {
            return "No session history yet.\n\nComplete a focus session to start building your timeline!"
        }

        val sb = StringBuilder()

        // Group by date
        data class Session(
            val date: String, val time: String, val hour: Int,
            val timeSpent: Int, val violations: Int, val apps: String,
            val focusDone: Boolean, val label: String, val score: Int,
            val risk: Int, val monster: Int, val cluster: String
        )

        val sessions = mutableListOf<Session>()
        for (i in 0 until history.length()) {
            val e = history.getJSONObject(i)
            sessions.add(Session(
                date      = e.optString("date",      "Unknown"),
                time      = e.optString("time",      ""),
                hour      = e.optInt("hour",          0),
                timeSpent = e.optInt("time_spent",    0),
                violations= e.optInt("violations",    0),
                apps      = e.optString("apps",       "None"),
                focusDone = e.optBoolean("focus_done",false),
                label     = e.optString("label",      "—"),
                score     = e.optInt("score",          0),
                risk      = e.optInt("risk",           0),
                monster   = e.optInt("monster",        0),
                cluster   = e.optString("cluster",    "—")
            ))
        }

        // Show newest first, grouped by date
        val grouped = sessions.reversed().groupBy { it.date }

        grouped.forEach { (date, daySessions) ->
            // Date header
            val dayEmoji = getDayHealthEmoji(daySessions)
            sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            sb.appendLine("$dayEmoji  $date")
            sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            sb.appendLine()

            daySessions.forEachIndexed { idx, s ->
                val sessionNum = daySessions.size - idx
                val statusIcon = when {
                    s.focusDone  && s.violations == 0 -> "✅"
                    s.focusDone  && s.violations > 0  -> "⚠️"
                    !s.focusDone && s.violations >= 3  -> "❌"
                    else -> "🔄"
                }
                val labelDisplay = when (s.label) {
                    "focused"    -> "FOCUSED 🟢"
                    "distracted" -> "DISTRACTED 🟡"
                    "addicted"   -> "ADDICTED 🔴"
                    "pending"    -> "Analyzing..."
                    else -> s.label.uppercase()
                }

                sb.appendLine("  $statusIcon Session #$sessionNum  [${s.time}]")
                sb.appendLine("     Duration : ${s.timeSpent} min")
                sb.appendLine("     Result   : ${if (s.focusDone) "Completed ✅" else "Failed ❌"}")

                if (s.label != "pending" && s.label != "—") {
                    sb.appendLine("     AI Label : $labelDisplay")
                    sb.appendLine("     Score    : ${s.score}/100")
                    sb.appendLine("     Risk     : ${s.risk}%")
                    sb.appendLine("     Profile  : ${s.cluster}")
                    sb.appendLine("     Monster  : Level ${s.monster}/5")
                }

                // Violations detail
                if (s.violations == 0) {
                    sb.appendLine("     Violations: None 🏆")
                } else {
                    sb.appendLine("     Violations: ${s.violations}")
                    if (s.apps.isNotEmpty() && s.apps != "None") {
                        val appList = s.apps.split(" | ")
                        appList.forEachIndexed { vIdx, app ->
                            val ord = when (vIdx+1){ 1->"1st"; 2->"2nd"; else->"${vIdx+1}th" }
                            sb.appendLine("       → $ord: $app")
                        }
                    }
                }
                sb.appendLine()
            }

            // Day summary
            val totalTime      = daySessions.sumOf { it.timeSpent }
            val totalViolations = daySessions.sumOf { it.violations }
            val completed      = daySessions.count { it.focusDone }
            val avgScore       = if (daySessions.any { it.score > 0 })
                daySessions.filter { it.score > 0 }.map { it.score }.average().toInt() else 0

            sb.appendLine("  📈 Day Summary")
            sb.appendLine("     Sessions   : ${daySessions.size} (${completed} completed)")
            sb.appendLine("     Total time : ${totalTime} min")
            sb.appendLine("     Violations : $totalViolations")
            if (avgScore > 0) sb.appendLine("     Avg Score  : $avgScore/100")
            sb.appendLine()
        }

        return sb.toString().trimEnd()
    }

    private fun getDayHealthEmoji(sessions: List<*>): String {
        // Simple heuristic based on session data
        val list = sessions.filterIsInstance<Any>()
        return when {
            list.isEmpty() -> "📅"
            else -> "📅"  // Could be extended with actual score analysis
        }
    }

    // ── Remarks ─────────────────────────────────────────────
    private fun buildGoodRemarks(label: String, violations: Int, score: Int, timeMin: Int): String {
        val items = mutableListOf<String>()
        if (label == "focused")   items.add("✅ Maintained focused behavior throughout the session")
        if (violations == 0)      items.add("✅ Zero violations — perfect self-control")
        if (score >= 90)          items.add("✅ Exceptional self-control score (${score}/100)")
        else if (score >= 70)     items.add("✅ Strong self-control score (${score}/100)")
        if (timeMin >= 20)        items.add("✅ Completed a substantial focus session (${timeMin} min)")
        if (items.isEmpty())      items.add("Complete a focus session to earn positive remarks.")
        return items.joinToString("\n")
    }

    private fun buildBadRemarks(label: String, violations: Int, score: Int, apps: String): String {
        val items = mutableListOf<String>()
        if (label == "addicted")  items.add("❌ AI classified behavior as ADDICTED — urgent action needed")
        if (label == "distracted") items.add("❌ AI classified behavior as DISTRACTED")
        when {
            violations >= 3 -> items.add("❌ Session failed — ${violations} violations reached the limit")
            violations == 2 -> items.add("⚠️ Near-failure — 2 violations (1 away from failing)")
            violations == 1 -> items.add("⚠️ 1 violation recorded this session")
        }
        if (score in 1..39)   items.add("❌ Self-control score critically low (${score}/100)")
        else if (score in 40..59) items.add("⚠️ Self-control score below average (${score}/100)")

        // Mention specific apps
        if (apps.isNotEmpty() && apps != "None" && violations > 0) {
            val autoApps = apps.split(" | ")
                .filter { !it.startsWith("Manual") }
                .map { it.substringBefore(" (") }
                .distinct()
            if (autoApps.isNotEmpty()) {
                items.add("❌ Opened during focus: ${autoApps.joinToString(", ")}")
            }
        }

        return if (items.isEmpty()) "No negative remarks. Keep it up! 🌟" else items.joinToString("\n")
    }

    // ── Color helpers ────────────────────────────────────────
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