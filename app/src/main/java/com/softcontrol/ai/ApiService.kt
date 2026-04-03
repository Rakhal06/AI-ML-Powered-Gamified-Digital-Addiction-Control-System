package com.softcontrol.ai

import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

// ── Request models ──────────────────────────────────────────

data class AnalyzeRequest(
    val time_spent: Float,
    val app_switches: Int,
    val hour_of_day: Int,
    val violations: Int,
    val focus_completed: Boolean = false,
    val user_id: String = "global",
    val display_name: String = "Player",
    val location_type: String = "other",        // Feature 1: "home" | "college" | "other"
    val day_type: String = "weekday",            // Feature 1: "weekday" | "weekend"
    val battery_level: Int = 100,               // Feature 1
    val headphone_connected: Boolean = false,   // Feature 1
    val streak: Int = 0
)

data class SequenceRequest(val sessions: List<Map<String, Any>>)

data class InterventionRequest(
    val user_id: String,
    val risk_score: Float,
    val hour_of_day: Int,
    val violations: Int,
    val streak: Int = 0,
    val recent_sessions: List<Map<String, Any>> = emptyList()
)

data class TrainingDataRequest(
    val user_id: String,
    val sessions: List<Map<String, Any>>
)

data class UpdateScoreRequest(
    val user_id: String,
    val display_name: String,
    val focus_score: Int,
    val xp_earned: Int
)

data class RLFeedbackRequest(
    val user_id: String,
    val action: Int,
    val state: Map<String, Float>,
    val next_state: Map<String, Float>,
    val reward: Float
)

// Feature 0: Continuous app usage tracking
data class AppLogEntry(
    val package_name: String,
    val app_name: String,
    val duration_minutes: Float,
    val category: String
)

data class UsageLogRequest(
    val user_id: String,
    val app_logs: List<AppLogEntry>
)

// ── Response models ─────────────────────────────────────────

data class Remarks(val good: List<String>, val bad: List<String>)

data class AnalyzeResponse(
    val label: String,
    val ensemble_label: String,
    val confidence: Float,
    val risk_score: Float,
    val self_control_score: Int,
    val monster_level: Int,
    val cluster: String,
    val cluster_id: Int,
    val is_binge_session: Boolean,
    val anomaly_score: Float,
    val explanations: Map<String, Float>?,
    val top_factor: String,
    val insight: String,
    val coach_tip: String,
    val remarks: Remarks,
    val weekly_screen_time_hours: Float,
    val xp_earned: Int = 0,
    val lstm_risk: Float = 0f,
    val lstm_trend: String = "stable",
    val rl_action: String = "none",
    val rl_message: String? = null,
    val used_personal_model: Boolean = false
)

data class SequenceResponse(val lstm_risk: Float, val trend: String, val sessions_analyzed: Int)

data class InterventionResponse(
    val intervene: Boolean,
    val short_term_risk: Float,
    val threshold: Float,
    val action: String,
    val message: String?
)

data class LeaderboardEntry(
    val user_id: String,
    val display_name: String,
    val focus_score: Int,
    val weekly_xp: Int,
    val rank: Int
)

data class LeaderboardResponse(val leaderboard: List<LeaderboardEntry>)

data class UserRankProfile(
    val user_id: String?,
    val display_name: String?,
    val focus_score: Int?,
    val weekly_xp: Int?
)
data class UserRankResponse(val rank: Int, val profile: UserRankProfile?)

data class AnalyticsSummary(
    val total_sessions: Int,
    val completed_sessions: Int,
    val avg_score: Float,
    val avg_risk: Float,
    val total_violations: Int,
    val total_screen_time_min: Float,
    val peak_distraction_hour: Int,
    val avg_daily_time_min: Float
)

data class DayTrend(
    val day: String,
    val total_time: Float,
    val avg_score: Float,
    val total_violations: Int,
    val session_count: Int
)

data class PeakHour(
    val hour_of_day: Int,
    val count: Int,
    val avg_risk: Float,
    val avg_violations: Float
)

data class AnalyticsResponse(
    val sessions: List<Map<String, Any>>,
    val summary: AnalyticsSummary,
    val trends: List<DayTrend> = emptyList(),
    val peak_hours: List<PeakHour> = emptyList()
)

data class RLActionResponse(val action: Int, val action_name: String, val message: String?)

// Feature 4: Missions
data class MissionItem(
    val id: Int,
    val mission_key: String,
    val mission_title: String,
    val mission_desc: String,
    val xp_reward: Int,
    val target: Int,
    val progress: Int,
    val completed: Int,
    val mission_type: String
)
data class MissionsResponse(
    val daily: List<MissionItem>,
    val weekly: List<MissionItem>
)

data class UsageTrendsResponse(
    val trends: List<DayTrend>,
    val peak_hours: List<PeakHour>
)

// ── API interface ───────────────────────────────────────────

interface ApiService {
    @POST("analyze")
    suspend fun analyze(@Body request: AnalyzeRequest): Response<AnalyzeResponse>

    @POST("predict_sequence")
    suspend fun predictSequence(@Body request: SequenceRequest): Response<SequenceResponse>

    @POST("check_intervention")
    suspend fun checkIntervention(@Body request: InterventionRequest): Response<InterventionResponse>

    @POST("submit_training_data")
    suspend fun submitTrainingData(@Body request: TrainingDataRequest): Response<Map<String, Any>>

    @POST("log_usage")
    suspend fun logUsage(@Body request: UsageLogRequest): Response<Map<String, Any>>

    @GET("leaderboard")
    suspend fun getLeaderboard(@Query("limit") limit: Int = 50): Response<LeaderboardResponse>

    @GET("user_rank/{userId}")
    suspend fun getUserRank(@Path("userId") userId: String): Response<UserRankResponse>

    @POST("update_score")
    suspend fun updateScore(@Body request: UpdateScoreRequest): Response<Map<String, Any>>

    @GET("analytics/{userId}")
    suspend fun getAnalytics(@Path("userId") userId: String): Response<AnalyticsResponse>

    @GET("usage_trends/{userId}")
    suspend fun getUsageTrends(
        @Path("userId") userId: String,
        @Query("days") days: Int = 7
    ): Response<UsageTrendsResponse>

    @GET("missions/{userId}")
    suspend fun getMissions(@Path("userId") userId: String): Response<MissionsResponse>

    @GET("rl_action")
    suspend fun getRLAction(
        @Query("risk") risk: Float,
        @Query("hour") hour: Int,
        @Query("violations") violations: Int,
        @Query("streak") streak: Int = 0
    ): Response<RLActionResponse>

    @POST("rl_feedback")
    suspend fun sendRLFeedback(@Body request: RLFeedbackRequest): Response<Map<String, Any>>

    @POST("retrain_lstm")
    suspend fun retrainLSTM(@Body body: Map<String, String>): Response<Map<String, Any>>
}

// ── Retrofit client ─────────────────────────────────────────

object RetrofitClient {
    // ⚠️ Change this to your PC's WiFi IP (run ipconfig in CMD)
    private const val BASE_URL = "http://10.255.231.101:5000/"

    val instance: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}