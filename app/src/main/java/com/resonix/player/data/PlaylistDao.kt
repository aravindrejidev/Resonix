package com.resonix.player.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class PlaylistDao {

    /** Every playlist with its song count and length; only songs that are still in the library are counted. */
    @Query(
        "SELECT p.id AS id, p.name AS name, p.createdAtSec AS createdAtSec, " +
            "COUNT(t.contentUri) AS trackCount, COALESCE(SUM(t.durationMs), 0) AS totalDurationMs " +
            "FROM playlists p " +
            "LEFT JOIN playlist_tracks e ON e.playlistId = p.id " +
            "LEFT JOIN tracks t ON t.contentUri = e.trackUri " +
            "GROUP BY p.id ORDER BY p.name COLLATE NOCASE ASC"
    )
    abstract fun observePlaylists(): Flow<List<PlaylistSummary>>

    /** The songs of one playlist, in playlist order. Entries whose song is not in the library are left out. */
    @Query(
        "SELECT e.id AS entryId, t.* FROM playlist_tracks e " +
            "INNER JOIN tracks t ON t.contentUri = e.trackUri " +
            "WHERE e.playlistId = :playlistId ORDER BY e.position ASC, e.id ASC"
    )
    abstract fun observeTracks(playlistId: Long): Flow<List<PlaylistTrack>>

    /** Same as [observeTracks], once, without the entry ids — what gets handed to the player. */
    @Query(
        "SELECT t.* FROM playlist_tracks e " +
            "INNER JOIN tracks t ON t.contentUri = e.trackUri " +
            "WHERE e.playlistId = :playlistId ORDER BY e.position ASC, e.id ASC"
    )
    abstract suspend fun getTracks(playlistId: Long): List<Track>

    @Query("SELECT id FROM playlists WHERE name = :name COLLATE NOCASE LIMIT 1")
    abstract suspend fun findIdByName(name: String): Long?

    @Query("SELECT name FROM playlists WHERE id = :id")
    abstract suspend fun getName(id: Long): String?

    @Insert
    abstract suspend fun insertPlaylist(playlist: Playlist): Long

    @Insert
    abstract suspend fun insertEntries(entries: List<PlaylistEntry>)

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_tracks WHERE playlistId = :playlistId")
    abstract suspend fun lastPosition(playlistId: Long): Int

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    abstract suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM playlist_tracks WHERE id = :entryId")
    abstract suspend fun deleteEntry(entryId: Long)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    abstract suspend fun deleteEntriesOf(playlistId: Long)

    @Query("DELETE FROM playlists WHERE id = :id")
    abstract suspend fun deletePlaylistRow(id: Long)

    /** Adds songs at the end of a playlist, in the given order. Returns how many were added. */
    @Transaction
    open suspend fun appendTracks(playlistId: Long, trackUris: List<String>): Int {
        if (trackUris.isEmpty()) return 0
        var position = lastPosition(playlistId) + 1
        insertEntries(trackUris.map { PlaylistEntry(playlistId = playlistId, trackUri = it, position = position++) })
        return trackUris.size
    }

    /** Creates a playlist, optionally with its first songs. Returns the new id. */
    @Transaction
    open suspend fun createWithTracks(name: String, createdAtSec: Long, trackUris: List<String>): Long {
        val id = insertPlaylist(Playlist(name = name, createdAtSec = createdAtSec))
        appendTracks(id, trackUris)
        return id
    }

    @Transaction
    open suspend fun deletePlaylist(id: Long) {
        deleteEntriesOf(id)
        deletePlaylistRow(id)
    }
}
