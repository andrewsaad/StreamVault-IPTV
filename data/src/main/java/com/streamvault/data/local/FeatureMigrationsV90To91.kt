package com.streamvault.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds payload hashes so unchanged guide and live-catalog downloads skip their commits. */
internal object FeatureMigrationsV90To91 {
    val MIGRATION_90_91 = object : Migration(90, 91) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE epg_sources ADD COLUMN content_sha256 TEXT")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `sync_content_hashes` (
                    `provider_id` INTEGER NOT NULL,
                    `scope` TEXT NOT NULL,
                    `key` TEXT NOT NULL,
                    `sha256` TEXT NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    PRIMARY KEY(`provider_id`, `scope`, `key`),
                    FOREIGN KEY(`provider_id`) REFERENCES `providers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_sync_content_hashes_provider_id` " +
                    "ON `sync_content_hashes` (`provider_id`)"
            )
        }
    }
}
