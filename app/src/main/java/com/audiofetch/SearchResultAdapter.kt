package com.audiofetch

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class SearchResult(
    val videoId: String,
    val title: String,
    val artist: String,
    val duration: String,
    val durationSeconds: Int,
    val thumbnail: String,
    val webpageUrl: String,
)

class SearchResultsAdapter(
    private var results: List<SearchResult>,
    private val onTap: (SearchResult) -> Unit,
) : RecyclerView.Adapter<SearchResultsAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.searchThumb)
        val title: TextView     = view.findViewById(R.id.searchTitle)
        val artist: TextView    = view.findViewById(R.id.searchArtist)
        val duration: TextView  = view.findViewById(R.id.searchDuration)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_result, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val result = results[position]
        holder.title.text    = result.title
        holder.artist.text   = result.artist.ifEmpty { "Unknown" }
        holder.duration.text = result.duration

        // Load thumbnail with Glide if available, else show default
        holder.thumbnail.setImageResource(R.drawable.bg_album_art_default)
        if (result.thumbnail.isNotEmpty()) {
            try {
                com.bumptech.glide.Glide.with(holder.thumbnail.context)
                    .load(result.thumbnail)
                    .placeholder(R.drawable.bg_album_art_default)
                    .error(R.drawable.bg_album_art_default)
                    .centerCrop()
                    .into(holder.thumbnail)
            } catch (_: Exception) {}
        }

        holder.itemView.setOnClickListener { onTap(result) }
    }

    override fun getItemCount() = results.size

    fun update(newResults: List<SearchResult>) {
        results = newResults
        notifyDataSetChanged()
    }
}
