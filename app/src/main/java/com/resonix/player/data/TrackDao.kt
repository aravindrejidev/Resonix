package com.resonix.player.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    @Query("SELECT * FROM tracks ORDER BY title ASC")
    fun getAllTracks(): Flow<List<Track>>

    @Query("SELECT mediaStoreId FROM tracks WHERE mediaStoreId IS NOT NULL")
    suspend fun getAllIds(): List<Long>

    @Upsert
    suspend fun upsert(track: Track)

    @Query("DELETE FROM tracks WHERE mediaStoreId = :id")
    suspend fun deleteById(id: Long)

    /** Tracks directly inside one folder — what PlaybackQueueManager
     * loads when a folder is played. Does not include subfolders. */
    @Query("SELECT * FROM tracks WHERE folderPath = :folderPath ORDER BY title ASC")
    suspend fun getTracksInFolder(folderPath: String): List<Track>

    @Query("SELECT contentUri FROM tracks WHERE rootFolderUri = :rootFolderUri")
    suspend fun getTrackUrisUnderRoot(rootFolderUri: String): List<String>

    @Query("DELETE FROM tracks WHERE contentUri = :contentUri")
    suspend fun deleteByContentUri(contentUri: String)
}
