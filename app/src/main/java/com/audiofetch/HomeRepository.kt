package com.audiofetch

import android.content.Context

object HomeRepository {

    // Fetch home shelves — uses auth cookies if available
    suspend fun getHome(context: Context): List<HomeShelf> {
        val cookies = if (AccountManager.isAuthenticated(context)) {
            // Load raw cookie string from the saved auth file
            loadSavedCookies(context)
        } else null

        return try {
            val raw = InnerTubeClient.getHome(cookies)
            InnerTubeParser.parseHome(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getWatchNext(videoId: String, context: Context): List<HomeCard> {
        val cookies = if (AccountManager.isAuthenticated(context)) loadSavedCookies(context) else null
        return try {
            val raw = InnerTubeClient.getWatchNext(videoId, cookies)
            parseWatchNext(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun loadSavedCookies(context: Context): String? {
        return try {
            val authFile = java.io.File(
                context.filesDir.parent + "/files/headers_auth.json"
            )
            if (!authFile.exists()) return null
            val json = org.json.JSONObject(authFile.readText())
            json.optString("Cookie").ifEmpty { null }
        } catch (e: Exception) { null }
    }

    private fun parseWatchNext(root: org.json.JSONObject): List<HomeCard> {
        val items = mutableListOf<HomeCard>()
        val playlist = root
            .optJSONObject("contents")
            ?.optJSONObject("singleColumnMusicWatchNextResultsRenderer")
            ?.optJSONObject("tabbedRenderer")
            ?.optJSONObject("watchNextTabbedResultsRenderer")
            ?.optJSONArray("tabs")
            ?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("musicQueueRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("playlistPanelRenderer")
            ?.optJSONArray("contents")
            ?: return emptyList()

        for (i in 0 until playlist.length()) {
            val r = playlist.getJSONObject(i)
                .optJSONObject("playlistPanelVideoRenderer") ?: continue
            val videoId = r.optString("videoId").ifEmpty { continue }
            val title = r.optJSONObject("title")
                ?.optJSONArray("runs")?.optJSONObject(0)
                ?.optString("text") ?: continue
            val artist = r.optJSONObject("longBylineText")
                ?.optJSONArray("runs")?.optJSONObject(0)
                ?.optString("text") ?: ""
            val thumbnail = r.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails")
                ?.let { thumbs ->
                    if (thumbs.length() > 0)
                        thumbs.getJSONObject(thumbs.length() - 1).optString("url")
                    else ""
                } ?: ""
            items.add(HomeCard(videoId = videoId, title = title, artist = artist,
                thumbnail = thumbnail, type = HomeCardType.TRACK))
        }
        return items
    }
}
