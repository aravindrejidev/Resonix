package com.resonix.player.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey

/** A playlist the user made. The songs of it are the [PlaylistEntry] rows that point at it. */
@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAtSec: Long,
)

/**
 * One place in a playlist. A song is referred to by its [trackUri] (the same string as
 * [Track.contentUri]) and deliberately *not* through a foreign key to the tracks table: a rescan of a
 * folder may delete and re-create track rows, and that must never empty somebody's playlist. An entry
 * whose file is gone is simply not shown until the file comes back. The same song may appear twice.
 */
@Entity(tableName = "playlist_tracks")
data class PlaylistEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val trackUri: String,
    val position: Int,
)

/** A playlist with what the list of playlists shows: how many of its songs are in the library, and how long they are. */
data class PlaylistSummary(
    val id: Long,
    val name: String,
    val createdAtSec: Long,
    val trackCount: Int,
    val totalDurationMs: Long,
)

/** A song of a playlist together with the id of its entry (so one occurrence can be removed). */
data class PlaylistTrack(
    val entryId: Long,
    @Embedded val track: Track,
)
