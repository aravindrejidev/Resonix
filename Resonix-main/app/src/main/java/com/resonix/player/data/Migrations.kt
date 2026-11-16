package com.resonix.player.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Every schema change since the 0.1.0 baseline (database version 3) goes here, oldest first.
 *
 * Write the SQL by hand against the exported schema JSON (`app/schemas/`), not by copying what Room
 * generates, and keep column definitions identical to the entity so Room's schema validation passes.
 * Example, for a new nullable column on `tracks` (database version 5):
 *
 * ```
 * private val MIGRATION_4_5 = object : Migration(4, 5) {
 *     override fun migrate(db: SupportSQLiteDatabase) {
 *         db.execSQL("ALTER TABLE tracks ADD COLUMN genre TEXT")
 *     }
 * }
 * ```
 */
object Migrations {

    /**
     * 3 -> 4: playlists. Two new tables, nothing existing is touched. The statements are exactly what
     * Room generates for [Playlist] and [PlaylistEntry].
     */
    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `playlists` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`createdAtSec` INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `playlist_tracks` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`playlistId` INTEGER NOT NULL, " +
                    "`trackUri` TEXT NOT NULL, " +
                    "`position` INTEGER NOT NULL)"
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_3_4)
}
