package com.audiofetch

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class PlaylistsAdapter(
    private var playlists: List<Playlist>,
    private val onClick: (Playlist) -> Unit,
    private val onLongPress: (Playlist, View) -> Unit
) : RecyclerView.Adapter<PlaylistsAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name:  TextView = v.findViewById(R.id.playlistName)
        val count: TextView = v.findViewById(R.id.playlistCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val pl = playlists[pos]
        h.name.text  = pl.name
        h.count.text = "${pl.trackUris.size} songs"
        h.itemView.setOnClickListener { onClick(pl) }
        h.itemView.setOnLongClickListener { onLongPress(pl, h.itemView); true }
    }

    override fun getItemCount() = playlists.size

    fun update(newPlaylists: List<Playlist>) {
        playlists = newPlaylists
        notifyDataSetChanged()
    }
}
