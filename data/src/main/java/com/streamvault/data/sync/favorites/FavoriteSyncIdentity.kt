package com.streamvault.data.sync.favorites

import com.streamvault.data.local.dao.FavoriteSyncDao
import com.streamvault.data.local.dao.FavoriteSyncLocalRow
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.provider.ProviderIdentityKeys
import com.streamvault.data.provider.toLegacyProvider
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.repository.ProviderSnapshotRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Item keys are provider-side ids, identical on every TV logged in to the same account:
 * `L:<streamId>`, `M:<streamId>` and `S:<providerSeriesId or seriesId>`. Zero ids are never synced.
 */
object FavoriteSyncItemKeys {
    const val LIVE = "LIVE"
    const val MOVIE = "MOVIE"
    const val SERIES = "SERIES"

    /** The sync list type for a favorites row, or null for types favorites sync ignores. */
    fun listType(contentType: String): String? = when (contentType) {
        ContentType.LIVE.name -> LIVE
        ContentType.MOVIE.name, ContentType.VOD.name -> MOVIE
        ContentType.SERIES.name -> SERIES
        else -> null
    }

    fun listType(contentType: ContentType): String? = listType(contentType.name)

    fun contentType(listType: String): ContentType = when (listType) {
        LIVE -> ContentType.LIVE
        SERIES -> ContentType.SERIES
        else -> ContentType.MOVIE
    }

    fun live(streamId: Long?): String? = streamId?.takeIf { it > 0L }?.let { "L:$it" }

    fun movie(streamId: Long?): String? = streamId?.takeIf { it > 0L }?.let { "M:$it" }

    fun series(providerSeriesId: String?, seriesId: Long?): String? =
        providerSeriesId?.trim()?.takeIf { it.isNotEmpty() }?.let { "S:$it" }
            ?: seriesId?.takeIf { it > 0L }?.let { "S:$it" }

    fun of(row: FavoriteSyncLocalRow): String? = when (listType(row.contentType)) {
        LIVE -> live(row.liveStreamId)
        MOVIE -> movie(row.movieStreamId)
        SERIES -> series(row.providerSeriesId, row.seriesId)
        else -> null
    }
}

/** Device-independent identity of a local provider, or null when its configuration is unreadable. */
fun interface ProviderIdentityKeyLookup {
    suspend fun identityKey(providerId: Long): String?
}

class SnapshotProviderIdentityKeyLookup @Inject constructor(
    private val providerSnapshotRepository: ProviderSnapshotRepository
) : ProviderIdentityKeyLookup {
    override suspend fun identityKey(providerId: Long): String? =
        providerSnapshotRepository.getSnapshot(providerId)
            ?.toLegacyProvider()
            ?.let(ProviderIdentityKeys::stableIdentityKey)
}

/** Maps local providers and content rows to their sync identities and back. */
@Singleton
class FavoriteSyncIdentity @Inject constructor(
    private val providerDao: ProviderDao,
    private val identityKeyLookup: ProviderIdentityKeyLookup,
    private val dao: FavoriteSyncDao
) {
    /** Local provider id to wire key, for every provider whose configuration can be read. */
    suspend fun localProviderKeys(keys: FavoriteSyncKeys): Map<Long, String> =
        providerDao.getAllSync().mapNotNull { entity ->
            providerWireKey(entity.id, keys)?.let { entity.id to it }
        }.toMap()

    suspend fun providerWireKey(providerId: Long, keys: FavoriteSyncKeys): String? =
        identityKeyLookup.identityKey(providerId)?.let(keys::providerWireKey)

    /** Item keys for local content ids of one sync list type; unknown or zero ids are omitted. */
    suspend fun itemKeysFor(providerId: Long, listType: String, contentIds: Collection<Long>): Map<Long, String> {
        val result = HashMap<Long, String>()
        contentIds.distinct().chunked(CHUNK).forEach { chunk ->
            when (listType) {
                FavoriteSyncItemKeys.LIVE -> dao.channelRefsByIds(providerId, chunk)
                    .forEach { ref -> FavoriteSyncItemKeys.live(ref.streamId)?.let { result[ref.id] = it } }
                FavoriteSyncItemKeys.MOVIE -> dao.movieRefsByIds(providerId, chunk)
                    .forEach { ref -> FavoriteSyncItemKeys.movie(ref.streamId)?.let { result[ref.id] = it } }
                FavoriteSyncItemKeys.SERIES -> dao.seriesRefsByIds(providerId, chunk)
                    .forEach { ref -> FavoriteSyncItemKeys.series(ref.providerSeriesId, ref.seriesId)?.let { result[ref.id] = it } }
            }
        }
        return result
    }

    /** Local content ids for item keys; keys whose content is not in this TV's catalog are absent. */
    suspend fun resolve(providerId: Long, itemKeys: Collection<String>): Map<String, Long> {
        val result = HashMap<String, Long>()
        val byPrefix = itemKeys.distinct().groupBy { it.substringBefore(':') }
        byPrefix["L"].orEmpty().mapNotNull { it.substringAfter(':').toLongOrNull() }.chunked(CHUNK).forEach { chunk ->
            dao.channelRefsByStreamIds(providerId, chunk).forEach { ref ->
                FavoriteSyncItemKeys.live(ref.streamId)?.let { result.putIfAbsent(it, ref.id) }
            }
        }
        byPrefix["M"].orEmpty().mapNotNull { it.substringAfter(':').toLongOrNull() }.chunked(CHUNK).forEach { chunk ->
            dao.movieRefsByStreamIds(providerId, chunk).forEach { ref ->
                FavoriteSyncItemKeys.movie(ref.streamId)?.let { result.putIfAbsent(it, ref.id) }
            }
        }
        val seriesKeys = byPrefix["S"].orEmpty()
        seriesKeys.map { it.substringAfter(':') }.chunked(CHUNK).forEach { chunk ->
            val wanted = chunk.map { "S:$it" }.toSet()
            dao.seriesRefsByRemoteIds(providerId, chunk, chunk.mapNotNull { it.toLongOrNull() }).forEach { ref ->
                // A row can match through either id; record every key it satisfies.
                listOfNotNull(
                    ref.providerSeriesId?.trim()?.takeIf { it.isNotEmpty() }?.let { "S:$it" },
                    ref.seriesId.takeIf { it > 0L }?.let { "S:$it" }
                ).filter { it in wanted }.forEach { key -> result.putIfAbsent(key, ref.id) }
            }
        }
        return result
    }

    private companion object {
        // Below SQLite's default 999 bound-parameter limit, leaving room for the provider id.
        const val CHUNK = 400
    }
}
