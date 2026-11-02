package com.streamvault.feature.catalog.presentation.dashboard

import com.streamvault.domain.model.AppHomeDashboardShelf
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Series

internal fun resolveVisibleDashboardShelves(
    uiState: DashboardUiState
): List<AppHomeDashboardShelf> = AppHomeDashboardShelf
    .normalizeForStorage(uiState.homeDashboardShelves)
    .filter(uiState::hasContentFor)

internal fun DashboardUiState.hasContentFor(shelf: AppHomeDashboardShelf): Boolean = when (shelf) {
    AppHomeDashboardShelf.LIVE_MATCHES -> liveMatches.isNotEmpty()
    AppHomeDashboardShelf.FAVORITE_CHANNELS -> favoriteChannels.isNotEmpty()
    AppHomeDashboardShelf.RECENT_CHANNELS -> recentChannels.isNotEmpty()
    AppHomeDashboardShelf.LIVE_SHORTCUTS -> liveShortcuts.isNotEmpty()
    AppHomeDashboardShelf.CONTINUE_WATCHING -> continueWatching.isNotEmpty()
    AppHomeDashboardShelf.PINNED_CATEGORIES -> pinnedMovieCategories.isNotEmpty() || pinnedSeriesCategories.isNotEmpty()
    AppHomeDashboardShelf.NEW_ARABIC_MOVIES,
    AppHomeDashboardShelf.NEW_EGYPTIAN_MOVIES,
    AppHomeDashboardShelf.NEW_ENGLISH_MOVIES,
    AppHomeDashboardShelf.NEW_TURKISH_MOVIES -> languageMoviesFor(shelf).isNotEmpty()
    AppHomeDashboardShelf.NEW_ARABIC_SERIES,
    AppHomeDashboardShelf.NEW_EGYPTIAN_SERIES,
    AppHomeDashboardShelf.NEW_ENGLISH_SERIES,
    AppHomeDashboardShelf.NEW_TURKISH_SERIES -> languageSeriesFor(shelf).isNotEmpty()
    AppHomeDashboardShelf.RECENT_MOVIES -> recentMovies.isNotEmpty()
    AppHomeDashboardShelf.RECENT_SERIES -> recentSeries.isNotEmpty()
    AppHomeDashboardShelf.FAVORITE_MOVIES -> favoriteMovies.isNotEmpty()
    AppHomeDashboardShelf.FAVORITE_SERIES -> favoriteSeries.isNotEmpty()
    AppHomeDashboardShelf.TRAKT_WATCHLIST_MOVIES -> traktWatchlistMovies.isNotEmpty()
    AppHomeDashboardShelf.TRAKT_WATCHLIST_SERIES -> traktWatchlistSeries.isNotEmpty()
    AppHomeDashboardShelf.CONTINUE_WATCHING_MOVIES -> continueWatchingMovies.isNotEmpty()
    AppHomeDashboardShelf.CONTINUE_WATCHING_SERIES -> continueWatchingSeriesItems.isNotEmpty()
    AppHomeDashboardShelf.TOP_RATED_MOVIES -> topRatedMovies.isNotEmpty()
    AppHomeDashboardShelf.RECOMMENDED_MOVIES -> recommendedMovies.isNotEmpty()
}

internal fun DashboardUiState.languageMoviesFor(shelf: AppHomeDashboardShelf): List<Movie> =
    shelf.vodLanguage()?.let(languageMovies::get).orEmpty()

internal fun DashboardUiState.languageSeriesFor(shelf: AppHomeDashboardShelf): List<Series> =
    shelf.vodLanguage()?.let(languageSeries::get).orEmpty()

/** The language a "New … Movies/Series" shelf shows, or null for every other shelf. */
internal fun AppHomeDashboardShelf.vodLanguage(): VodLanguage? = when (this) {
    AppHomeDashboardShelf.NEW_ARABIC_MOVIES, AppHomeDashboardShelf.NEW_ARABIC_SERIES -> VodLanguage.ARABIC
    AppHomeDashboardShelf.NEW_EGYPTIAN_MOVIES, AppHomeDashboardShelf.NEW_EGYPTIAN_SERIES -> VodLanguage.EGYPTIAN
    AppHomeDashboardShelf.NEW_ENGLISH_MOVIES, AppHomeDashboardShelf.NEW_ENGLISH_SERIES -> VodLanguage.ENGLISH
    AppHomeDashboardShelf.NEW_TURKISH_MOVIES, AppHomeDashboardShelf.NEW_TURKISH_SERIES -> VodLanguage.TURKISH
    else -> null
}
