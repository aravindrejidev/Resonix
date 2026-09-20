package com.resonix.player.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import com.resonix.player.audio.AudioTrackInfo
import com.resonix.player.audio.TrackProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "MediaStoreScanner"

class MediaStoreScanner(private val context: Context) {

    /**
     * Queries MediaStore for every on-device music file, probes each one
     * natively, and upserts the results into Room. Safe to call again
     * later (e.g. pull-to-refresh): rows for files MediaStore no longer
     * reports are removed, everything else is upserted.
     */
    suspend fun scan() = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getInstance(context).trackDao()
        val prober = TrackProber()
        val seenIds = mutableListOf<Long>()

        try {
            val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATE_ADDED
            )
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

            context.contentResolver.query(
                collection,
                projection,
                selection,
                null,
                "${MediaStore.Audio.Media.TITLE} ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val trackUri = ContentUris.withAppendedId(collection, id)
                    val info = probeViaFileDescriptor(prober, trackUri)

                    dao.upsert(
                        Track(
                            mediaStoreId = id,
                            title = cursor.getString(titleCol) ?: "Unknown title",
                            artist = cursor.getString(artistCol) ?: "Unknown artist",
                            album = cursor.getString(albumCol) ?: "Unknown album",
                            albumArtUri = trackUri.toString(),
                            contentUri = trackUri.toString(),
                            durationMs = cursor.getLong(durationCol),
                            sampleRateHz = info?.sampleRateHz ?: 0,
                            bitDepth = info?.bitDepth ?: 0,
                            bitrateBps = info?.bitrateBps ?: 0L,
                            format = info?.format ?: "",
                            dateAddedSec = cursor.getLong(dateAddedCol)
                        )
                    )
                    seenIds.add(id)
                }
            }

            dao.getAllIds()
                .filterNot { it in seenIds }
                .forEach { dao.deleteById(it) }
        } finally {
            prober.release()
        }
    }

    private fun probeViaFileDescriptor(prober: TrackProber, uri: Uri): AudioTrackInfo? {
        var pfd: ParcelFileDescriptor? = null
        return try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r")
            pfd?.let { prober.probe("/proc/self/fd/${it.fd}") }
        } catch (e: Exception) {
            Log.w(TAG, "Probe failed for $uri", e)
            null
        } finally {
            pfd?.close()
        }
    }
}
