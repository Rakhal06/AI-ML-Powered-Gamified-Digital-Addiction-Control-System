package com.softcontrol.ai

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.softcontrol.ai.databinding.ActivityCoachBinding
import java.util.Calendar

class CoachActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCoachBinding
    private val chatHistory = StringBuilder()

    // Context from last ML analysis
    private var label      = "focused"
    private var score      = 100
    private var risk       = 0f
    private var cluster    = "Regular User"
    private var violations = 0
    private var topFactor  = "time_spent"
    private var timeSpent  = 0f
    private var switches   = 0
    private var weekly     = 0f
    private var monster    = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCoachBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadPrefs()
        appendBot(buildWelcome())

        // Quick reply buttons
        binding.btnQ1.setOnClickListener { askQuestion("What is my relapse risk and what should I do?") }
        binding.btnQ2.setOnClickListener { askQuestion("Give me a personalized action plan to improve") }
        binding.btnQ3.setOnClickListener { askQuestion("Explain my violations and what caused them") }
        binding.btnQ4.setOnClickListener { askQuestion("What does my user profile mean and how do I change it?") }

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
        timeSpent  = p.getFloat("time_spent",     0f)
        switches   = p.getInt("app_switches",     0)
        weekly     = p.getFloat("weekly_hours",   0f)
        monster    = p.getInt("monster_level",    0)
    }

    private fun askQuestion(msg: String) {
        appendUser(msg)
        binding.etMessage.setText("")
        // Small delay for natural feel
        binding.root.postDelayed({ appendBot(generateReply(msg)) }, 350)
    }

    // ── Welcome Message ────────────────────────────────────
    private fun buildWelcome(): String {
        val riskPct  = (risk * 100).toInt()
        val hour     = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greeting = when (hour) {
            in 5..11  -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..20 -> "Good evening"
            else      -> "Hey"
        }

        val statusLine = when (label) {
            "addicted"   -> "⚠️ Your behavior is classified as ADDICTED. Immediate action recommended."
            "distracted" -> "🟡 Your behavior is classified as DISTRACTED. You can improve this today."
            else         -> "✅ Your behavior is classified as FOCUSED. Keep it up!"
        }

        val riskLine = when {
            riskPct >= 70 -> "🔴 Relapse Risk: ${riskPct}% — CRITICAL"
            riskPct >= 40 -> "🟡 Relapse Risk: ${riskPct}% — MODERATE"
            else          -> "🟢 Relapse Risk: ${riskPct}% — LOW"
        }

        return """$greeting! I'm your AI Assistant 🤖

Here's your current status:
$statusLine
$riskLine
📊 Self-Control Score: $score/100
👤 Behavioral Profile: $cluster
⏱ Time Spent: ${timeSpent.toInt()} min
🔄 App Switches: $switches
⚠️ Violations: $violations
🔍 Top AI Factor: ${topFactor.replace("_", " ").uppercase()}
📅 Weekly Forecast: ${weekly}h/week

Use the quick buttons or ask me anything about your digital behavior!"""
    }

    // ── Main Reply Engine ──────────────────────────────────
    private fun generateReply(msg: String): String {
        val lower   = msg.lowercase()
        val riskPct = (risk * 100).toInt()
        val hour    = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

        return when {

            // ── RISK / RELAPSE ────────────────────────────
            lower.containsAny("risk", "relapse", "danger", "chance") -> buildRiskReply(riskPct, hour)

            // ── SCORE / CONTROL ───────────────────────────
            lower.containsAny("score", "control", "discipline", "self-control", "point") -> buildScoreReply()

            // ── VIOLATIONS ───────────────────────────────
            lower.containsAny("violation", "broke", "failed", "app", "opened", "distraction") -> buildViolationReply()

            // ── USER TYPE / PROFILE ────────────────────────
            lower.containsAny("type", "cluster", "profile", "night owl", "binge", "impulsive") -> buildProfileReply()

            // ── ACTION PLAN / TIPS ────────────────────────
            lower.containsAny("tip", "advice", "improve", "help", "plan", "action", "better", "fix", "how") -> buildActionPlanReply(riskPct)

            // ── SHAP / AI EXPLANATION ─────────────────────
            lower.containsAny("shap", "factor", "why", "explain", "reason", "because", "cause") -> buildShapReply()

            // ── MONSTER ───────────────────────────────────
            lower.containsAny("monster", "level", "creature", "grow", "shrink") -> buildMonsterReply()

            // ── FOCUS / SESSION ───────────────────────────
            lower.containsAny("focus", "session", "timer", "pomodoro", "25", "minute") -> buildFocusReply()

            // ── WEEKLY / FORECAST ─────────────────────────
            lower.containsAny("week", "forecast", "predict", "future", "hours") -> buildWeeklyReply()

            // ── TIME / SCREEN TIME ────────────────────────
            lower.containsAny("time", "screen", "spent", "usage", "how long") -> buildScreenTimeReply(hour)

            // ── SLEEP / NIGHT ─────────────────────────────
            lower.containsAny("sleep", "night", "late", "midnight", "bedtime") -> buildSleepReply(hour)

            // ── COMPARISON / PROGRESS ─────────────────────
            lower.containsAny("compare", "progress", "better than", "yesterday", "improve", "trend") -> buildProgressReply()

            // ── MOTIVATION ───────────────────────────────
            lower.containsAny("motivat", "inspire", "encourage", "can i", "hard", "difficult", "struggle") -> buildMotivationReply()

            // ── GREETINGS ─────────────────────────────────
            lower.containsAny("hello", "hi", "hey", "hii", "what can you", "who are you") ->
                "Hi! 👋 I'm your AI Assistant, powered by your real ML analysis data.\n\nI can answer questions about:\n• Your relapse risk and what it means\n• Your self-control score breakdown\n• Your violations and which apps caused them\n• Your behavioral profile (Night Owl/Binge/Impulsive)\n• Personalized action plans\n• Why the AI classified you this way (SHAP)\n• Focus session tips\n• Sleep and screen time habits\n\nJust ask me anything!"

            // ── THANK YOU ────────────────────────────────
            lower.containsAny("thank", "thanks", "great", "good bot", "helpful") ->
                "You're welcome! 💪\n\nRemember: consistency is everything. Even improving by 1% every day makes you 37x better in a year.\n\nYou've got this — keep going! 🚀"

            // ── FALLBACK: intelligent catch-all ───────────
            else -> buildSmartFallback(lower, riskPct)
        }
    }

    // ── Reply builders ─────────────────────────────────────

    private fun buildRiskReply(riskPct: Int, hour: Int): String {
        val level = when {
            riskPct >= 80 -> "CRITICAL 🔴"
            riskPct >= 60 -> "HIGH 🟠"
            riskPct >= 40 -> "MODERATE 🟡"
            else          -> "LOW 🟢"
        }
        val causes = buildString {
            append("Based on your data, the key risk factors are:\n")
            if (timeSpent > 150) append("  • Very high screen time (${timeSpent.toInt()} min)\n")
            if (violations >= 2) append("  • Multiple focus violations (${violations})\n")
            if (switches > 20)   append("  • Excessive app switching ($switches times)\n")
            if (hour >= 22 || hour <= 4) append("  • Late-night usage pattern (${hour}:00)\n")
            if (cluster == "Night Owl") append("  • Night Owl profile = higher late-night risk\n")
            if (cluster == "Binge User") append("  • Binge User profile = long uninterrupted sessions\n")
        }
        val action = when {
            riskPct >= 80 -> "🚨 IMMEDIATE ACTIONS:\n  1. Put your phone face down RIGHT NOW\n  2. Do a 5-minute breathing exercise\n  3. Walk away from your phone for 30 minutes\n  4. Enable Strict Do Not Disturb\n  5. Tell someone you're taking a break"
            riskPct >= 60 -> "⚠️ RECOMMENDED ACTIONS:\n  1. Stop using social/entertainment apps now\n  2. Start a focus session\n  3. Set a phone-down timer for 1 hour\n  4. Drink water and take a 10-min walk"
            riskPct >= 40 -> "🟡 PREVENTIVE ACTIONS:\n  1. Be mindful for the next 2 hours\n  2. Avoid opening social media tonight\n  3. Set phone to sleep mode after 9 PM"
            else -> "✅ You're managing well! Keep your current routine. Consider one focus session tomorrow morning to maintain this score."
        }
        return "Your Relapse Risk: $riskPct% — $level\n\n$causes\n$action"
    }

    private fun buildScoreReply(): String {
        val grade = when {
            score >= 90 -> "A+ (Exceptional)"
            score >= 80 -> "A  (Excellent)"
            score >= 70 -> "B  (Good)"
            score >= 60 -> "C  (Average)"
            score >= 40 -> "D  (Below Average)"
            else        -> "F  (Critical)"
        }
        val formula = "Score = 100 - (violations × 10) - usage_factor\n" +
                "     = 100 - (${violations} × 10) - ${(timeSpent/5).toInt().coerceAtMost(30)}\n" +
                "     = $score"
        val breakdown = buildString {
            if (violations > 0) append("  ⬇ Violations cost you ${violations * 10} points\n")
            if (timeSpent > 50) append("  ⬇ Screen time (${timeSpent.toInt()} min) reduced score\n")
            if (score >= 70)    append("  ✅ You maintained good focus discipline\n")
        }
        val nextStep = when {
            score >= 80 -> "Goal: Maintain this score. Try extending sessions to 30 min tomorrow."
            score >= 60 -> "Goal: Reach 80. Complete 2 zero-violation focus sessions."
            score >= 40 -> "Goal: Reach 60. Start with just ONE 10-min session with no violations."
            else        -> "Goal: Reach 40. Remove distraction apps from home screen NOW."
        }
        return "Self-Control Score: $score/100 — Grade: $grade\n\nHow it's calculated:\n$formula\n\nBreakdown:\n$breakdown\nNext goal: $nextStep"
    }

    private fun buildViolationReply(): String {
        if (violations == 0) {
            return "🏆 Zero violations today!\n\nThat means:\n  ✅ You didn't open any distraction apps during focus\n  ✅ You resisted every impulse\n  ✅ Your brain is building a new habit\n\nThis is the most powerful thing you can do. One zero-violation session per day changes everything over 30 days."
        }

        val prefs    = getSharedPreferences("softcontrol", Context.MODE_PRIVATE)
        val appsRaw  = prefs.getString("violation_apps", "") ?: ""
        val appsList = if (appsRaw.isNotEmpty()) appsRaw.split(" | ") else emptyList()

        val appDetail = if (appsList.isNotEmpty()) {
            buildString {
                append("Apps that caused violations:\n")
                appsList.forEachIndexed { idx, entry ->
                    val ord = when(idx+1){ 1->"1st"; 2->"2nd"; else->"${idx+1}th" }
                    append("  $ord → $entry\n")
                }
            }
        } else "Violations were logged manually."

        val impact = "Each violation:\n  • Costs 10 points from your self-control score\n  • Trains your brain to accept distraction\n  • Increases relapse risk by ~15%\n  • Grows the addiction monster"

        val prevention = when {
            violations >= 3 -> "Prevention for next session:\n  1. Delete the trigger apps from home screen\n  2. Put phone in another room\n  3. Start with a 10-min session instead of 25\n  4. Tell someone to check on you"
            violations == 2 -> "Prevention for next session:\n  1. Use Do Not Disturb — allow no exceptions\n  2. Place phone face-down before starting\n  3. Log out of social media apps first"
            else -> "Prevention for next session:\n  1. Before starting, close all apps\n  2. Ask yourself: 'Why am I picking up my phone?'\n  3. Put the most distracting app in a folder 3 screens away"
        }

        return "Violations this session: $violations / 3\n\n$appDetail\n$impact\n\n$prevention"
    }

    private fun buildProfileReply(): String {
        val desc = when (cluster) {
            "Night Owl" -> buildString {
                append("Night Owl Profile 🦉\n\n")
                append("What it means:\n")
                append("  • Your peak phone usage is between 10 PM and 2 AM\n")
                append("  • You use screens when your willpower is lowest\n")
                append("  • Sleep disruption increases next-day cravings by 40%\n")
                append("  • The AI detected this pattern from your hour_of_day data\n\n")
                append("How to change it:\n")
                append("  1. Set a hard phone curfew at 10:00 PM daily\n")
                append("  2. Use Night Mode / Grayscale from 9 PM\n")
                append("  3. Put your phone charger in another room\n")
                append("  4. Replace last-hour phone with a book or podcast\n")
                append("  5. Track your sleep time — aim for 7-8 hours\n\n")
                append("Expected improvement: 2-3 weeks of consistent curfew will shift your cluster to Regular User.")
            }
            "Binge User" -> buildString {
                append("Binge User Profile 🎯\n\n")
                append("What it means:\n")
                append("  • You use your phone in long continuous sessions (90+ min)\n")
                append("  • You rarely take natural breaks during usage\n")
                append("  • This is the most addictive pattern — dopamine loops\n")
                append("  • Your time_spent feature had the highest SHAP impact\n\n")
                append("How to change it:\n")
                append("  1. Set a 30-min alarm every time you pick up your phone\n")
                append("  2. Use app timers for Instagram/YouTube (30 min/day)\n")
                append("  3. Practice the '20-20-20' rule: every 20 min, look 20 feet away for 20 sec\n")
                append("  4. Use focus sessions to enforce mandatory breaks\n")
                append("  5. Delete the apps you binge on from your phone for 7 days\n\n")
                append("Expected improvement: Breaking binge cycles takes 21 days of consistent enforcement.")
            }
            "Impulsive User" -> buildString {
                append("Impulsive User Profile ⚡\n\n")
                append("What it means:\n")
                append("  • You pick up your phone very frequently but briefly ($switches app switches today)\n")
                append("  • Phone checking is automatic — not intentional\n")
                append("  • Each pickup triggers a dopamine micro-hit\n")
                append("  • Your app_switches feature scored highest in SHAP\n\n")
                append("How to change it:\n")
                append("  1. Before every pickup, say out loud what you're doing and why\n")
                append("  2. Remove your phone from your pocket — keep it on a desk\n")
                append("  3. Turn off all non-essential notifications (keep only calls)\n")
                append("  4. Practice 'phone fasting': 1-hour blocks with no pickup\n")
                append("  5. Replace the habit loop: when you feel the urge, do 5 push-ups instead\n\n")
                append("Expected improvement: Impulse control improves significantly in 14 days with deliberate practice.")
            }
            else -> "Your profile is Regular User — a balanced usage pattern. Continue your focus sessions and monitoring to maintain this!"
        }
        return desc
    }

    private fun buildActionPlanReply(riskPct: Int): String {
        val urgency = when {
            riskPct >= 70 -> "🚨 URGENT"
            riskPct >= 40 -> "⚠️ IMPORTANT"
            else          -> "✅ MAINTENANCE"
        }

        val labelActions = when (label) {
            "addicted" -> listOf(
                "Delete Instagram, TikTok, YouTube from your home screen today",
                "Enable Screen Time / Digital Wellbeing limits: max 2h/day total",
                "Start with ONE 10-minute focus session daily for 7 days",
                "Tell a friend or family member about your goal — accountability is powerful",
                "Enable grayscale mode (Settings → Accessibility → Color Correction)",
                "Put your phone in a drawer from 8 PM to 8 AM for 1 week"
            )
            "distracted" -> listOf(
                "Use Do Not Disturb during all work/study blocks — no exceptions",
                "Enable 'Focus Mode' on Android to block distracting apps on demand",
                "Complete 2 focus sessions daily — start with 15 minutes if 25 feels hard",
                "Keep your phone face-down on a table, not in your pocket",
                "Check notifications only 3 times per day (morning, afternoon, evening)",
                "Log every time you pick up your phone this week — awareness is step 1"
            )
            else -> listOf(
                "Maintain your current routine — it's working!",
                "Try extending your focus sessions from 25 to 30 minutes",
                "Share your progress with someone to reinforce the habit",
                "Review your report weekly to catch any negative trends early",
                "Celebrate your wins — you earned it!"
            )
        }

        val clusterActions = when (cluster) {
            "Night Owl"      -> "\nExtra (Night Owl): Set phone curfew at 10 PM. Charge phone outside bedroom."
            "Binge User"     -> "\nExtra (Binge User): Set 30-min app timers. Take mandatory breaks every 20 min."
            "Impulsive User" -> "\nExtra (Impulsive): No phone pickups without stating a purpose first."
            else -> ""
        }

        return "$urgency — Personalized Action Plan\nBased on: $label behavior, ${score}/100 score, $riskPct% risk\n\nImmediate Steps:\n${labelActions.mapIndexed { i, a -> "${i+1}. $a" }.joinToString("\n")}$clusterActions\n\nTracking: Do these for 7 days and check if your score improves. Small consistent actions beat occasional big efforts every time."
    }

    private fun buildShapReply(): String {
        val factorExplain = when (topFactor) {
            "time_spent"   -> "Time Spent: Total minutes on your phone today. HIGH time_spent is the #1 predictor of addiction. The model learned that users spending >150 min/day are almost always classified as Addicted."
            "violations"   -> "Violations: The number of focus session breaks. Each violation is a strong signal of impulsive behavior. The model weights this heavily because violations directly measure your ability to resist distraction."
            "app_switches" -> "App Switches: How many times you switched between apps. High switching (>15) indicates a scattered, distracted mind. The model treats frequent switching as a core distraction signal."
            "hour_of_day"  -> "Hour of Day: When you use your phone. Late-night usage (10 PM - 4 AM) is heavily penalized by the model because it correlates with compulsive, uncontrolled usage patterns."
            else           -> topFactor.replace("_", " ").uppercase()
        }

        return "SHAP Explainability — Why the AI decided: $label\n\nTop Factor: ${topFactor.replace("_"," ").uppercase()}\n\n$factorExplain\n\nWhat SHAP does:\nSHAP (SHapley Additive exPlanations) is a game-theory based method that calculates exactly how much each feature pushed the prediction toward or away from each class.\n\nYour 4 features analyzed:\n  • time_spent (${timeSpent.toInt()} min)\n  • app_switches ($switches)\n  • violations ($violations)\n  • hour_of_day\n\nThe top factor above had the strongest mathematical impact on why you were classified as ${label.uppercase()}. Reducing this specific factor will most directly improve your AI score."
    }

    private fun buildMonsterReply(): String {
        val monsterState = when (monster) {
            0 -> "Sleeping 😴 — Completely dormant. Your digital habits are healthy."
            1 -> "Stirring 😐 — Waking up. Minor bad habits detected."
            2 -> "Awake 😤 — Active and watching. Moderate risk patterns."
            3 -> "Agitated 😡 — Angry and growing. Significant addiction signals."
            4 -> "Raging 👹 — Near maximum power. Urgent intervention needed."
            5 -> "Dominating 💀 — Full power. Critical addiction classification."
            else -> "Unknown state"
        }

        val toShrink = when {
            monster >= 4 -> "To shrink it:\n  • Complete 3 zero-violation focus sessions\n  • Reduce daily screen time below 90 min\n  • Avoid all distraction apps for 48 hours\n  • Your score needs to reach 70+"
            monster >= 2 -> "To shrink it:\n  • Complete 1-2 focus sessions today\n  • Keep violations at zero\n  • Don't use phone after 9 PM\n  • Aim for score above 75"
            else -> "To keep it sleeping:\n  • Maintain your current habits\n  • Keep doing focus sessions\n  • Monitor your score weekly"
        }

        return "Addiction Monster — Level $monster / 5\n\nStatus: $monsterState\n\n$toShrink\n\nHow Monster Level is calculated:\nLevel = violations + floor(risk_score × 3)\n     = $violations + ${((risk * 3).toInt())}\n     = $monster / 5\n\nLowering your violation count and relapse risk directly shrinks the monster."
    }

    private fun buildFocusReply(): String {
        return """Focus Session Guide 🎯

The 25-minute focus session uses the Pomodoro Technique — scientifically proven to improve concentration and reduce digital fatigue.

How it works:
  1. Tap START FOCUS MODE on the dashboard
  2. Timer counts down from 25:00
  3. Auto-detection monitors for distracting apps every 5 seconds
  4. If Instagram/YouTube/TikTok/Netflix etc. is opened → automatic violation
  5. Tap VIOLATION if you manually break focus

The 3-Strike System:
  • Violation 1 → Warning notification. Keep going.
  • Violation 2 → PUNISHMENT: red screen flash + vibration + danger notification
  • Violation 3 → SESSION FAILED. Data sent to AI for analysis.

After the session:
  • AI analyzes your time, violations, and switches
  • Updates your self-control score and monster level
  • Shows you which apps caused violations
  • Gives personalized coaching tips

Tips for success:
  • Start with 15 minutes if 25 feels too hard
  • Put phone face-down before starting
  • Log out of social apps before the session
  • Do it at the same time every day to build a habit"""
    }

    private fun buildWeeklyReply(): String {
        val dailyHours = weekly / 7
        val healthyMax = 2.0f
        val excess     = (dailyHours - healthyMax).coerceAtLeast(0f)

        return buildString {
            append("Weekly Screen Time Forecast 📅\n\n")
            append("At your current rate:\n")
            append("  • Weekly total: ${weekly}h\n")
            append("  • Daily average: ${String.format("%.1f", dailyHours)}h/day\n")
            append("  • Healthy max: 2h/day (14h/week)\n")
            if (excess > 0) {
                append("  • Daily excess: ${String.format("%.1f", excess)}h over limit\n")
                append("  • Monthly: ${String.format("%.0f", weekly * 4.3f)}h on your phone\n\n")
                val lostHours = weekly * 4.3f
                append("In that time you could have:\n")
                append("  • Read ${(lostHours / 5).toInt()} books\n")
                append("  • Learned a new skill (most take ~100h)\n")
                append("  • Exercised ${(lostHours / 0.75f).toInt()} times (45 min each)\n\n")
                append("To reach 14h/week:\n")
                append("  • Reduce daily usage by ${String.format("%.0f", excess * 60)} minutes\n")
                append("  • Use app timers for your top 3 apps\n")
                append("  • Each focus session you complete saves 25+ min of mindless scrolling")
            } else {
                append("\n✅ Your screen time is within healthy limits!")
                append("\nGoal: Keep it under 14h/week. You're currently on track.")
            }
        }
    }

    private fun buildScreenTimeReply(hour: Int): String {
        val timeStr = "${timeSpent.toInt()} minutes"
        val category = when {
            timeSpent < 60  -> "Low (under 1h)"
            timeSpent < 120 -> "Moderate (1-2h)"
            timeSpent < 180 -> "High (2-3h)"
            else            -> "Very High (3h+)"
        }
        val timeContext = when {
            hour in 22..23 || hour <= 4 ->
                "⚠️ You're using your phone late at night (${hour}:00). This is when addiction risk peaks because willpower is lowest and sleep is disrupted."
            hour in 5..8 ->
                "📱 Morning phone use detected (${hour}:00). Starting your day on your phone sets a distracted tone for the rest of the day."
            else -> "Your usage timing is relatively normal for the current hour."
        }

        return "Screen Time Analysis ⏱\n\nToday's session: $timeStr\nCategory: $category\n\n$timeContext\n\nContext:\n  • WHO recommends ≤2h recreational screen time/day\n  • Your current rate = ${weekly}h/week\n  • Each 30 min reduction improves your self-control score by ~6 points\n\nFastest ways to reduce:\n  1. No phone during meals (saves 30-45 min/day)\n  2. No phone first/last 30 min of day (saves 60 min)\n  3. 1 focus session daily (trades mindless scroll for productive use)"
    }

    private fun buildSleepReply(hour: Int): String {
        val lateNight = hour >= 22 || hour <= 4
        return buildString {
            if (lateNight) {
                append("🌙 Late Night Usage Detected (${hour}:00)\n\n")
                append("This is a major concern. Here's why:\n")
                append("  • Blue light from screens suppresses melatonin by 50%\n")
                append("  • Late-night phone use increases next-day cravings\n")
                append("  • Your Night Owl profile confirms this is a pattern, not a one-off\n\n")
                append("Immediate action:\n")
                append("  1. Put your phone down RIGHT NOW\n")
                append("  2. Enable Night Mode if you must continue\n")
                append("  3. Set an alarm: 'No phone after 10 PM' for 7 days\n\n")
            } else {
                append("Sleep & Digital Health 😴\n\n")
            }
            append("Sleep-Phone Connection:\n")
            append("  • Poor sleep → lower willpower → higher addiction risk\n")
            append("  • Every hour of sleep debt increases phone checking by ~30%\n")
            append("  • Your relapse risk (${(risk*100).toInt()}%) is partially driven by sleep patterns\n\n")
            append("7-Day Sleep Protocol:\n")
            append("  1. No phone after 10 PM — hard rule\n")
            append("  2. Charge phone OUTSIDE your bedroom\n")
            append("  3. Use an alarm clock instead of phone alarm\n")
            append("  4. Read 10 pages of a physical book before sleep\n")
            append("  5. Track your sleep time — aim for 7-8 hours")
        }
    }

    private fun buildProgressReply(): String {
        val prefs = getSharedPreferences("softcontrol", Context.MODE_PRIVATE)
        val historyJson = prefs.getString("session_history", "[]") ?: "[]"
        val history = org.json.JSONArray(historyJson)

        if (history.length() < 2) {
            return "Progress Tracking 📈\n\nYou need at least 2 sessions to compare progress.\n\nCurrent status:\n  • Score: $score/100\n  • Risk: ${(risk*100).toInt()}%\n  • Label: ${label.uppercase()}\n\nComplete more focus sessions and come back to see your trend!"
        }

        val last    = history.getJSONObject(history.length() - 1)
        val prev    = history.getJSONObject(history.length() - 2)
        val scoreDiff = last.optInt("score") - prev.optInt("score")
        val riskDiff  = last.optInt("risk")  - prev.optInt("risk")

        return buildString {
            append("Progress Report 📈\n\n")
            append("Last 2 sessions:\n")
            append("  Score:  ${prev.optInt("score")}/100 → ${last.optInt("score")}/100  ")
            append(if (scoreDiff >= 0) "(▲ +$scoreDiff)\n" else "(▼ $scoreDiff)\n")
            append("  Risk:   ${prev.optInt("risk")}%  → ${last.optInt("risk")}%  ")
            append(if (riskDiff <= 0) "(▼ improved)\n" else "(▲ worsened)\n")
            append("  Label:  ${prev.optString("label","—").uppercase()} → ${last.optString("label","—").uppercase()}\n\n")

            if (scoreDiff > 0) append("✅ Your score improved! Keep it up.\n")
            else if (scoreDiff < 0) append("⚠️ Your score dropped. Focus on reducing violations.\n")
            else append("📊 Score is stable. Push for improvement with a zero-violation session.\n")

            append("\nTo see full history: tap VIEW REPORT on the dashboard.")
        }
    }

    private fun buildMotivationReply(): String {
        val riskPct = (risk * 100).toInt()
        val encouragement = when {
            score >= 70 -> "You're already doing great — seriously, $score/100 is a strong score. Don't let up now."
            score >= 40 -> "You're in the middle ground — and that's actually the most important place to be. Small daily improvements here create the biggest long-term change."
            else        -> "I know $score/100 feels discouraging. But the fact that you're here, asking questions, means you already have more self-awareness than 90% of people with this issue."
        }
        return "Motivation Check 💪\n\n$encouragement\n\nSome facts to keep you going:\n  • The brain's prefrontal cortex (self-control center) strengthens with every focus session\n  • 21 days of consistent habits creates a new neural pathway\n  • Every person who overcame digital addiction started exactly where you are now\n  • Your score of $score means you still have ${100-score} points of potential left\n\nThis week's small goal:\n  → One focus session per day with zero violations\n  → That's all. Don't overwhelm yourself.\n\nYou have the awareness. Now just add consistency. 🚀"
    }

    private fun buildSmartFallback(lower: String, riskPct: Int): String {
        // Try to extract a meaningful topic even from unknown questions
        val topicGuess = when {
            lower.contains("what") && lower.contains("mean")  -> "You're asking about what something means — try asking specifically: 'What does my risk score mean?' or 'What does my profile mean?'"
            lower.contains("how") -> "You're asking how to do something — try: 'How do I improve my score?' or 'How does the focus timer work?'"
            lower.contains("when") -> "You're asking about timing — try: 'When should I do focus sessions?' or 'When is my peak risk time?'"
            lower.contains("what") -> "Try asking: 'What is my biggest risk factor?' or 'What apps caused my violations?'"
            else -> "I didn't fully understand that. Try rephrasing or use one of the quick buttons below."
        }

        return "AI Assistant 🤖\n\nBased on your current data:\n  • Status: ${label.uppercase()} | Score: $score/100 | Risk: $riskPct%\n  • Biggest issue: ${topFactor.replace("_"," ").uppercase()}\n\n$topicGuess\n\nQuick topics you can ask about:\n  • 'What is my risk level?'\n  • 'Give me an action plan'\n  • 'Explain my violations'\n  • 'What is my user profile?'\n  • 'How does SHAP work?'\n  • 'How is my score calculated?'\n  • 'What does the monster level mean?'\n  • 'How can I improve my sleep?'"
    }

    // ── Chat UI helpers ────────────────────────────────────
    private fun appendUser(msg: String) {
        chatHistory.append("\n👤 You:\n$msg\n")
        binding.tvChat.text = chatHistory.toString()
        scrollToBottom()
    }

    private fun appendBot(msg: String) {
        chatHistory.append("\n🤖 AI Assistant:\n$msg\n")
        binding.tvChat.text = chatHistory.toString()
        scrollToBottom()
    }

    private fun scrollToBottom() {
        binding.scrollView.post { binding.scrollView.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    // ── Extension helper ───────────────────────────────────
    private fun String.containsAny(vararg keywords: String): Boolean =
        keywords.any { this.contains(it) }
}