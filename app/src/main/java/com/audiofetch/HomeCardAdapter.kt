package com.audiofetch

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import com.audiofetch.R

class HomeCardAdapter(
    private val onClick: (HomeCard) -> Unit,
) : ListAdapter<HomeCard, HomeCardAdapter.CardViewHolder>(DIFF) {

    inner class CardViewHolder(root: View) : RecyclerView.ViewHolder(root) {
        val art:    ImageView = root.findViewById(R.id.homeCardArt)
        val title:  TextView  = root.findViewById(R.id.homeCardTitle)
        val artist: TextView  = root.findViewById(R.id.homeCardArtist)

        fun bind(card: HomeCard) {
            title.text  = card.title
            artist.text = card.artist

            Glide.with(art.context)
                .load(card.thumbnail.ifBlank { null })
                .apply(
                    RequestOptions()
                        .placeholder(R.drawable.bg_album_art_default)
                        .error(R.drawable.bg_album_art_default)
                        .transform(RoundedCorners(dpToPx(8, art.context)))
                )
                .into(art)

            itemView.setOnClickListener { onClick(card) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_home_card, parent, false)
        return CardViewHolder(view)
    }

    override fun onBindViewHolder(holder: CardViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<HomeCard>() {
            override fun areItemsTheSame(a: HomeCard, b: HomeCard): Boolean =
                when {
                    !a.videoId.isNullOrEmpty()  -> a.videoId    == b.videoId
                    a.playlistId != null         -> a.playlistId == b.playlistId
                    else                         -> a.title      == b.title
                }

            override fun areContentsTheSame(a: HomeCard, b: HomeCard): Boolean =
                a == b
        }
    }
}

private fun dpToPx(dp: Int, context: android.content.Context): Int =
    (dp * context.resources.displayMetrics.density).toInt()
