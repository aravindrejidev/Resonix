package com.resonix.player.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per audio file, from either the MediaStore scanner or the SAF
 * folder scanner. [contentUri] is the primary key rather than
 * [mediaStoreId] so both sources share one table — a SAF document tree
 * file has no MediaStore numeric ID. [albumArtUri] is the same string as
 * [contentUri] for MediaStore rows (what ContentResolver.loadThumbnail()
 * takes); folder-scanned rows don't have one (embedded-art extraction
 * isn't implemented yet, so this is blank for them).
 *
 * [rootFolderUri]/[folderPath]/[folderName] are only populated for
 * folder-scanned rows: rootFolderUri is the SAF tree the user granted
 * (constant across one folder's whole scan, used to scope rescans/
 * cleanup), folderPath is this file's immediate containing folder
 * relative to that root (e.g. "Rock/Metal" — what PlaybackQueueManager
 * groups by), folderName is just that folder's display name.
 */
@Entity(tableName = "tracks")
data class Track(
    @PrimaryKey val contentUri: String,
    val mediaStoreId: Long? = null,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtUri: String,
    val durationMs: Long,
    val sampleRateHz: Int,
    val bitDepth: Int,
    val bitrateBps: Long,
    val format: String,
    val year: Int = 0,
    val dateAddedSec: Long,
    val rootFolderUri: String = "",
    val folderPath: String = "",
    val folderName: String = ""
)
