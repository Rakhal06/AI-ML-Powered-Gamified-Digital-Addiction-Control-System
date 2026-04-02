package com.softcontrol.ai

import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

// ── Data classes ──────────────────────────────────────────

data class AnalyzeRequest(
    val time_spent: Float,
    val app_switches: Int,
    val hour_of_day: Int,
    val violations: Int,
    val focus_completed: Boolean = false
)

data class Remarks(
    val good: List<String>,
    val bad: List<String>
)

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
    val explanations: Map<String, Float>?,   // SHAP values per feature
    val top_factor: String,
    val insight: String,
    val coach_tip: String,
    val remarks: Remarks,
    val weekly_screen_time_hours: Float
)

// ── Retrofit Interface ────────────────────────────────────

interface ApiService {
    @POST("analyze")
    suspend fun analyze(@Body request: AnalyzeRequest): Response<AnalyzeResponse>
}

// ── Retrofit Client ───────────────────────────────────────

object RetrofitClient {
    // ⚠️  CHANGE THIS to your PC's WiFi IP address
    //     Run  ipconfig  in Windows CMD → look for IPv4 Address
    //     Your phone and PC must be on the SAME WiFi network
    private const val BASE_URL = "http://10.255.231.101:5000/"

    val instance: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}