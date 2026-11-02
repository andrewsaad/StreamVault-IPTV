package com.streamvault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.streamvault.data.local.entity.SyncContentHashEntity

@Dao
abstract class SyncContentHashDao {
    @Query("SELECT sha256 FROM sync_content_hashes WHERE provider_id = :providerId AND scope = :scope AND `key` = :key LIMIT 1")
    abstract suspend fun get(providerId: Long, scope: String, key: String): String?

    @Query("SELECT * FROM sync_content_hashes WHERE provider_id = :providerId AND scope = :scope")
    abstract suspend fun getScope(providerId: Long, scope: String): List<SyncContentHashEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(row: SyncContentHashEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAll(rows: List<SyncContentHashEntity>)

    @Query("DELETE FROM sync_content_hashes WHERE provider_id = :providerId AND scope = :scope")
    abstract suspend fun deleteScope(providerId: Long, scope: String)

    @Query("DELETE FROM sync_content_hashes WHERE provider_id = :providerId AND scope IN (:scopes)")
    abstract suspend fun deleteScopes(providerId: Long, scopes: List<String>)

    /** Replaces every key of [scope] so keys for removed categories cannot linger. */
    @Transaction
    open suspend fun replaceScope(providerId: Long, scope: String, rows: List<SyncContentHashEntity>) {
        deleteScope(providerId, scope)
        if (rows.isNotEmpty()) upsertAll(rows)
    }
}
