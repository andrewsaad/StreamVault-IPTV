package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.SyncContentHashDao
import com.streamvault.data.local.entity.SyncContentHashEntity
import com.streamvault.data.local.entity.SyncContentHashScope
import com.streamvault.data.remote.dto.XtreamCategory
import kotlinx.coroutines.test.runTest
import org.junit.Test

class XtreamLiveContentHashesTest {
    private val seed = XtreamLiveContentHashes.seed("build-1", "text=false;base64=false", setOf(3L), emptySet())
    private val categories = listOf(XtreamCategory("12", "News"), XtreamCategory("13", "Sports"))
    private val fresh = mapOf(
        XtreamLiveContentHashes.categoriesEntry(seed, categories),
        XtreamLiveContentHashes.streamsEntry(seed, "12", "raw-12"),
        XtreamLiveContentHashes.streamsEntry(seed, "13", "raw-13")
    )

    @Test
    fun `equal hashes with committed rows keep the catalog`() = runTest {
        assertThat(XtreamLiveContentHashes.isUnchanged(fresh, fresh.toMap()) { 40 }).isTrue()
    }

    @Test
    fun `equal hashes without committed rows still apply`() = runTest {
        assertThat(XtreamLiveContentHashes.isUnchanged(fresh, fresh.toMap()) { 0 }).isFalse()
    }

    @Test
    fun `a changed added or removed category applies without counting rows`() = runTest {
        val rowCountNeverRead: suspend () -> Int = { error("rows must not be counted when hashes differ") }
        val changed = fresh + XtreamLiveContentHashes.streamsEntry(seed, "13", "raw-13-new")
        val added = fresh + XtreamLiveContentHashes.streamsEntry(seed, "14", "raw-14")
        val removed = fresh - LiveContentHashKey(SyncContentHashScope.LIVE_CATEGORY, "13")

        assertThat(XtreamLiveContentHashes.isUnchanged(changed, fresh, rowCountNeverRead)).isFalse()
        assertThat(XtreamLiveContentHashes.isUnchanged(added, fresh, rowCountNeverRead)).isFalse()
        assertThat(XtreamLiveContentHashes.isUnchanged(removed, fresh, rowCountNeverRead)).isFalse()
        assertThat(XtreamLiveContentHashes.isUnchanged(null, fresh, rowCountNeverRead)).isFalse()
        assertThat(XtreamLiveContentHashes.isUnchanged(emptyMap(), emptyMap(), rowCountNeverRead)).isFalse()
    }

    @Test
    fun `settings that change mapping change every hash`() {
        val otherHidden = XtreamLiveContentHashes.seed("build-1", "text=false;base64=false", setOf(4L), emptySet())
        val otherBuild = XtreamLiveContentHashes.seed("build-2", "text=false;base64=false", setOf(3L), emptySet())
        val otherMapping = XtreamLiveContentHashes.seed("build-1", "text=true;base64=false", setOf(3L), emptySet())
        val base = XtreamLiveContentHashes.streamsEntry(seed, "12", "raw-12")

        listOf(otherHidden, otherBuild, otherMapping).forEach { other ->
            assertThat(XtreamLiveContentHashes.streamsEntry(other, "12", "raw-12")).isNotEqualTo(base)
            assertThat(XtreamLiveContentHashes.categoriesEntry(other, categories))
                .isNotEqualTo(XtreamLiveContentHashes.categoriesEntry(seed, categories))
        }
    }

    @Test
    fun `category order and names are part of the category hash`() {
        val base = XtreamLiveContentHashes.categoriesEntry(seed, categories)
        assertThat(XtreamLiveContentHashes.categoriesEntry(seed, categories.reversed())).isNotEqualTo(base)
        assertThat(XtreamLiveContentHashes.categoriesEntry(seed, listOf(XtreamCategory("12", "News 2"), categories[1])))
            .isNotEqualTo(base)
        assertThat(XtreamLiveContentHashes.categoriesEntry(seed, categories.map { it.copy() })).isEqualTo(base)
    }

    @Test
    fun `replace stores every live scope and null clears them`() = runTest {
        val dao = FakeSyncContentHashDao()
        dao.upsert(SyncContentHashEntity(7L, SyncContentHashScope.LIVE_CATEGORY, "99", "stale", 1L))
        dao.upsert(SyncContentHashEntity(7L, SyncContentHashScope.EPG, SyncContentHashScope.SINGLE_KEY, "guide", 1L))

        XtreamLiveContentHashes.replace(dao, 7L, fresh, now = 5L)
        assertThat(XtreamLiveContentHashes.read(dao, 7L)).isEqualTo(fresh)

        XtreamLiveContentHashes.replace(dao, 7L, null, now = 6L)
        assertThat(XtreamLiveContentHashes.read(dao, 7L)).isEmpty()
        assertThat(dao.get(7L, SyncContentHashScope.EPG, SyncContentHashScope.SINGLE_KEY)).isEqualTo("guide")
    }

    @Test
    fun `only automatic unforced non onboarding syncs may skip an unchanged catalog`() {
        assertThat(allowsLiveContentDelta(XtreamLiveSyncReason.BACKGROUND_STALE, force = false, trackInitialLiveOnboarding = false)).isTrue()
        assertThat(allowsLiveContentDelta(XtreamLiveSyncReason.BACKGROUND_STALE, force = true, trackInitialLiveOnboarding = false)).isFalse()
        assertThat(allowsLiveContentDelta(XtreamLiveSyncReason.BACKGROUND_STALE, force = false, trackInitialLiveOnboarding = true)).isFalse()
        listOf(XtreamLiveSyncReason.FOREGROUND, XtreamLiveSyncReason.MANUAL_SETTINGS, XtreamLiveSyncReason.INITIAL_ONBOARDING).forEach { reason ->
            assertThat(allowsLiveContentDelta(reason, force = false, trackInitialLiveOnboarding = false)).isFalse()
        }
    }

    private class FakeSyncContentHashDao : SyncContentHashDao() {
        private val rows = linkedMapOf<Triple<Long, String, String>, SyncContentHashEntity>()

        override suspend fun get(providerId: Long, scope: String, key: String): String? =
            rows[Triple(providerId, scope, key)]?.sha256

        override suspend fun getScope(providerId: Long, scope: String): List<SyncContentHashEntity> =
            rows.values.filter { it.providerId == providerId && it.scope == scope }

        override suspend fun upsert(row: SyncContentHashEntity) {
            rows[Triple(row.providerId, row.scope, row.key)] = row
        }

        override suspend fun upsertAll(rows: List<SyncContentHashEntity>) = rows.forEach { upsert(it) }

        override suspend fun deleteScope(providerId: Long, scope: String) {
            rows.keys.removeAll { it.first == providerId && it.second == scope }
        }

        override suspend fun deleteScopes(providerId: Long, scopes: List<String>) {
            rows.keys.removeAll { it.first == providerId && it.second in scopes }
        }
    }
}
