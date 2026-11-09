package com.streamvault.app.navigation

import android.app.SearchManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import com.google.common.truth.Truth.assertThat
import com.streamvault.app.MainActivity
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.ExternalNavigationRequest
import com.streamvault.core.navigation.PlayerNavigationRequest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ExternalNavigationRequestParserTest {
    private val context = RuntimeEnvironment.getApplication()
    private val parser = ExternalNavigationRequestParser(context)

    @Test
    fun searchIntentBecomesTypedSearchRequest() {
        val intent = Intent(Intent.ACTION_SEARCH)
            .putExtra(SearchManager.QUERY, " sports ")

        assertThat(parser.parse(intent))
            .isEqualTo(ExternalNavigationRequest.Search("sports"))
    }

    @Test
    fun malformedLegacyRouteFallsBackHome() {
        val intent = Intent().putExtra(MainActivity.EXTRA_EXTERNAL_ROUTE, "bad/route")

        assertThat(parser.parse(intent)).isEqualTo(
            ExternalNavigationRequest.Destination(AppDestination.Home)
        )
    }

    @Test
    fun downloadsLegacyRouteOpensDownloads() {
        val intent = Intent().putExtra(MainActivity.EXTRA_EXTERNAL_ROUTE, "downloads")

        assertThat(parser.parse(intent)).isEqualTo(
            ExternalNavigationRequest.Destination(AppDestination.Downloads)
        )
    }

    @Test
    fun advertisedExternalRoutesAreAcceptedByTheLegacyParser() {
        @Suppress("DEPRECATION")
        val activityInfo = context.packageManager.getActivityInfo(
            ComponentName(context, MainActivity::class.java),
            PackageManager.GET_META_DATA
        )
        val routes = checkNotNull(activityInfo.metaData)
            .getString("com.streamvault.app.EXTERNAL_ROUTES")
            .orEmpty()
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)

        assertThat(routes).containsExactly("home", "plugins", "downloads").inOrder()
        routes.forEach { route ->
            assertThat(AppRouteCodec.decodeLegacyExternalRoute(route)).isNotNull()
        }
    }

    @Test
    fun playlistMimeWithContentUriBecomesImportRequest() {
        val uri = Uri.parse("content://example/playlist")
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "audio/x-mpegurl")

        assertThat(parser.parse(intent))
            .isEqualTo(ExternalNavigationRequest.ImportM3u(uri.toString()))
    }

    @Test
    fun playlistPathWithoutMimeBecomesImportRequest() {
        val uri = Uri.parse("file:///sdcard/playlist.m3u8")
        val intent = Intent(Intent.ACTION_VIEW).setData(uri)

        assertThat(parser.parse(intent))
            .isEqualTo(ExternalNavigationRequest.ImportM3u(uri.toString()))
    }

    @Test
    fun backupJsonStreamBecomesImportRequest() {
        val uri = Uri.parse("content://example/backup.json")
        val intent = Intent(Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, uri)

        assertThat(parser.parse(intent))
            .isEqualTo(ExternalNavigationRequest.ImportBackup(uri.toString()))
    }

    @Test
    fun playerExtraTakesPrecedence() {
        val request = PlayerNavigationRequest(
            streamUrl = "https://example.com/live.m3u8",
            title = "News"
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .putExtra(MainActivity.EXTRA_PLAYER_REQUEST, request)
            .putExtra(MainActivity.EXTRA_EXTERNAL_ROUTE, "home")

        assertThat(parser.parse(intent))
            .isEqualTo(ExternalNavigationRequest.Player(request))
    }

    @Test
    fun typedDestinationExtraBecomesDestinationRequest() {
        val intent = Intent()
            .putExtra(
                MainActivity.EXTRA_EXTERNAL_DESTINATION,
                AppDestination.Settings(backupUri = "content://example/backup.json")
            )

        assertThat(parser.parse(intent)).isEqualTo(
            ExternalNavigationRequest.Destination(
                AppDestination.Settings(backupUri = "content://example/backup.json")
            )
        )
    }

    @Test
    fun genericViewFallsBackHome() {
        val intent = Intent(Intent.ACTION_VIEW)
            .setData(Uri.parse("https://example.com/watch/42"))

        assertThat(parser.parse(intent)).isEqualTo(
            ExternalNavigationRequest.Destination(AppDestination.Home)
        )
    }

    @Test
    fun blankSearchAssistantAndVoiceQueriesProduceNoRequest() {
        assertThat(
            parser.parse(Intent(Intent.ACTION_SEARCH).putExtra(SearchManager.QUERY, "  "))
        ).isNull()
        assertThat(
            parser.parse(Intent(Intent.ACTION_ASSIST).putExtra(SearchManager.QUERY, ""))
        ).isNull()
        assertThat(
            parser.parse(
                Intent(RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE)
                    .putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(" "))
            )
        ).isNull()
    }

    private fun roundTrip(request: PlayerNavigationRequest): ExternalNavigationRequest? {
        val intent = PlayerRequestIntentExtras.write(Intent(Intent.ACTION_VIEW), request)
        val parsed = Intent.parseUri(intent.toUri(Intent.URI_INTENT_SCHEME), Intent.URI_INTENT_SCHEME)
        return parser.parse(parsed)
    }

    @Test
    fun plainExtrasRoundTripLiveRequestThroughIntentUri() {
        val request = PlayerNavigationRequest(
            streamUrl = "https://example.com/live/1.m3u8",
            title = "News",
            channelId = "news.1",
            internalId = 7L,
            categoryId = 3L,
            providerId = 2L,
            contentType = "LIVE"
        )

        assertThat(roundTrip(request)).isEqualTo(ExternalNavigationRequest.Player(request))
    }

    @Test
    fun plainExtrasRoundTripMovieRequestThroughIntentUri() {
        val request = PlayerNavigationRequest(
            streamUrl = "https://example.com/movie/9.mp4?token=a&b=c",
            title = "Movie & Co",
            internalId = 9L,
            providerId = 2L,
            contentType = "MOVIE",
            artworkUrl = "https://example.com/poster.jpg",
            remoteContentId = "tt123"
        )

        assertThat(roundTrip(request)).isEqualTo(ExternalNavigationRequest.Player(request))
    }

    @Test
    fun plainExtrasRoundTripEpisodeRequestThroughIntentUri() {
        val request = PlayerNavigationRequest(
            streamUrl = "https://example.com/series/10.mkv",
            title = "Episode 10",
            internalId = 10L,
            providerId = 5L,
            contentType = "SERIES_EPISODE",
            seriesId = 77L,
            seasonNumber = 2,
            episodeNumber = 4,
            episodeId = 10L,
            remoteContentId = "ep-10",
            remoteSeriesId = "series-77"
        )

        assertThat(roundTrip(request)).isEqualTo(ExternalNavigationRequest.Player(request))
    }

    @Test
    fun serializableExtraWinsOverPlainExtras() {
        val serialized = PlayerNavigationRequest(streamUrl = "https://example.com/a.m3u8", title = "A")
        val plain = PlayerNavigationRequest(streamUrl = "https://example.com/b.m3u8", title = "B")
        val intent = PlayerRequestIntentExtras.write(Intent(Intent.ACTION_VIEW), plain)
            .putExtra(MainActivity.EXTRA_PLAYER_REQUEST, serialized)

        assertThat(parser.parse(intent)).isEqualTo(ExternalNavigationRequest.Player(serialized))
    }

    private fun validPlainIntent(): Intent = PlayerRequestIntentExtras.write(
        Intent(Intent.ACTION_VIEW),
        PlayerNavigationRequest(
            streamUrl = "https://example.com/a.m3u8",
            title = "A",
            providerId = 1L,
            contentType = "MOVIE"
        )
    )

    private fun assertFallsBackHome(intent: Intent) {
        assertThat(parser.parse(intent)).isEqualTo(
            ExternalNavigationRequest.Destination(AppDestination.Home)
        )
    }

    @Test
    fun plainExtrasWithUnknownContentTypeAreRejected() {
        assertFallsBackHome(validPlainIntent().putExtra(PlayerRequestIntentExtras.CONTENT_TYPE, "BOGUS"))
    }

    @Test
    fun plainExtrasWithNegativeIdsAreRejected() {
        assertFallsBackHome(validPlainIntent().putExtra(PlayerRequestIntentExtras.PROVIDER_ID, -5L))
        assertFallsBackHome(validPlainIntent().putExtra(PlayerRequestIntentExtras.SERIES_ID, -1L))
        assertFallsBackHome(validPlainIntent().putExtra(PlayerRequestIntentExtras.SEASON_NUMBER, -2))
        assertFallsBackHome(validPlainIntent().putExtra(PlayerRequestIntentExtras.INTERNAL_ID, -2L))
    }

    @Test
    fun plainExtrasWithWrongTypesAreRejected() {
        assertFallsBackHome(validPlainIntent().putExtra(PlayerRequestIntentExtras.PROVIDER_ID, "1"))
        assertFallsBackHome(validPlainIntent().putExtra(PlayerRequestIntentExtras.SEASON_NUMBER, 3L))
        assertFallsBackHome(validPlainIntent().putExtra(PlayerRequestIntentExtras.TITLE, 5))
    }

    @Test
    fun plainExtrasWithDisallowedStreamSchemeAreRejected() {
        listOf("file:///sdcard/a.mp4", "content://example/a", "javascript:alert(1)", "no-scheme").forEach { url ->
            assertFallsBackHome(validPlainIntent().putExtra(PlayerRequestIntentExtras.STREAM_URL, url))
        }
    }

    @Test
    fun plainExtrasWithOversizedTextAreRejected() {
        assertFallsBackHome(
            validPlainIntent().putExtra(PlayerRequestIntentExtras.TITLE, "x".repeat(5_000))
        )
    }
}
