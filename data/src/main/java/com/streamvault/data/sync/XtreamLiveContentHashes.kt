package com.streamvault.data.sync

import com.streamvault.data.local.dao.SyncContentHashDao
import com.streamvault.data.local.entity.SyncContentHashEntity
import com.streamvault.data.local.entity.SyncContentHashScope
import com.streamvault.data.remote.dto.XtreamCategory
import com.streamvault.data.util.ContentDigest

/** One stored hash: [scope] is a [SyncContentHashScope] value, [key] a category ID or "*". */
internal data class LiveContentHashKey(val scope: String, val key: String)

/**
 * Fingerprints of the Xtream Live payloads behind a committed catalog.
 *
 * Every hash is seeded with [FORMAT] and a caller seed (app build, mapping profile, hidden
 * categories), so the same bytes mapped under different settings never compare as unchanged.
 * Bump [FORMAT] whenever Live mapping or staging changes what a payload turns into.
 */
internal object XtreamLiveContentHashes {
    const val FORMAT = "xtream-live-v1"
    private val LIVE_SCOPES = listOf(SyncContentHashScope.LIVE_CATEGORIES, SyncContentHashScope.LIVE_CATEGORY)

    fun seed(
        appBuild: String,
        mappingProfile: String,
        hiddenCategoryIds: Set<Long>,
        requiredHiddenCategoryIds: Set<Long>
    ): String = buildString {
        append("build=").append(appBuild)
        append(";mapping=").append(mappingProfile)
        append(";hidden=").append(hiddenCategoryIds.sorted().joinToString(","))
        append(";required=").append(requiredHiddenCategoryIds.sorted().joinToString(","))
    }

    /** Category order is part of the hash because it drives the displayed category order. */
    fun categoriesEntry(seed: String, categories: List<XtreamCategory>): Pair<LiveContentHashKey, String> {
        val digest = ContentDigest.seeded(FORMAT, seed, SyncContentHashScope.LIVE_CATEGORIES)
        categories.forEach { category ->
            listOf(category.categoryId, category.categoryName, category.parentId.toString(), category.isAdult.toString())
                .forEach { field ->
                    digest.update(field.toByteArray(Charsets.UTF_8))
                    digest.update(0)
                }
            digest.update(1)
        }
        return LiveContentHashKey(SyncContentHashScope.LIVE_CATEGORIES, SyncContentHashScope.SINGLE_KEY) to
            ContentDigest.hex(digest)
    }

    fun streamsEntry(seed: String, key: String, rawSha256: String): Pair<LiveContentHashKey, String> =
        LiveContentHashKey(SyncContentHashScope.LIVE_CATEGORY, key) to
            ContentDigest.sha256Hex(FORMAT, seed, SyncContentHashScope.LIVE_CATEGORY, key, rawSha256)

    /**
     * True only when every fresh hash equals the stored set exactly (no added or removed
     * categories) and the committed catalog still has rows to keep.
     */
    suspend inline fun isUnchanged(
        fresh: Map<LiveContentHashKey, String>?,
        stored: Map<LiveContentHashKey, String>,
        committedRowCount: suspend () -> Int
    ): Boolean = !fresh.isNullOrEmpty() && fresh == stored && committedRowCount() > 0

    suspend fun read(dao: SyncContentHashDao, providerId: Long): Map<LiveContentHashKey, String> =
        LIVE_SCOPES.flatMap { scope -> dao.getScope(providerId, scope) }
            .associate { row -> LiveContentHashKey(row.scope, row.key) to row.sha256 }

    /** Replaces all Live hashes; null clears them so a later sync cannot skip on stale data. */
    suspend fun replace(dao: SyncContentHashDao, providerId: Long, hashes: Map<LiveContentHashKey, String>?, now: Long) {
        LIVE_SCOPES.forEach { scope ->
            dao.replaceScope(
                providerId,
                scope,
                hashes.orEmpty()
                    .filterKeys { it.scope == scope }
                    .map { (key, sha256) -> SyncContentHashEntity(providerId, scope, key.key, sha256, now) }
            )
        }
    }
}
