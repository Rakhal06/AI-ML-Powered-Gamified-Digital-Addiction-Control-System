package com.softcontrol.ai

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.softcontrol.ai.databinding.ActivityCoachBinding

class CoachActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCoachBinding
    private val chatHistory = StringBuilder()

    private var label      = "focused"
    private var score      = 100
    private var risk       = 0f
    private var cluster    = "Regular User"
    private var violations = 0
    private var topFactor  = "time_spent"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCoachBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadPrefs()
        appendBot(buildWelcome())

        binding.btnQ1.setOnClickListener { askQuestion("What is my risk level?") }
        binding.btnQ2.setOnClickListener { askQuestion("Give me tips to improve") }
        binding.btnQ3.setOnClickListener { askQuestion("Explain my violations") }
        binding.btnQ4.setOnClickListener { askQuestion("What is my user type?") }

        binding.btnSend.setOnClickListener {
            val msg = binding.etMessage.text.toString().trim()
            if (msg.isEmpty()) {
                Toast.makeText(this, "Type a message first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            askQuestion(msg)
        }

        binding.btnBack.setOnClickListener { finish() }
    }

    private fun loadPrefs() {
        val p   = getSharedPreferences("softcontrol", Context.MODE_PRIVATE)
        label      = p.getString("last_label",   "focused")      ?: "focused"
        score      = p.getInt("last_score",       100)
        risk       = p.getFloat("last_risk",      0f)
        cluster    = p.getString("last_cluster",  "Regular User") ?: "Regular User"
        violations = p.getInt("violations",        0)
        topFactor  = p.getString("top_factor",    "time_spent")   ?: "time_spent"
    }

    private fun askQuestion(msg: String) {
        appendUser(msg)
        binding.etMessage.setText("")
        val reply = generateReply(msg)
        binding.root.postDelayed({ appendBot(reply) }, 400)
    }

    private fun buildWelcome(): String {
        val riskPct = (risk * 100).toInt()
        val riskIcon = if (riskPct > 70) "🔴" else if (riskPct > 40) "🟡" else "🟢"
        val labelIcon = if (label == "addicted") "💀" else if (label == "distracted") "😤" else "✅"
        return "Hi! I'm your AI Coach 🤖\n\n" +
                "Your status:\n" +
                "$labelIcon Behavior: ${label.uppercase()}\n" +
                "📊 Self-Control: $score/100\n" +
                "$riskIcon Relapse Risk: $riskPct%\n" +
                "👤 Profile: $cluster\n" +
                "🔍 Top AI Factor: ${topFactor.replace("_"," ").uppercase()}\n" +
                "⚠️ Violations: $violations\n\n" +
                "Tap a quick button or type your question!"
    }

    private fun generateReply(msg: String): String {
        val lower   = msg.lowercase()
        val riskPct = (risk * 100).toInt()

        return when {
            lower.contains("risk") || lower.contains("relapse") -> {
                val lvl = if (riskPct > 70) "HIGH 🔴" else if (riskPct > 40) "MODERATE 🟡" else "LOW 🟢"
                val advice = if (riskPct > 70)
                    "Put your phone down NOW. 10 min of walking resets your brain."
                else if (riskPct > 40)
                    "Avoid social media tonight. Set phone-down time at 9 PM."
                else "You're managing well! Keep your routine."
                "Relapse Risk: $riskPct% — $lvl\n\n$advice"
            }

            lower.contains("score") || lower.contains("control") -> {
                val fb = if (score >= 80) "Excellent discipline! 💪"
                else if (score >= 60) "Good but improvable. Try one more focus session daily."
                else if (score >= 40) "Below average. Reduce screen time by 30 mins tomorrow."
                else "Critical! Start with just 1 focus session today."
                "Self-Control: $score/100\n\n$fb\n\nFormula: 100 - (violations×10) - usage_factor"
            }

            lower.contains("violation") || lower.contains("broke") || lower.contains("failed") ->
                when {
                    violations == 0 -> "Zero violations today — perfect! 🏆\nKeep this streak going!"
                    violations == 1 -> "1 violation. Next time: ask 'Is this urgent?' before picking up your phone."
                    violations == 2 -> "2 violations — danger zone!\nFix: Use Do Not Disturb + phone face-down."
                    else -> "3+ violations — session failed 😔\nPlan:\n• Start with 10-min sessions\n• Remove distraction apps from home screen\n• Use grayscale mode"
                }

            lower.contains("tip") || lower.contains("advice") || lower.contains("improve") ||
                    lower.contains("help") || lower.contains("better") ->
                if (label == "addicted")
                    "Addiction Plan 🛠️\n1. Delete social apps from home screen\n2. Use grayscale mode\n3. Enable App Timers\n4. Phone out of bedroom\n5. 2hr daily screen limit\n\nBiggest factor: ${topFactor.replace("_"," ").uppercase()}"
                else if (label == "distracted")
                    "Focus Plan 📵\n1. Do Not Disturb during work\n2. Pomodoro: 25min focus, 5min break\n3. Phone face-down\n4. Check notifs only 3x/day\n\nTrigger: ${topFactor.replace("_"," ")}"
                else
                    "Maintenance Plan ✅\nYou're doing great!\n1. Keep focus session streak\n2. Consistent sleep schedule\n3. Exercise daily\n4. Review weekly report\n\nSelf-Control: $score/100!"

            lower.contains("type") || lower.contains("cluster") || lower.contains("profile") ->
                "Profile: $cluster 👤\n\n" + when (cluster) {
                    "Night Owl"      -> "Heavy phone use 10PM-2AM. Disrupts sleep.\nFix: Hard curfew at 10PM."
                    "Binge User"     -> "Long continuous sessions (90+ min). Most addictive pattern.\nFix: 30-min timer every time you pick up phone."
                    "Impulsive User" -> "Constant app switching without intent.\nFix: State your purpose before opening phone."
                    else             -> "Regular pattern. Keep monitoring!"
                }

            lower.contains("shap") || lower.contains("factor") || lower.contains("why") ||
                    lower.contains("explain") ->
                "AI Explanation (SHAP) 🔍\n\nTop factor: ${topFactor.replace("_"," ").uppercase()}\n\n" +
                        when (topFactor) {
                            "time_spent"   -> "Screen time is the strongest predictor. More time = higher risk."
                            "violations"   -> "Your focus violations are the biggest signal to the AI."
                            "app_switches" -> "Frequent app switching = impulsive usage pattern."
                            "hour_of_day"  -> "When you use your phone matters. Late-night use is penalized."
                            else -> "This factor has the highest impact on your AI score."
                        } + "\n\nSHAP shows WHY the AI made its decision, not just what it decided."

            lower.contains("monster") ->
                "Addiction Monster 👹\n\n" + when {
                    score >= 80 -> "Sleeping 😴 — you're in great shape!"
                    score >= 60 -> "Awake 😐 — watching your habits."
                    score >= 40 -> "Angry 😡 — reduce screen time!"
                    else        -> "Full power 💀 — urgent action needed!"
                } + "\n\nLevel = violations + relapse risk. Lower both to shrink it!"

            lower.contains("focus") || lower.contains("session") || lower.contains("timer") ->
                "Focus Mode Guide 🎯\n\n• 25-minute sessions\n• Tap VIOLATION when you break focus\n• 3 violations = FAILED\n• Red flash + vibration = punishment mode\n• After session, AI updates your score\n\nPro tip: Start with 10-min sessions!"

            lower.contains("hello") || lower.contains("hi") || lower.contains("hey") ->
                "Hey! 👋 Ask me about your risk, violations, tips, or your user profile!"

            lower.contains("thank") ->
                "You're welcome! 💪 Even 1% better every day = 37x better in a year!"

            else ->
                "Based on your data ($cluster, score $score/100, risk $riskPct%):\n\nFocus on reducing '${topFactor.replace("_"," ")}'. Try asking:\n• 'Give me tips'\n• 'Explain my violations'\n• 'What is my risk level?'"
        }
    }

    private fun appendUser(msg: String) {
        chatHistory.append("\n👤 You:\n$msg\n")
        binding.tvChat.text = chatHistory.toString()
        scrollToBottom()
    }

    private fun appendBot(msg: String) {
        chatHistory.append("\n🤖 AI Coach:\n$msg\n")
        binding.tvChat.text = chatHistory.toString()
        scrollToBottom()
    }

    private fun scrollToBottom() {
        binding.scrollView.post { binding.scrollView.fullScroll(android.view.View.FOCUS_DOWN) }
    }
}