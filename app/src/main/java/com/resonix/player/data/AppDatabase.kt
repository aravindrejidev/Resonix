package com.resonix.player.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Track::class, ScannedFolder::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao
    abstract fun scannedFolderDao(): ScannedFolderDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "resonix.db"
                )
                    // No Migration objects exist yet and there's no real
                    // user data to preserve at this stage — clears and
                    // recreates the DB on a version bump instead of
                    // crashing. Replace with a proper Migration before
                    // there's data worth keeping across an update.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
