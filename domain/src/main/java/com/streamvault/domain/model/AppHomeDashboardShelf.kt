package com.streamvault.domain.model

enum class AppHomeDashboardShelf(
    val storageValue: String,
    val defaultEnabled: Boolean
) {
    LIVE_MATCHES("live_matches", defaultEnabled = true),
    FAVORITE_CHANNELS("favorite_channels", defaultEnabled = true),
    RECENT_CHANNELS("recent_channels", defaultEnabled = true),
    LIVE_SHORTCUTS("live_shortcuts", defaultEnabled = true),
    CONTINUE_WATCHING("continue_watching", defaultEnabled = true),
    PINNED_CATEGORIES("pinned_categories", defaultEnabled = true),
    NEW_ARABIC_MOVIES("new_arabic_movies", defaultEnabled = true),
    NEW_EGYPTIAN_MOVIES("new_egyptian_movies", defaultEnabled = true),
    NEW_ENGLISH_MOVIES("new_english_movies", defaultEnabled = true),
    NEW_TURKISH_MOVIES("new_turkish_movies", defaultEnabled = true),
    NEW_ARABIC_SERIES("new_arabic_series", defaultEnabled = true),
    NEW_EGYPTIAN_SERIES("new_egyptian_series", defaultEnabled = true),
    NEW_ENGLISH_SERIES("new_english_series", defaultEnabled = true),
    NEW_TURKISH_SERIES("new_turkish_series", defaultEnabled = true),
    RECENT_MOVIES("recent_movies", defaultEnabled = false),
    RECENT_SERIES("recent_series", defaultEnabled = false),
    FAVORITE_MOVIES("favorite_movies", defaultEnabled = false),
    FAVORITE_SERIES("favorite_series", defaultEnabled = false),
    TRAKT_WATCHLIST_MOVIES("trakt_watchlist_movies", defaultEnabled = false),
    TRAKT_WATCHLIST_SERIES("trakt_watchlist_series", defaultEnabled = false),
    CONTINUE_WATCHING_MOVIES("continue_watching_movies", defaultEnabled = false),
    CONTINUE_WATCHING_SERIES("continue_watching_series", defaultEnabled = false),
    TOP_RATED_MOVIES("top_rated_movies", defaultEnabled = false),
    RECOMMENDED_MOVIES("recommended_movies", defaultEnabled = false);

    companion object {
        val catalogOrder: List<AppHomeDashboardShelf> = listOf(
            LIVE_MATCHES,
            FAVORITE_CHANNELS,
            RECENT_CHANNELS,
            LIVE_SHORTCUTS,
            CONTINUE_WATCHING,
            PINNED_CATEGORIES,
            NEW_ARABIC_MOVIES,
            NEW_EGYPTIAN_MOVIES,
            NEW_ENGLISH_MOVIES,
            NEW_TURKISH_MOVIES,
            NEW_ARABIC_SERIES,
            NEW_EGYPTIAN_SERIES,
            NEW_ENGLISH_SERIES,
            NEW_TURKISH_SERIES,
            RECENT_MOVIES,
            RECENT_SERIES,
            FAVORITE_MOVIES,
            FAVORITE_SERIES,
            TRAKT_WATCHLIST_MOVIES,
            TRAKT_WATCHLIST_SERIES,
            CONTINUE_WATCHING_MOVIES,
            CONTINUE_WATCHING_SERIES,
            TOP_RATED_MOVIES,
            RECOMMENDED_MOVIES
        )

        val defaultOrder: List<AppHomeDashboardShelf> = catalogOrder.filter { it.defaultEnabled }

        /** The default layout before the live-matches and language rows existed. */
        private val legacyDefaultOrder: List<AppHomeDashboardShelf> = listOf(
            FAVORITE_CHANNELS,
            RECENT_CHANNELS,
            LIVE_SHORTCUTS,
            CONTINUE_WATCHING,
            PINNED_CATEGORIES,
            RECENT_MOVIES,
            RECENT_SERIES
        )

        /**
         * A stored layout identical to the old default was never customised (it is what "Reset"
         * saved), so it follows the current default instead of hiding the new rows forever.
         */
        fun upgradeLegacyDefault(stored: List<AppHomeDashboardShelf>): List<AppHomeDashboardShelf> =
            if (stored == legacyDefaultOrder) defaultOrder else stored

        fun fromStorage(value: String?): AppHomeDashboardShelf? =
            catalogOrder.firstOrNull { it.storageValue.equals(value, ignoreCase = true) }

        fun normalizeForStorage(shelves: List<AppHomeDashboardShelf>): List<AppHomeDashboardShelf> {
            val unique = linkedSetOf<AppHomeDashboardShelf>()
            shelves.forEach { shelf ->
                if (shelf in catalogOrder) {
                    unique += shelf
                }
            }
            return unique.toList()
        }

        fun displayOrder(enabledShelves: List<AppHomeDashboardShelf>): List<AppHomeDashboardShelf> {
            val normalized = normalizeForStorage(enabledShelves)
            val hidden = catalogOrder.filterNot { it in normalized }
            return normalized + hidden
        }
    }
}
