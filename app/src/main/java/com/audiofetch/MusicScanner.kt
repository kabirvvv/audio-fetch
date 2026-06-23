package com.audiofetch

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore

object MusicScanner {

    /**
     * Scans the device's Downloads folder via MediaStore and returns
     * all audio files as Track objects, sorted by date added (newest first).
     */
    fun scanDownloads(context: Context): List<Track> {
        val tracks = mutableListOf<Track>()

        val collection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.DURATION,
            MediaStore.Downloads.MIME_TYPE,
            MediaStore.Downloads.DATE_ADDED
        )

        val selection = "${MediaStore.Downloads.MIME_TYPE} LIKE 'audio/%'"
        val sortOrder = "${MediaStore.Downloads.DATE_ADDED} DESC"

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                collection,
                projection,
                selection,
                null,
                sortOrder
            )

            cursor?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val durCol = c.getColumnIndex(MediaStore.Downloads.DURATION)

                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val rawName = c.getString(nameCol) ?: continue
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
        } finally {
            cursor?.close()
        }

        return tracks
    }
}
