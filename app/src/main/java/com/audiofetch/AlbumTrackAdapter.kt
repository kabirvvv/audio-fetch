package com.audiofetch

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Track list adapter used only on the Album page.
 *
 * Unlike [SearchResultsAdapter] (which repeats the same album art thumbnail
 * for every row) this shows a clean "01, 02, 03…" track number, matching the
 * editorial look of a physical tracklist.
 */
class AlbumTrackAdapter(
    private var results: List<SearchResult>,
    private val onTap: (SearchResult) -> Unit,
) : RecyclerView.Adapter<AlbumTrackAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val number: TextView   = view.findViewById(R.id.trackNumber)
        val title: TextView    = view.findViewById(R.id.trackTitle)
        val artist: TextView   = view.findViewById(R.id.trackArtist)
        val duration: TextView = view.findViewById(R.id.trackDuration)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_album_track, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val result = results[position]
        holder.number.text = "%02d".format(position + 1)
        holder.title.text = result.title
        holder.artist.text = result.artist.ifEmpty { "Unknown" }
        holder.duration.text = result.duration
        holder.itemView.setOnClickListener { onTap(result) }
    }

    override fun getItemCount() = results.size

    fun update(newResults: List<SearchResult>) {
        results = newResults
        notifyDataSetChanged()
    }
}
