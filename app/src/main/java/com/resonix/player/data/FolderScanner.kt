package com.resonix.player.data

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.resonix.player.audio.AudioTrackInfo
import com.resonix.player.audio.TrackProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "FolderScanner"

class FolderScanner(private val context: Context) {

    /**
     * Recursively scans every audio file under [treeUri] (from
     * ActivityResultContracts.OpenDocumentTree — call
     * context.contentResolver.takePersistableUriPermission() on it first
     * so this survives app restarts) and upserts the results into Room.
     * Safe to call again later to re-scan the same tree: rows previously
     * scanned from this root that are no longer found get removed.
     *
     * title/artist/album/year come from the file's own container tags
     * (via TrackProber, which reads libavformat's metadata dictionary —
     * same as MediaStoreScanner's native probe). Falls back to the
     * filename for title, "Unknown artist"/"Unknown album" for those,
     * only when a file genuinely has no such tag.
     */
    suspend fun scanFolder(treeUri: Uri) = withContext(Dispatchers.IO) {
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
        if (rootDoc == null || !rootDoc.isDirectory) {
            Log.w(TAG, "$treeUri is not a usable directory")
            return@withContext
        }

        val dao = AppDatabase.getInstance(context).trackDao()
        val folderDao = AppDatabase.getInstance(context).scannedFolderDao()
        val prober = TrackProber()
        val seenUris = mutableListOf<String>()
        val rootUriString = treeUri.toString()

        try {
            walk(rootDoc, "", rootUriString, prober, dao, seenUris)

            dao.getTrackUrisUnderRoot(rootUriString)
                .filterNot { it in seenUris }
                .forEach { dao.deleteByContentUri(it) }

            folderDao.upsert(
                ScannedFolder(
                    rootUri = rootUriString,
                    displayName = rootDoc.name ?: "Folder",
                    lastScannedAtSec = System.currentTimeMillis() / 1000
                )
            )
        } finally {
            prober.release()
        }
    }

    private suspend fun walk(
        dir: DocumentFile,
        relativePath: String,
        rootUriString: String,
        prober: TrackProber,
        dao: TrackDao,
        seenUris: MutableList<String>
    ) {
        for (child in dir.listFiles()) {
            if (child.isDirectory) {
                val childPath = if (relativePath.isEmpty()) {
                    child.name ?: ""
                } else {
                    "$relativePath/${child.name}"
                }
                walk(child, childPath, rootUriString, prober, dao, seenUris)
            } else if (isAudioFile(child)) {
                val uriString = child.uri.toString()
                val info = probeViaFileDescriptor(prober, child.uri)

                dao.upsert(
                    Track(
                        contentUri = uriString,
                        title = info?.title?.takeIf { it.isNotBlank() }
                            ?: fileNameWithoutExtension(child.name),
                        artist = info?.artist?.takeIf { it.isNotBlank() } ?: "Unknown artist",
                        album = info?.album?.takeIf { it.isNotBlank() } ?: "Unknown album",
                        albumArtUri = "",
                        durationMs = info?.durationMs ?: 0L,
                        sampleRateHz = info?.sampleRateHz ?: 0,
                        bitDepth = info?.bitDepth ?: 0,
                        bitrateBps = info?.bitrateBps ?: 0L,
                        format = info?.format ?: "",
                        year = info?.year ?: 0,
                        dateAddedSec = child.lastModified() / 1000,
                        rootFolderUri = rootUriString,
                        folderPath = relativePath,
                        folderName = dir.name ?: ""
                    )
                )
                seenUris.add(uriString)
            }
        }
    }

    private fun isAudioFile(doc: DocumentFile): Boolean {
        return doc.type?.startsWith("audio/") == true
    }

    private fun fileNameWithoutExtension(name: String?): String {
        if (name.isNullOrEmpty()) return "Unknown title"
        val dotIndex = name.lastIndexOf('.')
        return if (dotIndex > 0) name.substring(0, dotIndex) else name
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
