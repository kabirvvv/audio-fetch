package com.audiofetch

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class LibraryAdapter(
    private var tracks: List<Track>,
    private val onPlay: (Track) -> Unit,
    private val onLongPress: (Track, View) -> Unit
) : RecyclerView.Adapter<LibraryAdapter.VH>() {

    var accentColor: Int = 0xFF00E676.toInt()

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title:    TextView  = v.findViewById(R.id.libTrackTitle)
        val artist:   TextView  = v.findViewById(R.id.libTrackArtist)
        val duration: TextView  = v.findViewById(R.id.libTrackDuration)
        val playIcon: ImageView = v.findViewById(R.id.libTrackPlay)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_library_track, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val track = tracks[pos]
        h.title.text    = track.title
        h.artist.text   = track.artist.ifEmpty { "Unknown" }
        h.duration.text = formatMs(track.durationMs)
        h.itemView.setOnClickListener { onPlay(track) }
        h.itemView.setOnLongClickListener { onLongPress(track, h.itemView); true }
    }

    override fun getItemCount() = tracks.size

    fun update(newTracks: List<Track>) {
        tracks = newTracks
        notifyDataSetChanged()
    }

    private fun formatMs(ms: Long): String {
        val s = ms / 1000; val m = s / 60; val sec = s % 60
        return "$m:${sec.toString().padStart(2, '0')}"
    }
}
