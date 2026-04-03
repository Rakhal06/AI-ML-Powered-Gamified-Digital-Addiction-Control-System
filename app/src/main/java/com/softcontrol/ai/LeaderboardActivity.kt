package com.softcontrol.ai

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.softcontrol.ai.databinding.ActivityLeaderboardBinding
import kotlinx.coroutines.launch

/**
 * Feature 5: Social & Competitive Features — Leaderboard
 * Shows ranked list of all users by focus score + user's own rank.
 */
class LeaderboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLeaderboardBinding
    private val entries = mutableListOf<LeaderboardEntry>()
    private lateinit var adapter: LeaderboardAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLeaderboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = LeaderboardAdapter(entries, UserProfileManager.getUserId(this))
        binding.recyclerLeaderboard.layoutManager = LinearLayoutManager(this)
        binding.recyclerLeaderboard.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRefresh.setOnClickListener { loadLeaderboard() }

        loadLeaderboard()
    }

    private fun loadLeaderboard() {
        val userId = UserProfileManager.getUserId(this)
        binding.tvLoading.visibility = View.VISIBLE
        binding.recyclerLeaderboard.visibility = View.GONE

        lifecycleScope.launch {
            try {
                // Load leaderboard
                val lbResponse = RetrofitClient.instance.getLeaderboard(50)
                // Load user's own rank
                val rankResponse = RetrofitClient.instance.getUserRank(userId)

                runOnUiThread {
                    binding.tvLoading.visibility = View.GONE
                    binding.recyclerLeaderboard.visibility = View.VISIBLE

                    if (lbResponse.isSuccessful) {
                        val leaderboard = lbResponse.body()?.leaderboard ?: emptyList()
                        entries.clear()
                        entries.addAll(leaderboard)
                        adapter.notifyDataSetChanged()

                        binding.tvPlayerCount.text = "${leaderboard.size} players ranked"
                    }

                    if (rankResponse.isSuccessful) {
                        val rankData = rankResponse.body()
                        val rank     = rankData?.rank ?: 0
                        val profile  = rankData?.profile
                        val score    = profile?.focus_score ?: 0
                        val xp       = profile?.weekly_xp ?: 0
                        binding.tvMyRank.text = "Your Rank: #$rank   Score: $score   Weekly XP: $xp"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.tvLoading.text = "Cannot connect to server.\nMake sure backend is running."
                    Toast.makeText(this@LeaderboardActivity,
                        "Server unreachable", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ── RecyclerView Adapter ────────────────────────────────────
    class LeaderboardAdapter(
        private val data: List<LeaderboardEntry>,
        private val myUserId: String
    ) : RecyclerView.Adapter<LeaderboardAdapter.VH>() {

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvRank:  TextView = itemView.findViewById(R.id.tvLbRank)
            val tvName:  TextView = itemView.findViewById(R.id.tvLbName)
            val tvScore: TextView = itemView.findViewById(R.id.tvLbScore)
            val tvXP:    TextView = itemView.findViewById(R.id.tvLbXP)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_leaderboard, parent, false)
            return VH(view)
        }

        override fun getItemCount() = data.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = data[position]
            val rankEmoji = when (entry.rank) { 1 -> "🥇"; 2 -> "🥈"; 3 -> "🥉"; else -> "#${entry.rank}" }
            holder.tvRank.text  = rankEmoji
            holder.tvName.text  = entry.display_name
            holder.tvScore.text = "Score: ${entry.focus_score}"
            holder.tvXP.text    = "XP: ${entry.weekly_xp}"

            // Highlight current user's row
            val isMe = entry.user_id == myUserId
            holder.itemView.setBackgroundColor(
                if (isMe) 0xFF1A1A4E.toInt() else 0xFF1A1A2E.toInt()
            )
            holder.tvName.setTextColor(
                if (isMe) 0xFFFACC15.toInt() else 0xFFFFFFFF.toInt()
            )
        }
    }
}