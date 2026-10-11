package com.resonix.player.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections

/**
 * Loads a track's embedded album art (there is no separate art file — see
 * Track.albumArtUri's doc comment) with an in-memory cache so scrolling a
 * list, or reopening Now Playing for the same track, never re-decodes it.
 *
 * Two things differ from a naive loader, both about memory:
 *  - embedded pictures are decoded *down-sampled* to the size actually
 *    needed (a 3000px cover would otherwise cost ~36 MB per row), and
 *  - the cache is limited by bytes, not by entry count.
 */
object AlbumArtLoader {
    private const val CACHE_BYTES = 24 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    // LruCache never accepts null values, so files with no embedded art
    // are tracked in a separate set instead of being cached as null.
    private val knownNoArt: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** Round requests to a few buckets so list rows and the player share entries. */
    private fun bucketFor(sizePx: Int): Int = when {
        sizePx <= 192 -> 192
        sizePx <= 384 -> 384
        sizePx <= 640 -> 640
        else -> 1024
    }

    private fun keyOf(uri: String, bucket: Int) = "$uri@$bucket"

    /** Synchronous cache lookup — lets a recomposed list row show its art with no placeholder flash. */
    fun peek(uri: String, sizePx: Int): Bitmap? = cache.get(keyOf(uri, bucketFor(sizePx)))

    suspend fun load(context: Context, uri: Uri, sizePx: Int = 512): Bitmap? {
        val bucket = bucketFor(sizePx)
        val uriString = uri.toString()
        val key = keyOf(uriString, bucket)
        cache.get(key)?.let { return it }
        if (uriString in knownNoArt) return null

        val bitmap = withContext(Dispatchers.IO) {
            tryLoadThumbnail(context, uri, bucket) ?: tryEmbeddedPicture(context, uri, bucket)
        }
        if (bitmap != null) cache.put(key, bitmap) else knownNoArt.add(uriString)
        return bitmap
    }

    private fun tryLoadThumbnail(context: Context, uri: Uri, sizePx: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null)
        } catch (e: Exception) {
            null
        }
    }

    private fun tryEmbeddedPicture(context: Context, uri: Uri, sizePx: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val bytes = retriever.embeddedPicture ?: return null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (longest / (sample * 2) >= sizePx) sample *= 2

            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}

/**
 * Album art for [uriString] (a MediaStore art URI *or* the audio file's own
 * content URI — embedded art is read from the file). `null` while loading
 * or when the file has no art.
 */
@Composable
fun rememberAlbumArt(uriString: String?, sizePx: Int = 512): State<ImageBitmap?> {
    val context = LocalContext.current
    val cached = remember(uriString, sizePx) {
        if (uriString.isNullOrEmpty()) null else AlbumArtLoader.peek(uriString, sizePx)?.asImageBitmap()
    }
    return produceState<ImageBitmap?>(initialValue = cached, uriString, sizePx) {
        if (value == null && !uriString.isNullOrEmpty()) {
            value = AlbumArtLoader.load(context, Uri.parse(uriString), sizePx)?.asImageBitmap()
        }
    }
}
