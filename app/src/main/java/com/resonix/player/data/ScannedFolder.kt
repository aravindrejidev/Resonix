package com.resonix.player.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One row per SAF folder tree the user has granted access to via
 * OpenDocumentTree. [rootUri] is the persisted tree URI — pass it back
 * to FolderScanner.scanFolder() to re-scan without asking the user to
 * pick the folder again. */
@Entity(tableName = "scanned_folders")
data class ScannedFolder(
    @PrimaryKey val rootUri: String,
    val displayName: String,
    val lastScannedAtSec: Long
)
