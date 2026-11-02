package com.streamvault.feature.catalog.presentation.dashboard

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.AppHomeDashboardShelf
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Series
import com.streamvault.feature.catalog.api.CatalogLiveMatch
import org.junit.Test

class DashboardHomeShelvesTest {

    @Test
    fun `resolveVisibleDashboardShelves keeps enabled order and filters empty shelves`() {
        val uiState = DashboardUiState(
            homeDashboardShelves = listOf(
                AppHomeDashboardShelf.RECOMMENDED_MOVIES,
                AppHomeDashboardShelf.FAVORITE_CHANNELS,
                AppHomeDashboardShelf.RECENT_MOVIES
            ),
            favoriteChannels = listOf(Channel(id = 1L, name = "News")),
            recentMovies = listOf(Movie(id = 2L, name = "Fresh")),
            recommendedMovies = emptyList()
        )

        val visibleShelves = resolveVisibleDashboardShelves(uiState)

        assertThat(visibleShelves).containsExactly(
            AppHomeDashboardShelf.FAVORITE_CHANNELS,
            AppHomeDashboardShelf.RECENT_MOVIES
        ).inOrder()
    }

    @Test
    fun `resolveVisibleDashboardShelves supports optional shelves when they have content`() {
        val uiState = DashboardUiState(
            homeDashboardShelves = listOf(
                AppHomeDashboardShelf.TOP_RATED_MOVIES,
                AppHomeDashboardShelf.FAVORITE_MOVIES
            ),
            topRatedMovies = listOf(Movie(id = 10L, name = "Top Pick")),
            favoriteMovies = listOf(Movie(id = 11L, name = "Saved Pick"))
        )

        val visibleShelves = resolveVisibleDashboardShelves(uiState)

        assertThat(visibleShelves).containsExactly(
            AppHomeDashboardShelf.TOP_RATED_MOVIES,
            AppHomeDashboardShelf.FAVORITE_MOVIES
        ).inOrder()
    }

    @Test
    fun `resolveVisibleDashboardShelves shows Trakt watchlist shelves when they have content`() {
        val uiState = DashboardUiState(
            homeDashboardShelves = listOf(
                AppHomeDashboardShelf.TRAKT_WATCHLIST_MOVIES,
                AppHomeDashboardShelf.TRAKT_WATCHLIST_SERIES
            ),
            traktWatchlistMovies = listOf(Movie(id = 30L, name = "Watchlisted movie")),
            traktWatchlistSeries = listOf(Series(id = 31L, name = "Watchlisted show"))
        )

        assertThat(resolveVisibleDashboardShelves(uiState)).containsExactly(
            AppHomeDashboardShelf.TRAKT_WATCHLIST_MOVIES,
            AppHomeDashboardShelf.TRAKT_WATCHLIST_SERIES
        ).inOrder()
    }

    @Test
    fun `default layout puts live matches on top and hides empty language rows`() {
        val uiState = DashboardUiState(
            homeDashboardShelves = AppHomeDashboardShelf.defaultOrder,
            favoriteChannels = listOf(Channel(id = 1L, name = "News")),
            liveMatches = listOf(
                CatalogLiveMatch(
                    channel = Channel(id = 2L, name = "beIN Sport 1 FHD Q"),
                    title = "Liverpool vs Arsenal",
                    league = null,
                    startTimeMs = 0L,
                    endTimeMs = 1L,
                    commentator = null
                )
            ),
            languageMovies = mapOf(
                VodLanguage.EGYPTIAN to listOf(Movie(id = 3L, name = "Egyptian")),
                VodLanguage.ARABIC to emptyList()
            ),
            languageSeries = mapOf(VodLanguage.TURKISH to listOf(Series(id = 4L, name = "Turkish"))),
            recentMovies = listOf(Movie(id = 5L, name = "Recent but not in the default layout"))
        )

        assertThat(resolveVisibleDashboardShelves(uiState)).containsExactly(
            AppHomeDashboardShelf.LIVE_MATCHES,
            AppHomeDashboardShelf.FAVORITE_CHANNELS,
            AppHomeDashboardShelf.NEW_EGYPTIAN_MOVIES,
            AppHomeDashboardShelf.NEW_TURKISH_SERIES
        ).inOrder()
        assertThat(uiState.languageMoviesFor(AppHomeDashboardShelf.NEW_EGYPTIAN_MOVIES).map { it.id })
            .containsExactly(3L)
        assertThat(uiState.languageSeriesFor(AppHomeDashboardShelf.NEW_TURKISH_SERIES).map { it.id })
            .containsExactly(4L)
    }

    @Test
    fun `live matches row is hidden when there are no matches`() {
        val uiState = DashboardUiState(homeDashboardShelves = listOf(AppHomeDashboardShelf.LIVE_MATCHES))

        assertThat(resolveVisibleDashboardShelves(uiState)).isEmpty()
    }

    @Test
    fun `every language shelf maps to its language and title`() {
        val languageShelves = AppHomeDashboardShelf.entries.filter { it.vodLanguage() != null }

        assertThat(languageShelves).hasSize(8)
        assertThat(languageShelves.map { it.languageShelfTitleResId() }.toSet()).hasSize(8)
        assertThat(AppHomeDashboardShelf.NEW_ENGLISH_SERIES.vodLanguage()).isEqualTo(VodLanguage.ENGLISH)
        assertThat(AppHomeDashboardShelf.RECENT_MOVIES.vodLanguage()).isNull()
    }

    @Test
    fun `resolveVisibleDashboardShelves shows pinned categories when they have content`() {
        val uiState = DashboardUiState(
            homeDashboardShelves = listOf(AppHomeDashboardShelf.PINNED_CATEGORIES),
            pinnedMovieCategories = mapOf("Action" to listOf(Movie(id = 20L, name = "Pinned Pick")))
        )

        assertThat(resolveVisibleDashboardShelves(uiState))
            .containsExactly(AppHomeDashboardShelf.PINNED_CATEGORIES)
    }
}
