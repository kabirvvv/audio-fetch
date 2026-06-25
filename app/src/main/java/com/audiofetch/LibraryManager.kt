package com.audiofetch

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single source of truth for Downloads, Local, History, and Playlists.
 * Everything is persisted in SharedPreferences as JSON.
 */
object LibraryManager {

    private const val PREFS = "library"
    private const val KEY_DOWNLOADS = "downloads"
    private const val KEY_LOCAL = "local"
    private const val KEY_HISTORY = "history"
    private const val KEY_PLAYLISTS = "playlists"
    private const val HISTORY_LIMIT = 50

    // ── in-memory caches ────────────────────────────────────────────────────
    private val downloads = mutableListOf<Track>()
    private val local     = mutableListOf<Track>()
    private val history   = mutableListOf<Track>()
    private val playlists = mutableListOf<Playlist>()

    // ── init ────────────────────────────────────────────────────────────────

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        downloads.clear(); downloads.addAll(tracksFromJson(prefs.getString(KEY_DOWNLOADS, "[]") ?: "[]"))
        local.clear();     local.addAll(tracksFromJson(prefs.getString(KEY_LOCAL, "[]") ?: "[]"))
        history.clear();   history.addAll(tracksFromJson(prefs.getString(KEY_HISTORY, "[]") ?: "[]"))
        playlists.clear(); playlists.addAll(playlistsFromJson(prefs.getString(KEY_PLAYLISTS, "[]") ?: "[]"))
    }

    // ── read ─────────────────────────────────────────────────────────────────

    fun getDownloads(): List<Track> = downloads.toList()
    fun getLocal(): List<Track>     = local.toList()
    fun getHistory(): List<Track>   = history.toList()
    fun getPlaylists(): List<Playlist> = playlists.toList()

    fun getPlaylist(id: String): Playlist? = playlists.find { it.id == id }

    fun getPlaylistTracks(playlist: Playlist): List<Track> {
        val all = (downloads + local).associateBy { it.uri.toString() }
        return playlist.trackUris.mapNotNull { all[it] }
    }

    // ── downloads ────────────────────────────────────────────────────────────

    fun addDownload(context: Context, track: Track) {
        if (downloads.none { it.uri == track.uri }) {
            downloads.add(0, track)
            save(context)
        }
    }

    fun removeDownload(context: Context, track: Track) {
        downloads.removeAll { it.uri == track.uri }
        save(context)
    }

    // ── local ────────────────────────────────────────────────────────────────

    fun setLocal(context: Context, tracks: List<Track>) {
        local.clear()
        local.addAll(tracks)
        save(context)
    }

    // ── history ──────────────────────────────────────────────────────────────

    fun addToHistory(context: Context, track: Track) {
        history.removeAll { it.uri == track.uri }
        history.add(0, track)
        if (history.size > HISTORY_LIMIT) history.subList(HISTORY_LIMIT, history.size).clear()
        save(context)
    }

    fun clearHistory(context: Context) {
        history.clear()
        save(context)
    }

    // ── playlists ────────────────────────────────────────────────────────────

    fun createPlaylist(context: Context, name: String): Playlist {
        val pl = Playlist(name = name)
        playlists.add(pl)
        save(context)
        return pl
    }

    fun renamePlaylist(context: Context, id: String, newName: String) {
        val idx = playlists.indexOfFirst { it.id == id }
        if (idx >= 0) {
            playlists[idx] = playlists[idx].copy(name = newName)
            save(context)
        }
    }

    fun deletePlaylist(context: Context, id: String) {
        playlists.removeAll { it.id == id }
        save(context)
    }

    fun addTrackToPlaylist(context: Context, playlistId: String, track: Track) {
        val pl = playlists.find { it.id == playlistId } ?: return
        val uriStr = track.uri.toString()
        if (uriStr !in pl.trackUris) {
            pl.trackUris.add(uriStr)
            save(context)
        }
    }

    fun removeTrackFromPlaylist(context: Context, playlistId: String, trackUri: String) {
        playlists.find { it.id == playlistId }?.trackUris?.remove(trackUri)
        save(context)
    }

    fun reorderPlaylist(context: Context, playlistId: String, from: Int, to: Int) {
        val pl = playlists.find { it.id == playlistId } ?: return
        if (from < 0 || to < 0 || from >= pl.trackUris.size || to >= pl.trackUris.size) return
        val uri = pl.trackUris.removeAt(from)
        pl.trackUris.add(to, uri)
        save(context)
    }

    // ── serialisation ────────────────────────────────────────────────────────

    private fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putString(KEY_DOWNLOADS, tracksToJson(downloads))
            putString(KEY_LOCAL,     tracksToJson(local))
            putString(KEY_HISTORY,   tracksToJson(history))
            putString(KEY_PLAYLISTS, playlistsToJson(playlists))
            apply()
        }
    }

    private fun trackToJson(t: Track): JSONObject = JSONObject().apply {
        put("uri",      t.uri.toString())
        put("title",    t.title)
        put("artist",   t.artist)
        put("duration", t.durationMs)
    }

    private fun trackFromJson(o: JSONObject) = Track(
        uri       = Uri.parse(o.getString("uri")),
        title     = o.optString("title", "Unknown"),
        artist    = o.optString("artist", ""),
        durationMs = o.optLong("duration", 0L)
    )

    private fun tracksToJson(list: List<Track>): String {
        val arr = JSONArray()
        list.forEach { arr.put(trackToJson(it)) }
        return arr.toString()
    }

    private fun tracksFromJson(json: String): List<Track> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { trackFromJson(arr.getJSONObject(it)) }
    }

    private fun playlistsToJson(list: List<Playlist>): String {
        val arr = JSONArray()
        list.forEach { pl ->
            val uris = JSONArray()
            pl.trackUris.forEach { uris.put(it) }
            arr.put(JSONObject().apply {
                put("id",   pl.id)
                put("name", pl.name)
                put("uris", uris)
            })
        }
        return arr.toString()
    }

    private fun playlistsFromJson(json: String): List<Playlist> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val urisArr = o.getJSONArray("uris")
            val uris = (0 until urisArr.length()).map { urisArr.getString(it) }.toMutableList()
            Playlist(id = o.getString("id"), name = o.getString("name"), trackUris = uris)
        }
    }
}
