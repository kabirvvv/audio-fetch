package com.example.audiofetch

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * Horizontal RecyclerView adapter for the Moods & Genres chip row.
 *
 * Each chip is a pill-shaped TextView:
 *   ╭──────────────╮
 *   │  Chill Vibes │  ← 13sp, white, horizontal padding 16dp, height 36dp
 *   ╰──────────────╯
 *   background: #1E1E1E with accent-coloured stroke, fully rounded
 *
 * [onClick]  lambda called with (params, title).
 *             MainActivity calls get_mood_playlists(params) and shows results.
 */
class MoodChipAdapter(
    private val onClick: (params: String, title: String) -> Unit,
) : ListAdapter<MoodChip, MoodChipAdapter.ChipViewHolder>(DIFF) {

    // ── ViewHolder ────────────────────────────────────────────────────────────

    inner class ChipViewHolder(root: View) : RecyclerView.ViewHolder(root) {
        val chip: TextView = root.findViewById(R.id.moodChipText)

        fun bind(mood: MoodChip) {
            chip.text = mood.title
            itemView.setOnClickListener { onClick(mood.params, mood.title) }
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChipViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_mood_chip, parent, false)
        return ChipViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChipViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    // ── DiffUtil ──────────────────────────────────────────────────────────────

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<MoodChip>() {
            override fun areItemsTheSame(a: MoodChip, b: MoodChip): Boolean =
                a.params == b.params

            override fun areContentsTheSame(a: MoodChip, b: MoodChip): Boolean =
                a == b
        }
    }
}
