package com.resonix.player.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ScannedFolderDao {

    @Query("SELECT * FROM scanned_folders ORDER BY displayName ASC")
    fun getAllFolders(): Flow<List<ScannedFolder>>

    @Upsert
    suspend fun upsert(folder: ScannedFolder)
}
