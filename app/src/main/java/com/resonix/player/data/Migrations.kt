package com.resonix.player.data

import androidx.room.migration.Migration

/**
 * Every schema change since the 0.1.0 baseline (database version 3) goes here, oldest first.
 *
 * Example, for a new nullable column on `tracks` (database version 4):
 *
 * ```
 * private val MIGRATION_3_4 = object : Migration(3, 4) {
 *     override fun migrate(db: SupportSQLiteDatabase) {
 *         db.execSQL("ALTER TABLE tracks ADD COLUMN genre TEXT")
 *     }
 * }
 * val ALL = arrayOf<Migration>(MIGRATION_3_4)
 * ```
 *
 * Write the SQL by hand against the exported schema JSON, not by copying what Room generates,
 * and keep column defaults identical to the entity so Room's schema validation passes.
 */
object Migrations {
    val ALL: Array<Migration> = emptyArray()
}
