package com.resonix.player.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The library database: one row per audio file plus the list of folders the user has granted.
 *
 * ### Changing the schema
 * 1. Bump [version] and edit the entities.
 * 2. Add a `Migration(old, new)` to [Migrations.ALL]. A version bump without one makes Room throw
 *    on update, on purpose: that is better than silently wiping the user's folder list.
 * 3. Build once, then commit the new `app/schemas/com.resonix.player.data.AppDatabase/<version>.json`
 *    that Room exports. Those files are what migrations are written (and tested) against.
 */
@Database(
    entities = [Track::class, ScannedFolder::class, Playlist::class, PlaylistEntry::class],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao
    abstract fun scannedFolderDao(): ScannedFolderDao
    abstract fun playlistDao(): PlaylistDao

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
                    .addMigrations(*Migrations.ALL)
                    // Versions 1 and 2 were development schemas from before 0.1.0 and never had
                    // a migration. They are the only ones allowed to be rebuilt from scratch.
                    .fallbackToDestructiveMigrationFrom(1, 2)
                    // Installing an older APK over a newer one (a downgrade) cannot be migrated
                    // either; the library is just rescanned from the folders afterwards.
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
