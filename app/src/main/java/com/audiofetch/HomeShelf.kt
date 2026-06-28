package com.audiofetch

/**
 * Type of card on the Home screen.
 * Drives tap behaviour in MainActivity.
 */
enum class HomeCardType {
    TRACK,      // tap → stream immediately via streamFromSearchResult()
    ALBUM,      // tap → stub toast (future: show track list sheet)
    PLAYLIST,   // tap → stub toast (future: show track list sheet)
    MOOD        // not used in shelves — MoodChipAdapter uses plain strings
}

/**
 * A single card on the Home screen.
 *
 * For TRACK cards:  videoId is always set.
 * For ALBUM/PLAYLIST cards: playlistId is set; videoId may be empty.
 */
data class HomeCard(
    val videoId:    String        = "",
    val playlistId: String?       = null,
    val title:      String,
    val artist:     String        = "",
    val thumbnail:  String        = "",
    val type:       HomeCardType  = HomeCardType.TRACK,
)

/**
 * A horizontal shelf rendered on the Home screen.
 * One RecyclerView row per shelf.
 *
 * [title]  — display label above the row, e.g. "Trending" or "Quick Picks"
 * [items]  — ordered list of cards inside the shelf
 */
data class HomeShelf(
    val title: String,
    val items: List<HomeCard>,
)

/**
 * A mood / genre chip from get_mood_categories().
 * Rendered by MoodChipAdapter in the Moods row.
 */
data class MoodChip(
    val title:  String,
    val params: String,
)
