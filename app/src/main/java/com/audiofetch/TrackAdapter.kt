package com.audiofetch

import android.graphics.Color
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

class TrackAdapter(
    private val tracks: MutableList<Track>,
    private val onTrackClick: (Int) -> Unit
) : RecyclerView.Adapter<TrackAdapter.VH>() {

    var accentColor: Int = 0xFF00E676.toInt()
    private var nowPlayingIndex = -1
    var itemTouchHelper: ItemTouchHelper? = null

    // Called by MainActivity when the user drops a dragged item
    var onTrackMoved: ((from: Int, to: Int) -> Unit)? = null

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title:      TextView  = v.findViewById(R.id.queueTrackTitle)
        val artist:     TextView  = v.findViewById(R.id.queueTrackArtist)
        val duration:   TextView  = v.findViewById(R.id.queueTrackDuration)
        val nowPlaying: View      = v.findViewById(R.id.queueNowPlayingBar)
        val dragHandle: ImageView = v.findViewById(R.id.queueDragHandle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_queue_track, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val track = tracks[pos]
        h.title.text    = track.title
        h.artist.text   = track.artist.ifEmpty { "Unknown" }
        h.duration.text = formatMs(track.durationMs)

        val isNowPlaying = pos == nowPlayingIndex
        h.nowPlaying.visibility = if (isNowPlaying) View.VISIBLE else View.INVISIBLE
        h.title.setTextColor(if (isNowPlaying) accentColor else Color.WHITE)

        h.itemView.setOnClickListener { onTrackClick(h.adapterPosition) }

        // Drag handle — start drag on touch
        h.dragHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                itemTouchHelper?.startDrag(h)
            }
            false
        }
    }

    override fun getItemCount() = tracks.size

    fun updateTracks(newTracks: List<Track>) {
        tracks.clear()
        tracks.addAll(newTracks)
        notifyDataSetChanged()
    }

    fun setNowPlaying(index: Int) {
        val old = nowPlayingIndex
        nowPlayingIndex = index
        if (old >= 0) notifyItemChanged(old)
        if (index >= 0) notifyItemChanged(index)
    }

    fun moveItem(from: Int, to: Int) {
        val item = tracks.removeAt(from)
        tracks.add(to, item)
        notifyItemMoved(from, to)
        onTrackMoved?.invoke(from, to)
    }

    private fun formatMs(ms: Long): String {
        val s = ms / 1000; val m = s / 60; val sec = s % 60
        return "$m:${sec.toString().padStart(2, '0')}"
    }
}
