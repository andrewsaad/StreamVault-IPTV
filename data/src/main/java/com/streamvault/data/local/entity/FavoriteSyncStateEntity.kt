package com.streamvault.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Device-local favorites sync state: the merged replica document, server ETag, hybrid clock and
 * status values. Pairing secrets never live here; they stay in encrypted preferences. Rows are
 * not exported to backups.
 */
@Entity(tableName = "favorite_sync_state")
data class FavoriteSyncStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "value") val value: String
)
