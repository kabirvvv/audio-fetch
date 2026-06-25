package com.audiofetch

import java.util.UUID

data class Playlist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val trackUris: MutableList<String> = mutableListOf()
)
