package com.softcontrol.ai

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.softcontrol.ai.databinding.ActivityAnalyticsDashboardBinding
import kotlinx.coroutines.launch

/**
 * Feature 7: Advanced Analytics Dashboard
 * Shows weekly/monthly trends, peak distraction hours, and behavior summaries.
 */
class AnalyticsDashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAnalyticsDashboardBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAnalyticsDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnBack.setOnClickListener { finish() }
        binding.btnRefresh.setOnClickListener { loadAnalytics() }
        loadAnalytics()
    }

    private fun loadAnalytics() {
        val userId = UserProfileManager.getUserId(this)
        binding.tvLoading.visibility = View.VISIBLE
        binding.scrollContent.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val response = RetrofitClient.instance.getAnalytics(userId)
                runOnUiThread {
                    binding.tvLoading.visibility = View.GONE
                    binding.scrollContent.visibility = View.VISIBLE

                    if (response.isSuccessful) {
                        response.body()?.let { data ->
                            updateSummaryCards(data.summary)
                            buildScreenTimeTrendChart(data.trends)
                            buildScoreTrendChart(data.trends)
                            buildPeakHoursChart(data.peak_hours)
                        }
                    } else {
                        Toast.makeText(this@AnalyticsDashboardActivity,
                            "Failed to load analytics", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.tvLoading.text = "Cannot connect to server.\nMake sure backend is running."
                    Toast.makeText(this@AnalyticsDashboardActivity,
                        "Server unreachable", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateSummaryCards(summary: AnalyticsSummary) {
        binding.tvTotalSessions.text    = "${summary.total_sessions} sessions"
        binding.tvCompletedSessions.text= "${summary.completed_sessions} completed"
        binding.tvAvgScore.text         = "Avg Score: ${summary.avg_score}"
        binding.tvAvgRisk.text          = "Avg Risk: ${(summary.avg_risk * 100).toInt()}%"
        binding.tvTotalViolations.text  = "Total Violations: ${summary.total_violations}"
        binding.tvTotalScreenTime.text  =
            "Total Screen Time: ${(summary.total_screen_time_min / 60).toInt()}h ${(summary.total_screen_time_min % 60).toInt()}m"
        val peakHour = summary.peak_distraction_hour
        binding.tvPeakHour.text         =
            "Peak Distraction: ${peakHour}:00-${peakHour+1}:00"
        binding.tvDailyAvg.text         =
            "Daily Avg: ${summary.avg_daily_time_min.toInt()} min"
    }

    private fun buildScreenTimeTrendChart(trends: List<DayTrend>) {
        if (trends.isEmpty()) {
            binding.chartScreenTime.setNoDataText("No trend data yet — complete more sessions")
            return
        }
        val entries  = trends.mapIndexed { i, t -> Entry(i.toFloat(), (t.total_time / 60f)) }
        val labels   = trends.map { it.day.takeLast(5) }   // "MM-DD"
        val dataSet  = LineDataSet(entries, "Screen Time (hours)").apply {
            color       = Color.parseColor("#7C3AED")
            setCircleColor(Color.parseColor("#A78BFA"))
            lineWidth   = 2.5f; circleRadius = 4f
            valueTextColor = Color.WHITE; valueTextSize = 9f
        }
        binding.chartScreenTime.apply {
            data = LineData(dataSet)
            xAxis.apply {
                valueFormatter = IndexAxisValueFormatter(labels)
                position        = XAxis.XAxisPosition.BOTTOM
                textColor       = Color.parseColor("#9E9EC8")
                granularity     = 1f; labelRotationAngle = -30f
                setDrawGridLines(false)
            }
            axisLeft.apply  { textColor = Color.WHITE; gridColor = Color.parseColor("#2A2A3E") }
            axisRight.isEnabled = false
            legend.textColor    = Color.WHITE
            description.isEnabled = false
            setBackgroundColor(Color.parseColor("#1A1A2E"))
            animateX(500)
            invalidate()
        }
    }

    private fun buildScoreTrendChart(trends: List<DayTrend>) {
        if (trends.isEmpty()) return
        val entries  = trends.mapIndexed { i, t -> Entry(i.toFloat(), t.avg_score) }
        val labels   = trends.map { it.day.takeLast(5) }
        val dataSet  = LineDataSet(entries, "Avg Self-Control Score").apply {
            color       = Color.parseColor("#00FF88")
            setCircleColor(Color.parseColor("#34D399"))
            lineWidth   = 2.5f; circleRadius = 4f
            fillAlpha   = 50; fillColor = Color.parseColor("#00FF88"); setDrawFilled(true)
            valueTextColor = Color.WHITE; valueTextSize = 9f
        }
        binding.chartScore.apply {
            data = LineData(dataSet)
            xAxis.apply {
                valueFormatter = IndexAxisValueFormatter(labels)
                position = XAxis.XAxisPosition.BOTTOM; textColor = Color.parseColor("#9E9EC8")
                granularity = 1f; labelRotationAngle = -30f; setDrawGridLines(false)
            }
            axisLeft.apply { textColor = Color.WHITE; axisMinimum = 0f; axisMaximum = 100f }
            axisRight.isEnabled = false
            legend.textColor    = Color.WHITE
            description.isEnabled = false
            setBackgroundColor(Color.parseColor("#1A1A2E"))
            animateX(500); invalidate()
        }
    }

    private fun buildPeakHoursChart(peakHours: List<PeakHour>) {
        if (peakHours.isEmpty()) {
            binding.chartPeakHours.setNoDataText("Not enough data to show peak hours")
            return
        }
        val entries = peakHours.mapIndexed { i, ph -> BarEntry(i.toFloat(), ph.count.toFloat()) }
        val labels  = peakHours.map { "${it.hour_of_day}:00" }
        val dataSet = BarDataSet(entries, "Distraction Count by Hour").apply {
            color = Color.parseColor("#FF4444")
            valueTextColor = Color.WHITE; valueTextSize = 9f
        }
        binding.chartPeakHours.apply {
            data = BarData(dataSet)
            xAxis.apply {
                valueFormatter = IndexAxisValueFormatter(labels)
                position = XAxis.XAxisPosition.BOTTOM; textColor = Color.parseColor("#9E9EC8")
                granularity = 1f; setDrawGridLines(false)
            }
            axisLeft.apply { textColor = Color.WHITE }
            axisRight.isEnabled = false
            legend.textColor    = Color.WHITE
            description.isEnabled = false
            setBackgroundColor(Color.parseColor("#1A1A2E"))
            animateY(500); invalidate()
        }
    }
}