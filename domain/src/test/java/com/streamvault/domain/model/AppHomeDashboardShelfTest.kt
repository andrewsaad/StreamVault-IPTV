package com.streamvault.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppHomeDashboardShelfTest {

    @Test
    fun `defaultOrder starts with live matches and swaps recent rows for language rows`() {
        assertThat(AppHomeDashboardShelf.defaultOrder).containsExactly(
            AppHomeDashboardShelf.LIVE_MATCHES,
            AppHomeDashboardShelf.FAVORITE_CHANNELS,
            AppHomeDashboardShelf.RECENT_CHANNELS,
            AppHomeDashboardShelf.LIVE_SHORTCUTS,
            AppHomeDashboardShelf.CONTINUE_WATCHING,
            AppHomeDashboardShelf.PINNED_CATEGORIES,
            AppHomeDashboardShelf.NEW_ARABIC_MOVIES,
            AppHomeDashboardShelf.NEW_EGYPTIAN_MOVIES,
            AppHomeDashboardShelf.NEW_ENGLISH_MOVIES,
            AppHomeDashboardShelf.NEW_TURKISH_MOVIES,
            AppHomeDashboardShelf.NEW_ARABIC_SERIES,
            AppHomeDashboardShelf.NEW_EGYPTIAN_SERIES,
            AppHomeDashboardShelf.NEW_ENGLISH_SERIES,
            AppHomeDashboardShelf.NEW_TURKISH_SERIES
        ).inOrder()
    }

    @Test
    fun `recently added rows stay available in the catalog but not in the default order`() {
        assertThat(AppHomeDashboardShelf.catalogOrder).containsAtLeast(
            AppHomeDashboardShelf.RECENT_MOVIES,
            AppHomeDashboardShelf.RECENT_SERIES
        )
        assertThat(AppHomeDashboardShelf.defaultOrder).containsNoneOf(
            AppHomeDashboardShelf.RECENT_MOVIES,
            AppHomeDashboardShelf.RECENT_SERIES
        )
        assertThat(AppHomeDashboardShelf.catalogOrder).containsExactlyElementsIn(AppHomeDashboardShelf.entries)
        assertThat(AppHomeDashboardShelf.entries.map { it.storageValue }.toSet())
            .hasSize(AppHomeDashboardShelf.entries.size)
    }

    @Test
    fun `an untouched legacy default layout follows the new default`() {
        val legacy = listOf(
            AppHomeDashboardShelf.FAVORITE_CHANNELS,
            AppHomeDashboardShelf.RECENT_CHANNELS,
            AppHomeDashboardShelf.LIVE_SHORTCUTS,
            AppHomeDashboardShelf.CONTINUE_WATCHING,
            AppHomeDashboardShelf.PINNED_CATEGORIES,
            AppHomeDashboardShelf.RECENT_MOVIES,
            AppHomeDashboardShelf.RECENT_SERIES
        )
        val customised = legacy.dropLast(1)

        assertThat(AppHomeDashboardShelf.upgradeLegacyDefault(legacy)).isEqualTo(AppHomeDashboardShelf.defaultOrder)
        assertThat(AppHomeDashboardShelf.upgradeLegacyDefault(customised)).isEqualTo(customised)
    }

    @Test
    fun `normalizeForStorage removes duplicates and preserves order`() {
        val normalized = AppHomeDashboardShelf.normalizeForStorage(
            listOf(
                AppHomeDashboardShelf.RECENT_MOVIES,
                AppHomeDashboardShelf.FAVORITE_CHANNELS,
                AppHomeDashboardShelf.RECENT_MOVIES,
                AppHomeDashboardShelf.TOP_RATED_MOVIES
            )
        )

        assertThat(normalized).containsExactly(
            AppHomeDashboardShelf.RECENT_MOVIES,
            AppHomeDashboardShelf.FAVORITE_CHANNELS,
            AppHomeDashboardShelf.TOP_RATED_MOVIES
        ).inOrder()
    }

    @Test
    fun `displayOrder keeps enabled shelves first and appends hidden shelves`() {
        val ordered = AppHomeDashboardShelf.displayOrder(
            listOf(
                AppHomeDashboardShelf.RECOMMENDED_MOVIES,
                AppHomeDashboardShelf.FAVORITE_CHANNELS
            )
        )

        assertThat(ordered.take(2)).containsExactly(
            AppHomeDashboardShelf.RECOMMENDED_MOVIES,
            AppHomeDashboardShelf.FAVORITE_CHANNELS
        ).inOrder()
        assertThat(ordered).containsAtLeastElementsIn(AppHomeDashboardShelf.catalogOrder)
        assertThat(ordered).hasSize(AppHomeDashboardShelf.catalogOrder.size)
    }
}
