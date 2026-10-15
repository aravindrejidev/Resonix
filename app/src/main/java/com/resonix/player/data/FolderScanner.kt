package com.resonix.player.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.resonix.player.audio.TrackProber
import com.resonix.player.util.AppMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
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
     * Each file is probed through an open file descriptor (see
     * [TrackMetadata]) for duration, format, sample rate, bit depth and
     * tags, with Android's MediaMetadataRetriever as a safety net.
     * Falls back to the filename for title and "Unknown artist" /
     * "Unknown album" only when a file genuinely has no such tag.
     *
     * Directory listings use one DocumentsContract query per folder
     * instead of DocumentFile.listFiles() — that is one provider
     * round-trip per *file attribute* and makes big libraries very slow.
     */
    suspend fun scanFolder(treeUri: Uri) = withContext(Dispatchers.IO) {
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
        if (rootDoc == null || !rootDoc.isDirectory) {
            Log.w(TAG, "$treeUri is not a usable directory")
            AppMessages.post("That folder can't be read")
            return@withContext
        }

        val dao = AppDatabase.getInstance(context).trackDao()
        val folderDao = AppDatabase.getInstance(context).scannedFolderDao()
        val prober = TrackProber()
        val seenUris = HashSet<String>()
        val rootUriString = treeUri.toString()
        val rootName = rootDoc.name ?: "Folder"

        try {
            // Register the folder right away (not only when the scan ends) so it appears on
            // Home while its songs are still coming in, and survives an interrupted scan.
            val existing = folderDao.getAllFolders().first().firstOrNull { it.rootUri == rootUriString }
            folderDao.upsert(
                ScannedFolder(
                    rootUri = rootUriString,
                    displayName = rootName,
                    lastScannedAtSec = existing?.lastScannedAtSec ?: 0L
                )
            )

            val stats = ScanStats()
            walk(
                treeUri = treeUri,
                dirDocumentId = DocumentsContract.getTreeDocumentId(treeUri),
                dirName = rootName,
                relativePath = "",
                rootUriString = rootUriString,
                prober = prober,
                dao = dao,
                seenUris = seenUris,
                stats = stats,
            )

            dao.getTrackUrisUnderRoot(rootUriString)
                .filterNot { it in seenUris }
                .forEach { dao.deleteByContentUri(it) }

            folderDao.upsert(
                ScannedFolder(
                    rootUri = rootUriString,
                    displayName = rootName,
                    lastScannedAtSec = System.currentTimeMillis() / 1000
                )
            )

            when {
                stats.songs == 0 -> AppMessages.post("No songs found in “$rootName”")
                stats.unreadable > 0 -> AppMessages.post(
                    "${stats.songs} songs found in “$rootName” — ${stats.unreadable} could not be read fully"
                )
                else -> AppMessages.post("${stats.songs} songs found in “$rootName”")
            }
        } finally {
            prober.release()
        }
    }

    private class ScanStats {
        var songs = 0
        var unreadable = 0
    }

    private class Child(
        val documentId: String,
        val name: String,
        val mimeType: String?,
        val lastModifiedMs: Long,
    ) {
        val isDirectory: Boolean get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
    }

    private suspend fun walk(
        treeUri: Uri,
        dirDocumentId: String,
        dirName: String,
        relativePath: String,
        rootUriString: String,
        prober: TrackProber,
        dao: TrackDao,
        seenUris: MutableSet<String>,
        stats: ScanStats,
    ) {
        for (child in listChildren(treeUri, dirDocumentId)) {
            currentCoroutineContext().ensureActive()  // let a cancelled scan stop promptly

            if (child.isDirectory) {
                if (child.name.startsWith(".")) continue  // .thumbnails, .trash …
                val childPath = if (relativePath.isEmpty()) child.name else "$relativePath/${child.name}"
                walk(
                    treeUri, child.documentId, child.name, childPath,
                    rootUriString, prober, dao, seenUris, stats,
                )
            } else if (TrackMetadata.isAudioFile(child.name, child.mimeType)) {
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.documentId)
                val uriString = uri.toString()
                val info = TrackMetadata.probe(context, prober, uri)
                if (info == null || info.durationMs <= 0L) stats.unreadable++

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
                        dateAddedSec = child.lastModifiedMs / 1000,
                        rootFolderUri = rootUriString,
                        folderPath = relativePath,
                        folderName = dirName
                    )
                )
                seenUris.add(uriString)
                stats.songs++
            }
        }
    }

    private fun listChildren(treeUri: Uri, parentDocumentId: String): List<Child> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val result = ArrayList<Child>()
        try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(0) ?: continue
                    result.add(
                        Child(
                            documentId = documentId,
                            name = cursor.getString(1) ?: "",
                            mimeType = cursor.getString(2),
                            lastModifiedMs = if (cursor.isNull(3)) 0L else cursor.getLong(3),
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't list $childrenUri", e)
        }
        return result
    }

    private fun fileNameWithoutExtension(name: String?): String {
        if (name.isNullOrEmpty()) return "Unknown title"
        val dotIndex = name.lastIndexOf('.')
        return if (dotIndex > 0) name.substring(0, dotIndex) else name
    }
}
