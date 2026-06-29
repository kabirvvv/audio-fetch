package com.audiofetch

import org.json.JSONArray
import org.json.JSONObject

object InnerTubeParser {

    // ── Entry point ───────────────────────────────────────────────────────────

    fun parseHome(root: JSONObject): List<HomeShelf> {
        val shelves = mutableListOf<HomeShelf>()
        val sections = root
            .optJSONObject("contents")
            ?.optJSONObject("singleColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs")
            ?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents")
            ?: return emptyList()

        for (i in 0 until sections.length()) {
            val section = sections.getJSONObject(i)

            // musicCarouselShelfRenderer → horizontal scroll row
            val carousel = section.optJSONObject("musicCarouselShelfRenderer")
            if (carousel != null) {
                val shelf = parseCarousel(carousel)
                if (shelf != null) shelves.add(shelf)
                continue
            }

            // musicShelfRenderer → vertical list (Quick Picks style)
            val musicShelf = section.optJSONObject("musicShelfRenderer")
            if (musicShelf != null) {
                val shelf = parseMusicShelf(musicShelf)
                if (shelf != null) shelves.add(shelf)
            }
        }
        return shelves
    }

    // ── Carousel (horizontal row) ─────────────────────────────────────────────

    private fun parseCarousel(carousel: JSONObject): HomeShelf? {
        val title = carousel
            .optJSONObject("header")
            ?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
            ?.optJSONObject("title")
            ?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optString("text") ?: ""

        val contents = carousel.optJSONArray("contents") ?: return null
        val items = mutableListOf<HomeCard>()

        for (i in 0 until contents.length()) {
            val item = contents.getJSONObject(i)
            val card = parseMusicTwoRowItemRenderer(
                item.optJSONObject("musicTwoRowItemRenderer")
            ) ?: parseMusicResponsiveListItemRenderer(
                item.optJSONObject("musicResponsiveListItemRenderer")
            ) ?: continue
            items.add(card)
        }

        return if (items.isNotEmpty()) HomeShelf(title, items) else null
    }

    // ── Music shelf (vertical list) ───────────────────────────────────────────

    private fun parseMusicShelf(shelf: JSONObject): HomeShelf? {
        val title = shelf
            .optJSONObject("title")
            ?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optString("text") ?: ""

        val contents = shelf.optJSONArray("contents") ?: return null
        val items = mutableListOf<HomeCard>()

        for (i in 0 until contents.length()) {
            val card = parseMusicResponsiveListItemRenderer(
                contents.getJSONObject(i)
                    .optJSONObject("musicResponsiveListItemRenderer")
            ) ?: continue
            items.add(card)
        }

        return if (items.isNotEmpty()) HomeShelf(title, items) else null
    }

    // ── Card parsers ──────────────────────────────────────────────────────────

    // musicTwoRowItemRenderer = album/playlist card (square art + title below)
    private fun parseMusicTwoRowItemRenderer(r: JSONObject?): HomeCard? {
        r ?: return null
        val title = r.optJSONObject("title")
            ?.optJSONArray("runs")?.optJSONObject(0)
            ?.optString("text") ?: return null

        val thumbnail = bestThumbnail(
            r.optJSONObject("thumbnailRenderer")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails")
        )

        // Navigate endpoint to get videoId or browseId
        val nav = r.optJSONObject("navigationEndpoint")
        val videoId = nav?.optJSONObject("watchEndpoint")?.optString("videoId")
        val browseId = nav?.optJSONObject("browseEndpoint")?.optString("browseId")
        val playlistId = nav?.optJSONObject("watchPlaylistEndpoint")
            ?.optString("playlistId")
            ?: r.optJSONObject("overlay")
                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")
                ?.optJSONObject("watchPlaylistEndpoint")
                ?.optString("playlistId")

        val subtitle = r.optJSONArray("subtitle")
            ?.let { runs -> (0 until runs.length()).joinToString("") {
                runs.optJSONObject(it)?.optString("text") ?: ""
            }} ?: ""

        val type = when {
            browseId?.startsWith("MPREb") == true -> HomeCardType.ALBUM
            playlistId != null || browseId?.startsWith("VL") == true -> HomeCardType.PLAYLIST
            videoId != null -> HomeCardType.TRACK
            else -> return null
        }

        return HomeCard(
            videoId    = videoId ?: "",
            playlistId = playlistId ?: browseId,
            title      = title,
            artist     = subtitle,
            thumbnail  = thumbnail,
            type       = type
        )
    }

    // musicResponsiveListItemRenderer = track row (art left, title+artist right)
    private fun parseMusicResponsiveListItemRenderer(r: JSONObject?): HomeCard? {
        r ?: return null

        val videoId = r.optJSONObject("overlay")
            ?.optJSONObject("musicItemThumbnailOverlayRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("musicPlayButtonRenderer")
            ?.optJSONObject("playNavigationEndpoint")
            ?.optJSONObject("watchEndpoint")
            ?.optString("videoId")
            ?: r.optJSONObject("flexColumns")
                ?.let { null } // will fall through to flexColumns parse

        val flexColumns = r.optJSONArray("flexColumns") ?: return null

        val title = flexColumns.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optString("text") ?: return null

        val artist = flexColumns.optJSONObject(1)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")
            ?.let { runs -> (0 until runs.length())
                .filter { runs.getJSONObject(it)
                    .optJSONObject("navigationEndpoint")
                    ?.has("browseEndpoint") == true }
                .joinToString(", ") { runs.getJSONObject(it).optString("text") }
            }?.ifEmpty { 
                // fallback: just take the first run text
                flexColumns.optJSONObject(1)
                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                    ?.optJSONObject("text")
                    ?.optJSONArray("runs")
                    ?.optJSONObject(0)
                    ?.optString("text") ?: ""
            } ?: ""

        val thumbnail = bestThumbnail(
            r.optJSONObject("thumbnail")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails")
        )

        // Try to get videoId from navigation if not found above
        val resolvedVideoId = videoId
            ?: r.optJSONObject("overlay")
                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId")
            ?: return null

        return HomeCard(
            videoId   = resolvedVideoId,
            title     = title,
            artist    = artist,
            thumbnail = thumbnail,
            type      = HomeCardType.TRACK
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun bestThumbnail(thumbnails: JSONArray?): String {
        thumbnails ?: return ""
        // prefer the largest one
        var best = ""
        var bestWidth = 0
        for (i in 0 until thumbnails.length()) {
            val t = thumbnails.getJSONObject(i)
            val w = t.optInt("width", 0)
            if (w > bestWidth) {
                bestWidth = w
                best = t.optString("url", "")
            }
        }
        // if no width info just take last
        if (best.isEmpty() && thumbnails.length() > 0) {
            best = thumbnails.getJSONObject(thumbnails.length() - 1).optString("url", "")
        }
        return best
    }
}
