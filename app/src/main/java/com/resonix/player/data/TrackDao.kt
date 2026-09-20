package com.resonix.player.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    @Query("SELECT * FROM tracks ORDER BY title ASC")
    fun getAllTracks(): Flow<List<Track>>

    @Query("SELECT mediaStoreId FROM tracks")
    suspend fun getAllIds(): List<Long>

    @Upsert
    suspend fun upsert(track: Track)

    @Query("DELETE FROM tracks WHERE mediaStoreId = :id")
    suspend fun deleteById(id: Long)
}
