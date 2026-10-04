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

    /**
     * Tracks directly inside one folder — what PlaybackQueueManager loads
     * when a folder is played. Does not include subfolders.
     *
     * Filters on rootFolderUri *and* folderPath, not folderPath alone:
     * a root-level folder-scanned track (nothing in a subfolder) has
     * folderPath = "", same as every MediaStore-sourced track (which
     * has no folder concept at all) — without also matching
     * rootFolderUri, playing that root folder would accidentally pull
     * in the entire MediaStore-sourced library too. rootFolderUri is
     * blank for MediaStore tracks and always non-blank for folder-scanned
     * ones (even at the root), so this correctly keeps the two apart.
     */
    @Query(
        "SELECT * FROM tracks WHERE rootFolderUri = :rootFolderUri " +
                "AND folderPath = :folderPath ORDER BY title ASC"
    )
    suspend fun getTracksInFolder(rootFolderUri: String, folderPath: String): List<Track>

    @Query("SELECT contentUri FROM tracks WHERE rootFolderUri = :rootFolderUri")
    suspend fun getTrackUrisUnderRoot(rootFolderUri: String): List<String>

    /** Every track under a scanned root, any depth — what the Repo
     * screen shows when you open a folder. */
    @Query("SELECT * FROM tracks WHERE rootFolderUri = :rootFolderUri ORDER BY folderPath ASC, title ASC")
    fun getTracksUnderRootFlow(rootFolderUri: String): Flow<List<Track>>

    @Query("DELETE FROM tracks WHERE contentUri = :contentUri")
    suspend fun deleteByContentUri(contentUri: String)
}
