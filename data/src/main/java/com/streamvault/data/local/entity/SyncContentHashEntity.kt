package com.streamvault.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * SHA-256 of the last provider payload that was fully committed for one sync scope.
 *
 * A row is written only after the matching rows were committed, so an equal hash on the next
 * sync proves the committed rows already reflect that payload. Like sync checkpoints, these
 * rows are device-local cache state and are never exported to backups.
 */
@Entity(
    tableName = "sync_content_hashes",
    primaryKeys = ["provider_id", "scope", "key"],
    foreignKeys = [ForeignKey(
        entity = ProviderEntity::class,
        parentColumns = ["id"],
        childColumns = ["provider_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["provider_id"])]
)
data class SyncContentHashEntity(
    @ColumnInfo(name = "provider_id") val providerId: Long,
    @ColumnInfo(name = "scope") val scope: String,
    @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "sha256") val sha256: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

/** Scopes stored in [SyncContentHashEntity.scope]. */
object SyncContentHashScope {
    /** Provider xmltv.php guide payload. */
    const val EPG = "EPG"
    /** Xtream get_live_categories payload. */
    const val LIVE_CATEGORIES = "LIVE_CATEGORIES"
    /** Xtream get_live_streams payload, keyed by category ID or [LIVE_FULL_CATALOG_KEY]. */
    const val LIVE_CATEGORY = "LIVE_CATEGORY"

    const val SINGLE_KEY = "*"
    const val LIVE_FULL_CATALOG_KEY = "*"
}
