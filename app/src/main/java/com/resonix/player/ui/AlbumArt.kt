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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections

/**
 * Loads a track's embedded album art (not a separate art file — there
 * isn't one; see Track.albumArtUri's doc comment) with a small
 * in-memory cache so scrolling a list or reopening Now Playing for the
 * same track doesn't re-decode it every time.
 */
object AlbumArtLoader {
    // LruCache never accepts null values, so files with no embedded art
    // are tracked in a separate set instead of being cached as null.
    private val cache = LruCache<String, Bitmap>(64)
    private val knownNoArt: MutableSet<String> = Collections.synchronizedSet(HashSet())

    suspend fun load(context: Context, uri: Uri, sizePx: Int = 512): Bitmap? {
        val key = uri.toString()
        cache.get(key)?.let { return it }
        if (key in knownNoArt) return null

        val bitmap = withContext(Dispatchers.IO) {
            tryLoadThumbnail(context, uri, sizePx) ?: tryEmbeddedPicture(context, uri)
        }
        if (bitmap != null) cache.put(key, bitmap) else knownNoArt.add(key)
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

    private fun tryEmbeddedPicture(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val bytes = retriever.embeddedPicture ?: return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }
}

@Composable
fun rememberAlbumArt(uriString: String?): State<ImageBitmap?> {
    val context = LocalContext.current
    return produceState<ImageBitmap?>(initialValue = null, key1 = uriString) {
        value = null
        if (!uriString.isNullOrEmpty()) {
            val bitmap = AlbumArtLoader.load(context, Uri.parse(uriString))
            value = bitmap?.asImageBitmap()
        }
    }
}
