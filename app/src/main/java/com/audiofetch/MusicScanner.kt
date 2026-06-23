package com.audiofetch

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.os.Build

object MusicScanner {

    /**
     * Scans all audio on the device via MediaStore.Audio.Media (covers Music,
     * Downloads, Podcasts, Ringtones, Alarms — everything the media scanner indexed).
     * Falls back to Downloads-only URI on API < 29 for audio not yet indexed.
     * Results are sorted newest-first by date added.
     */
    fun scanDownloads(context: Context): List<Track> {
        val tracks = mutableListOf<Track>()

        // Primary scan: all audio indexed by MediaStore
        tracks.addAll(scanAudioMedia(context))

        // Secondary scan: Downloads folder (API 29+) — catches files not yet
        // picked up by the media scanner (e.g. freshly downloaded m4a files)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val downloadsOnly = scanDownloadsUri(context)
            // Deduplicate by title to avoid showing the same file twice
            val existingTitles = tracks.map { it.title.lowercase() }.toHashSet()
            downloadsOnly.forEach { t ->
                if (t.title.lowercase() !in existingTitles) {
                    tracks.add(t)
                    existingTitles.add(t.title.lowercase())
                }
            }
        }

        // Sort newest first
        return tracks.sortedByDescending { it.durationMs }
    }

    private fun scanAudioMedia(context: Context): List<Track> {
        val tracks = mutableListOf<Track>()

        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.MIME_TYPE
        )

        // Exclude very short files (ringtones, notification sounds < 10 seconds)
        val selection = "${MediaStore.Audio.Media.DURATION} >= 10000"
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        try {
            context.contentResolver.query(
                collection, projection, selection, null, sortOrder
            )?.use { c ->
                val idCol    = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artCol   = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val durCol   = c.getColumnIndex(MediaStore.Audio.Media.DURATION)

                while (c.moveToNext()) {
                    val id       = c.getLong(idCol)
                    val rawTitle = c.getString(titleCol) ?: continue
                    val artist   = if (artCol >= 0) c.getString(artCol) ?: "" else ""
                    val duration = if (durCol >= 0) c.getLong(durCol) else 0L

                    val uri = Uri.withAppendedPath(collection, id.toString())

                    val title = rawTitle
                        .replace('_', ' ')
                        .replace('-', ' ')
                        .trim()

                    // Skip placeholder artist tags
                    val cleanArtist = if (artist == "<unknown>") "" else artist

                    tracks.add(Track(uri = uri, title = title, artist = cleanArtist, durationMs = duration))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return tracks
    }

    private fun scanDownloadsUri(context: Context): List<Track> {
        val tracks = mutableListOf<Track>()

        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.DURATION,
            MediaStore.Downloads.MIME_TYPE,
            MediaStore.Downloads.DATE_ADDED
        )

        val selection  = "${MediaStore.Downloads.MIME_TYPE} LIKE 'audio/%'"
        val sortOrder  = "${MediaStore.Downloads.DATE_ADDED} DESC"

        try {
            context.contentResolver.query(
                collection, projection, selection, null, sortOrder
            )?.use { c ->
                val idCol   = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val durCol  = c.getColumnIndex(MediaStore.Downloads.DURATION)

                while (c.moveToNext()) {
                    val id       = c.getLong(idCol)
                    val rawName  = c.getString(nameCol) ?: continue
                    val duration = if (durCol >= 0) c.getLong(durCol) else 0L

                    val uri = Uri.withAppendedPath(collection, id.toString())

                    val title = rawName.substringBeforeLast('.')
                        .replace('_', ' ')
                        .replace('-', ' ')
                        .trim()

                    tracks.add(Track(uri = uri, title = title, durationMs = duration))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return tracks
    }
}
