package com.audiofetch

import android.net.Uri

data class Track(
    val uri: Uri,
    val title: String,
    val artist: String = "",
    val durationMs: Long = 0L,
    val videoId: String? = null,
    val isAutoplay: Boolean = false,
    val thumbnailUrl: string = ""
)
