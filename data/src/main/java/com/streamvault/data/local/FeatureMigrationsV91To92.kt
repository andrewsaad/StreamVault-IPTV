package com.streamvault.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds favorites sync storage: a device-independent id on custom groups and a key/value table
 * for the local replica and sync status. Existing groups keep a null id until they are synced.
 */
internal object FeatureMigrationsV91To92 {
    val MIGRATION_91_92 = object : Migration(91, 92) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE virtual_groups ADD COLUMN sync_uuid TEXT")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_virtual_groups_sync_uuid` ON `virtual_groups` (`sync_uuid`)"
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `favorite_sync_state` (
                    `key` TEXT NOT NULL,
                    `value` TEXT NOT NULL,
                    PRIMARY KEY(`key`)
                )
                """.trimIndent()
            )
        }
    }
}
