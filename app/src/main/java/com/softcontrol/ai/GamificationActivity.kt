package com.softcontrol.ai

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import com.softcontrol.ai.databinding.ActivityGamificationBinding
import kotlinx.coroutines.launch

/**
 * Feature 4: Advanced Gamification System
 * Shows XP, level, streak, badges, and daily/weekly missions.
 */
class GamificationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGamificationBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGamificationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnBack.setOnClickListener { finish() }
        loadGamificationData()
    }

    override fun onResume() {
        super.onResume()
        loadGamificationData()
    }

    private fun loadGamificationData() {
        // Local gamification state
        val totalXP = GamificationManager.getTotalXP(this)
        val level   = GamificationManager.getLevel(totalXP)
        val name    = GamificationManager.getLevelName(level)
        val streak  = GamificationManager.getStreak(this)
        val progress= GamificationManager.getXpProgress(totalXP)
        val nextXP  = GamificationManager.getXpForNextLevel(totalXP)
        val badges  = GamificationManager.getBadges(this)

        // Header stats
        binding.tvXpTotal.text   = "$totalXP XP"
        binding.tvLevel.text     = "Level $level — $name"
        binding.tvStreak.text    = "$streak day streak"
        binding.progressXP.progress = (progress * 100).toInt()
        binding.tvXpProgress.text =
            if (nextXP == Int.MAX_VALUE) "MAX LEVEL"
            else "$totalXP / $nextXP XP to Level ${level + 1}"

        // Badges
        buildBadgeSection(badges)

        // Fetch missions from backend
        val userId = UserProfileManager.getUserId(this)
        lifecycleScope.launch {
            try {
                val response = RetrofitClient.instance.getMissions(userId)
                if (response.isSuccessful) {
                    response.body()?.let { missionsData ->
                        runOnUiThread {
                            buildMissions(missionsData.daily, missionsData.weekly)
                        }
                    }
                } else {
                    runOnUiThread { buildOfflineMissions() }
                }
            } catch (_: Exception) {
                runOnUiThread { buildOfflineMissions() }
            }
        }
    }

    private fun buildBadgeSection(badges: List<String>) {
        val container = binding.llBadges
        container.removeAllViews()
        if (badges.isEmpty()) {
            val tv = TextView(this).apply {
                text = "No badges yet — complete focus sessions to earn them!"
                setTextColor(0xFF9E9EC8.toInt()); textSize = 13f
            }
            container.addView(tv)
            return
        }
        // Show badges in a horizontal-wrapping row
        var row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 4)
        }
        badges.forEachIndexed { idx, badge ->
            val tv = TextView(this).apply {
                text = badge; textSize = 12f; setTextColor(0xFFFFFFFF.toInt())
                setPadding(12, 8, 12, 8)
                background = resources.getDrawable(android.R.drawable.dialog_holo_dark_frame, null)
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 8, 8) }
            row.addView(tv, lp)
            if ((idx + 1) % 2 == 0) {
                container.addView(row)
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; setPadding(0, 4, 0, 4)
                }
            }
        }
        if (row.childCount > 0) container.addView(row)
    }

    private fun buildMissions(daily: List<MissionItem>, weekly: List<MissionItem>) {
        val container = binding.llMissions
        container.removeAllViews()

        addMissionGroupHeader(container, "Daily Missions")
        if (daily.isEmpty()) {
            addMissionEmpty(container, "No daily missions — check back tomorrow!")
        } else {
            daily.forEach { addMissionCard(container, it) }
        }

        addMissionGroupHeader(container, "Weekly Missions")
        if (weekly.isEmpty()) {
            addMissionEmpty(container, "No weekly missions available.")
        } else {
            weekly.forEach { addMissionCard(container, it) }
        }
    }

    private fun buildOfflineMissions() {
        val container = binding.llMissions
        container.removeAllViews()
        val tv = TextView(this).apply {
            text = "Connect to server to see your missions!"
            setTextColor(0xFF9E9EC8.toInt()); textSize = 13f; setPadding(0, 12, 0, 0)
        }
        container.addView(tv)
    }

    private fun addMissionGroupHeader(parent: LinearLayout, title: String) {
        val tv = TextView(this).apply {
            text = title; textSize = 15f
            setTextColor(0xFF60A5FA.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 16, 0, 8)
        }
        parent.addView(tv)
    }

    private fun addMissionEmpty(parent: LinearLayout, msg: String) {
        val tv = TextView(this).apply {
            text = msg; textSize = 13f; setTextColor(0xFF9E9EC8.toInt()); setPadding(0, 4, 0, 8)
        }
        parent.addView(tv)
    }

    private fun addMissionCard(parent: LinearLayout, m: MissionItem) {
        val card = CardView(this).apply {
            radius = 12f; cardElevation = 4f
            setCardBackgroundColor(if (m.completed == 1) 0xFF0D2D0D.toInt() else 0xFF1A1A2E.toInt())
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 10) }
            layoutParams = lp
        }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(16, 14, 16, 14)
        }

        // Title row
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tvTitle = TextView(this).apply {
            text = if (m.completed == 1) "✅ ${m.mission_title}" else "🎯 ${m.mission_title}"
            textSize = 14f
            setTextColor(if (m.completed == 1) 0xFF00FF88.toInt() else 0xFFFFFFFF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvXP = TextView(this).apply {
            text = "+${m.xp_reward} XP"; textSize = 13f; setTextColor(0xFFFACC15.toInt())
        }
        titleRow.addView(tvTitle); titleRow.addView(tvXP)
        inner.addView(titleRow)

        // Description
        val tvDesc = TextView(this).apply {
            text = m.mission_desc; textSize = 12f; setTextColor(0xFF9E9EC8.toInt())
            setPadding(0, 4, 0, 6)
        }
        inner.addView(tvDesc)

        // Progress bar
        if (m.completed == 0 && m.target > 1) {
            val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = m.target; progress = m.progress
                progressTintList = android.content.res.ColorStateList.valueOf(0xFF7C3AED.toInt())
            }
            inner.addView(pb)
            val tvProg = TextView(this).apply {
                text = "${m.progress}/${m.target}"; textSize = 11f; setTextColor(0xFF9E9EC8.toInt())
                setPadding(0, 2, 0, 0)
            }
            inner.addView(tvProg)
        }

        card.addView(inner); parent.addView(card)
    }
}