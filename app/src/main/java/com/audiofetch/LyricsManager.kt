package com.audiofetch

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class LrcLine(val timeMs: Long, val text: String)

object LyricsManager {

    private val syncedCache = mutableMapOf<String, List<LrcLine>>()  // key = videoId or "title|artist"
    private val plainCache  = mutableMapOf<String, String>()

    /**
     * Fetch lyrics for a track.
     * Returns Pair(syncedLines, plainText).
     * If synced lyrics exist, syncedLines is non-empty and plainText is null.
     * If only plain lyrics exist, syncedLines is empty and plainText is the string.
     * If nothing found, syncedLines is empty and plainText is a message string.
     */
    suspend fun fetch(title: String, artist: String, videoId: String): Pair<List<LrcLine>, String?> {
        val cacheKey = videoId.ifEmpty { "$title|$artist" }

        syncedCache[cacheKey]?.let { return Pair(it, null) }
        plainCache[cacheKey]?.let  { return Pair(emptyList(), it) }

        return withContext(Dispatchers.IO) {
            try {
                val encodedArtist = Uri.encode(artist)
                val encodedTitle  = Uri.encode(title)
                val url = "https://lrclib.net/api/get?artist_name=$encodedArtist&track_name=$encodedTitle"

                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", "AudioFetch/1.0 (https://github.com/kabirvvv/audio-fetch)")

                val responseCode = conn.responseCode
                if (responseCode == 404) {
                    val msg = "No lyrics found"
                    plainCache[cacheKey] = msg
                    return@withContext Pair(emptyList(), msg)
                }

                val body = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(body)

                if (json.optBoolean("instrumental", false)) {
                    val msg = "♪ Instrumental"
                    plainCache[cacheKey] = msg
                    return@withContext Pair(emptyList(), msg)
                }

                val synced = json.optString("syncedLyrics", "")
                if (synced.isNotEmpty()) {
                    val lines = parseLrc(synced)
                    if (lines.isNotEmpty()) {
                        syncedCache[cacheKey] = lines
                        return@withContext Pair(lines, null)
                    }
                }

                val plain = json.optString("plainLyrics", "")
                if (plain.isNotEmpty()) {
                    plainCache[cacheKey] = plain
                    return@withContext Pair(emptyList(), plain)
                }

                val msg = "No lyrics found"
                plainCache[cacheKey] = msg
                Pair(emptyList(), msg)

            } catch (e: Exception) {
                Pair(emptyList(), "Could not load lyrics")
            }
        }
    }

    fun parseLrc(lrc: String): List<LrcLine> {
        val regex = Regex("""\[(\d+):(\d+)\.(\d+)\](.*)""")
        return lrc.lines().mapNotNull { line ->
            regex.find(line)?.let {
                val (min, sec, cs, text) = it.destructured
                // cs can be 2 digits (centiseconds) or 3 digits (milliseconds)
                val csLong = cs.toLong()
                val msFromCs = if (cs.length >= 3) csLong else csLong * 10
                val timeMs = min.toLong() * 60_000L + sec.toLong() * 1_000L + msFromCs
                LrcLine(timeMs, text.trim())
            }
        }.sortedBy { it.timeMs }
    }

    fun clearCache() {
        syncedCache.clear()
        plainCache.clear()
    }
}
