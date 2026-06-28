package com.example.audiofetch

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.audiofetch.R

class MoodChipAdapter(
    private val onClick: (params: String, title: String) -> Unit,
) : ListAdapter<MoodChip, MoodChipAdapter.ChipViewHolder>(DIFF) {

    inner class ChipViewHolder(root: View) : RecyclerView.ViewHolder(root) {
        val chip: TextView = root.findViewById(R.id.moodChipText)

        fun bind(mood: MoodChip) {
            chip.text = mood.title
            itemView.setOnClickListener { onClick(mood.params, mood.title) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChipViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_mood_chip, parent, false)
        return ChipViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChipViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<MoodChip>() {
            override fun areItemsTheSame(a: MoodChip, b: MoodChip): Boolean =
                a.params == b.params
            override fun areContentsTheSame(a: MoodChip, b: MoodChip): Boolean =
                a == b
        }
    }
}
