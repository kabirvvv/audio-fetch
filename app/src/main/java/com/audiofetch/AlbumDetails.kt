package com.audiofetch

/**
 * Full metadata + track list for the Album Page.
 * Populated from Python's get_album_details(browseId).
 */
data class AlbumDetails(
    val title: String,
    val artist: String,
    val thumbnail: String,
    val year: String = "",
    val tracks: List<SearchResult> = emptyList(),
)
