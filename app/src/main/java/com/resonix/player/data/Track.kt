package com.resonix.player.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per MediaStore audio file. [contentUri] and [albumArtUri] are
 * the same MediaStore content:// URI string: it's what playback opens
 * via ContentResolver to get a file descriptor, and it's also what
 * ContentResolver.loadThumbnail() takes to fetch embedded album art —
 * there's no separate dedicated "album art" URI on modern Android (that
 * old MediaStore column was deprecated in API 29).
 */
@Entity(tableName = "tracks")
data class Track(
    @PrimaryKey val mediaStoreId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtUri: String,
    val contentUri: String,
    val durationMs: Long,
    val sampleRateHz: Int,
    val bitDepth: Int,
    val bitrateBps: Long,
    val format: String,
    val dateAddedSec: Long
)
