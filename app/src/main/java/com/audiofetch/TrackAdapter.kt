package com.audiofetch

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TrackAdapter(
    private val tracks: MutableList<Track>,
    private val onTrackClick: (Int) -> Unit
) : RecyclerView.Adapter<TrackAdapter.ViewHolder>() {

    var currentIndex = -1
    var accentColor = Color.parseColor("#00FFA2")

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val index: TextView = view.findViewById(R.id.trackIndex)
        val name: TextView = view.findViewById(R.id.trackName)
        val duration: TextView = view.findViewById(R.id.trackDuration)
        val dot: ImageView = view.findViewById(R.id.nowPlayingDot)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_track, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val track = tracks[position]
        val isPlaying = position == currentIndex

        holder.index.text = (position + 1).toString().padStart(2, '0')
        holder.name.text = track.title
        holder.duration.text = formatDuration(track.durationMs)

        holder.name.setTextColor(
            if (isPlaying) accentColor else Color.WHITE
        )
        holder.dot.visibility = if (isPlaying) View.VISIBLE else View.GONE

        // Subtle highlight for active track
        holder.itemView.setBackgroundColor(
            if (isPlaying) Color.argb(20, Color.red(accentColor),
                Color.green(accentColor), Color.blue(accentColor))
            else Color.TRANSPARENT
        )

        holder.itemView.setOnClickListener { onTrackClick(position) }
    }

    override fun getItemCount() = tracks.size

    fun updateTracks(newTracks: List<Track>) {
        tracks.clear()
        tracks.addAll(newTracks)
        notifyDataSetChanged()
    }

    fun setNowPlaying(index: Int) {
        val old = currentIndex
        currentIndex = index
        if (old >= 0) notifyItemChanged(old)
        if (index >= 0) notifyItemChanged(index)
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "—"
        val s = ms / 1000
        val m = s / 60
        val sec = s % 60
        return "$m:${sec.toString().padStart(2, '0')}"
    }
}
