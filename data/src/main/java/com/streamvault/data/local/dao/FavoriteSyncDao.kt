package com.streamvault.data.local.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.streamvault.data.local.entity.FavoriteSyncStateEntity
import com.streamvault.data.local.entity.VirtualGroupEntity

/** One favorite row together with the provider-side identity of the content it points at. */
data class FavoriteSyncLocalRow(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "content_id") val contentId: Long,
    @ColumnInfo(name = "content_type") val contentType: String,
    @ColumnInfo(name = "group_id") val groupId: Long?,
    @ColumnInfo(name = "position") val position: Int,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "live_stream_id") val liveStreamId: Long?,
    @ColumnInfo(name = "movie_stream_id") val movieStreamId: Long?,
    @ColumnInfo(name = "provider_series_id") val providerSeriesId: String?,
    @ColumnInfo(name = "series_id") val seriesId: Long?
)

data class FavoriteSyncStreamRef(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "stream_id") val streamId: Long
)

data class FavoriteSyncSeriesRef(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "provider_series_id") val providerSeriesId: String?,
    @ColumnInfo(name = "series_id") val seriesId: Long
)

/**
 * Narrow projections used by favorites sync. Every write here bypasses FavoriteRepository on
 * purpose: changes that arrive from other TVs must never be recorded as local user intent.
 */
@Dao
abstract class FavoriteSyncDao {
    @Query("SELECT value FROM favorite_sync_state WHERE `key` = :key LIMIT 1")
    abstract suspend fun getValue(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putValue(row: FavoriteSyncStateEntity)

    @Query("DELETE FROM favorite_sync_state WHERE `key` = :key")
    abstract suspend fun deleteValue(key: String)

    @Query(
        """
        SELECT f.id AS id, f.content_id AS content_id, f.content_type AS content_type,
               f.group_id AS group_id, f.position AS position, f.added_at AS added_at,
               c.stream_id AS live_stream_id, m.stream_id AS movie_stream_id,
               s.provider_series_id AS provider_series_id, s.series_id AS series_id
        FROM favorites AS f
        LEFT JOIN channels AS c
            ON f.content_type = 'LIVE' AND c.id = f.content_id AND c.provider_id = f.provider_id
        LEFT JOIN movies AS m
            ON f.content_type IN ('MOVIE', 'VOD') AND m.id = f.content_id AND m.provider_id = f.provider_id
        LEFT JOIN series AS s
            ON f.content_type = 'SERIES' AND s.id = f.content_id AND s.provider_id = f.provider_id
        WHERE f.provider_id = :providerId AND f.content_type IN ('LIVE', 'MOVIE', 'VOD', 'SERIES')
        ORDER BY f.position ASC, f.id ASC
        """
    )
    abstract suspend fun localFavorites(providerId: Long): List<FavoriteSyncLocalRow>

    @Query("SELECT id, stream_id FROM channels WHERE provider_id = :providerId AND id IN (:ids)")
    abstract suspend fun channelRefsByIds(providerId: Long, ids: List<Long>): List<FavoriteSyncStreamRef>

    @Query("SELECT id, stream_id FROM channels WHERE provider_id = :providerId AND stream_id IN (:streamIds)")
    abstract suspend fun channelRefsByStreamIds(providerId: Long, streamIds: List<Long>): List<FavoriteSyncStreamRef>

    @Query("SELECT id, stream_id FROM movies WHERE provider_id = :providerId AND id IN (:ids)")
    abstract suspend fun movieRefsByIds(providerId: Long, ids: List<Long>): List<FavoriteSyncStreamRef>

    @Query("SELECT id, stream_id FROM movies WHERE provider_id = :providerId AND stream_id IN (:streamIds)")
    abstract suspend fun movieRefsByStreamIds(providerId: Long, streamIds: List<Long>): List<FavoriteSyncStreamRef>

    @Query("SELECT id, provider_series_id, series_id FROM series WHERE provider_id = :providerId AND id IN (:ids)")
    abstract suspend fun seriesRefsByIds(providerId: Long, ids: List<Long>): List<FavoriteSyncSeriesRef>

    @Query(
        "SELECT id, provider_series_id, series_id FROM series WHERE provider_id = :providerId " +
            "AND (provider_series_id IN (:remoteIds) OR series_id IN (:numericIds))"
    )
    abstract suspend fun seriesRefsByRemoteIds(
        providerId: Long,
        remoteIds: List<String>,
        numericIds: List<Long>
    ): List<FavoriteSyncSeriesRef>

    @Query("SELECT * FROM virtual_groups WHERE provider_id = :providerId ORDER BY position ASC, id ASC")
    abstract suspend fun groups(providerId: Long): List<VirtualGroupEntity>

    @Query("SELECT * FROM virtual_groups WHERE id = :groupId LIMIT 1")
    abstract suspend fun groupById(groupId: Long): VirtualGroupEntity?

    @Query("UPDATE virtual_groups SET sync_uuid = :syncUuid WHERE id = :groupId")
    abstract suspend fun setGroupSyncUuid(groupId: Long, syncUuid: String)

    @Query("UPDATE virtual_groups SET name = :name, icon_emoji = :iconEmoji, position = :position WHERE id = :groupId")
    abstract suspend fun updateGroup(groupId: Long, name: String, iconEmoji: String?, position: Int)

    @Insert
    abstract suspend fun insertGroup(group: VirtualGroupEntity): Long

    @Query("DELETE FROM virtual_groups WHERE id = :groupId")
    abstract suspend fun deleteGroup(groupId: Long)

    @Query("DELETE FROM favorites WHERE group_id = :groupId")
    abstract suspend fun deleteFavoritesInGroup(groupId: Long)

    @Query("DELETE FROM favorites WHERE id = :favoriteId")
    abstract suspend fun deleteFavorite(favoriteId: Long)

    @Query("UPDATE favorites SET position = :position WHERE id = :favoriteId")
    abstract suspend fun updateFavoritePosition(favoriteId: Long, position: Int)
}
