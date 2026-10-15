package com.resonix.player.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import com.resonix.player.audio.AudioTrackInfo
import com.resonix.player.audio.TrackProber
import com.resonix.player.audio.isLosslessFormat
import java.util.Locale

private const val TAG = "TrackMetadata"

/**
 * Reads a track's technical info and tags for the library scanners.
 *
 * The FFmpeg prober ([TrackProber]) is the primary source — it knows the real
 * sample rate, bit depth and codec. It is handed an *already-open file
 * descriptor*, because a content:// document (SAF folder or MediaStore row)
 * cannot be re-opened by path ("/proc/self/fd/N" is denied by Android's
 * storage layer for files the app has no direct path access to — which is why
 * scanned songs used to come out with 0:00 durations and no tags).
 *
 * If FFmpeg still can't read a file, Android's own [MediaMetadataRetriever]
 * is asked, so a song at least shows its real duration and title.
 */
internal object TrackMetadata {

    /** Containers FFmpeg plays that the system MIME map often doesn't report as an audio type. */
    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "mp2", "flac", "wav", "wave", "aif", "aiff", "aifc", "m4a", "m4b", "aac",
        "ogg", "oga", "opus", "wma", "alac", "ape", "wv", "tta", "dsf", "dff", "mka",
        "mpc", "caf", "ac3", "dts", "amr",
    )

    fun isAudioFile(name: String?, mimeType: String?): Boolean {
        if (mimeType != null && (mimeType.startsWith("audio/") || mimeType == "application/ogg")) return true
        val extension = name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
        return extension in AUDIO_EXTENSIONS
    }

    /** Native probe over a descriptor, topped up by the framework retriever when it fell short. */
    fun probe(context: Context, prober: TrackProber, uri: Uri): AudioTrackInfo? {
        val nativeInfo = try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd -> prober.probeFd(pfd.fd) }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't open $uri for probing", e)
            null
        }
        if (nativeInfo != null && nativeInfo.durationMs > 0L) return nativeInfo

        val fallback = readWithRetriever(context, uri)
        return merge(nativeInfo, fallback)
    }

    /** Native result wins; the fallback only fills in what the native one left empty. */
    private fun merge(nativeInfo: AudioTrackInfo?, fallback: AudioTrackInfo?): AudioTrackInfo? {
        if (nativeInfo == null) return fallback
        if (fallback == null) return nativeInfo
        return nativeInfo.copy(
            durationMs = if (nativeInfo.durationMs > 0L) nativeInfo.durationMs else fallback.durationMs,
            bitrateBps = if (nativeInfo.bitrateBps > 0L) nativeInfo.bitrateBps else fallback.bitrateBps,
            title = nativeInfo.title.ifBlank { fallback.title },
            artist = nativeInfo.artist.ifBlank { fallback.artist },
            album = nativeInfo.album.ifBlank { fallback.album },
            year = if (nativeInfo.year > 0) nativeInfo.year else fallback.year,
        )
    }

    private fun readWithRetriever(context: Context, uri: Uri): AudioTrackInfo? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            fun tag(key: Int): String = retriever.extractMetadata(key)?.trim().orEmpty()

            var sampleRate = 0
            var bitDepth = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                sampleRate = tag(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE).toIntOrNull() ?: 0
                bitDepth = tag(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE).toIntOrNull() ?: 0
            }
            val format = formatFromMime(tag(MediaMetadataRetriever.METADATA_KEY_MIMETYPE))
            AudioTrackInfo(
                sampleRateHz = sampleRate,
                bitDepth = if (isLosslessFormat(format)) bitDepth else 0,
                channelCount = 0,
                bitrateBps = tag(MediaMetadataRetriever.METADATA_KEY_BITRATE).toLongOrNull() ?: 0L,
                durationMs = tag(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull() ?: 0L,
                format = format,
                title = tag(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = tag(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    .ifBlank { tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) },
                album = tag(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                year = tag(MediaMetadataRetriever.METADATA_KEY_YEAR).take(4).toIntOrNull() ?: 0,
                trackGainDb = 0.0,
                trackPeakLinear = 0.0,
            )
        } catch (e: Exception) {
            Log.w(TAG, "MediaMetadataRetriever failed for $uri", e)
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun formatFromMime(mime: String): String = when (mime.lowercase(Locale.ROOT)) {
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
        "audio/x-wav", "audio/wav", "audio/vnd.wave" -> "wav"
        "audio/ogg", "application/ogg" -> "ogg"
        "audio/opus" -> "opus"
        "audio/aac", "audio/aac-adts" -> "aac"
        "audio/x-aiff", "audio/aiff" -> "aiff"
        "" -> ""
        else -> mime.substringAfter('/')
    }
}
