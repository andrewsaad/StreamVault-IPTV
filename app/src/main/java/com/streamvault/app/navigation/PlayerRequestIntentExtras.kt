package com.streamvault.app.navigation

import android.content.Intent
import com.streamvault.core.navigation.PlayerNavigationRequest
import com.streamvault.data.remote.stalker.StalkerUrlFactory
import com.streamvault.data.remote.xtream.XtreamUrlFactory
import com.streamvault.domain.model.ContentType

/**
 * Carries a [PlayerNavigationRequest] as plain primitive intent extras so it survives
 * `Intent.toUri(URI_INTENT_SCHEME)` / `Intent.parseUri` (used by launcher Watch Next cards),
 * which drops Serializable and Parcelable extras.
 *
 * Only the fields a Watch Next card needs are carried. Archive, combined-profile, Stremio and
 * return-destination fields are intentionally not encoded. [read] treats every extra as
 * untrusted: any malformed present value rejects the whole request.
 *
 * Security: MainActivity is exported with BROWSABLE VIEW filters, so a web page can set plain
 * string/long extras through a Chrome `intent:` URL. The stream URL is therefore restricted to
 * internal provider URLs (`xtream://`, `stalker://`) that the app resolves itself with the user's
 * own provider configuration (see [isInternalProviderUrl]). Every other scheme (http, https,
 * rtsp, rtmp, udp, file, content, ...) and an empty URL are rejected, so a page cannot force
 * playback of an attacker-chosen URL. Cards whose history URL is not internal (e.g. M3U) keep
 * the Serializable extra, which does not survive `Intent.toUri` and falls back to Home.
 */
internal object PlayerRequestIntentExtras {
    private const val PREFIX = "com.streamvault.app.extra.PLAYER_REQUEST_"
    const val STREAM_URL = PREFIX + "STREAM_URL"
    const val TITLE = PREFIX + "TITLE"
    const val CHANNEL_ID = PREFIX + "CHANNEL_ID"
    const val INTERNAL_ID = PREFIX + "INTERNAL_ID"
    const val CATEGORY_ID = PREFIX + "CATEGORY_ID"
    const val PROVIDER_ID = PREFIX + "PROVIDER_ID"
    const val CONTENT_TYPE = PREFIX + "CONTENT_TYPE"
    const val ARTWORK_URL = PREFIX + "ARTWORK_URL"
    const val SERIES_ID = PREFIX + "SERIES_ID"
    const val SEASON_NUMBER = PREFIX + "SEASON_NUMBER"
    const val EPISODE_NUMBER = PREFIX + "EPISODE_NUMBER"
    const val EPISODE_ID = PREFIX + "EPISODE_ID"
    const val REMOTE_CONTENT_ID = PREFIX + "REMOTE_CONTENT_ID"
    const val REMOTE_SERIES_ID = PREFIX + "REMOTE_SERIES_ID"

    private const val MAX_URL_LENGTH = 4096
    private const val MAX_TEXT_LENGTH = 1024
    private const val ABSENT_LONG = Long.MIN_VALUE
    private const val ABSENT_INT = Int.MIN_VALUE

    fun write(intent: Intent, request: PlayerNavigationRequest): Intent = intent.apply {
        putExtra(STREAM_URL, request.streamUrl)
        putExtra(TITLE, request.title)
        request.channelId?.let { putExtra(CHANNEL_ID, it) }
        putExtra(INTERNAL_ID, request.internalId)
        request.categoryId?.let { putExtra(CATEGORY_ID, it) }
        request.providerId?.let { putExtra(PROVIDER_ID, it) }
        putExtra(CONTENT_TYPE, request.contentType)
        request.artworkUrl?.let { putExtra(ARTWORK_URL, it) }
        request.seriesId?.let { putExtra(SERIES_ID, it) }
        request.seasonNumber?.let { putExtra(SEASON_NUMBER, it) }
        request.episodeNumber?.let { putExtra(EPISODE_NUMBER, it) }
        request.episodeId?.let { putExtra(EPISODE_ID, it) }
        request.remoteContentId?.let { putExtra(REMOTE_CONTENT_ID, it) }
        request.remoteSeriesId?.let { putExtra(REMOTE_SERIES_ID, it) }
    }

    /** Returns null when the intent carries no plain request or any present field is malformed. */
    fun read(intent: Intent): PlayerNavigationRequest? {
        if (!intent.hasExtra(STREAM_URL)) return null

        val streamUrl = intent.getStringExtra(STREAM_URL)
            ?.takeIf { it.length <= MAX_URL_LENGTH && isInternalProviderUrl(it) }
            ?: return null
        val title = intent.getStringExtra(TITLE)?.takeIf { it.length <= MAX_TEXT_LENGTH } ?: return null
        val contentType = intent.getStringExtra(CONTENT_TYPE)
            ?.takeIf { name -> ContentType.entries.any { it.name == name } }
            ?: return null

        val channelId = intent.optionalString(CHANNEL_ID, MAX_TEXT_LENGTH) { return null }
        val artworkUrl = intent.optionalString(ARTWORK_URL, MAX_URL_LENGTH) { return null }
        val remoteContentId = intent.optionalString(REMOTE_CONTENT_ID, MAX_TEXT_LENGTH) { return null }
        val remoteSeriesId = intent.optionalString(REMOTE_SERIES_ID, MAX_TEXT_LENGTH) { return null }
        // The request default for a missing internal id is -1, so -1 is the only negative allowed.
        val internalId = intent.optionalLong(INTERNAL_ID, minimum = -1L) { return null } ?: -1L
        val categoryId = intent.optionalLong(CATEGORY_ID, minimum = 0L) { return null }
        val providerId = intent.optionalLong(PROVIDER_ID, minimum = 0L) { return null }
        val seriesId = intent.optionalLong(SERIES_ID, minimum = 0L) { return null }
        val episodeId = intent.optionalLong(EPISODE_ID, minimum = 0L) { return null }
        val seasonNumber = intent.optionalInt(SEASON_NUMBER) { return null }
        val episodeNumber = intent.optionalInt(EPISODE_NUMBER) { return null }

        return PlayerNavigationRequest(
            streamUrl = streamUrl,
            title = title,
            channelId = channelId,
            internalId = internalId,
            categoryId = categoryId,
            providerId = providerId,
            contentType = contentType,
            artworkUrl = artworkUrl,
            seriesId = seriesId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeId = episodeId,
            remoteContentId = remoteContentId,
            remoteSeriesId = remoteSeriesId
        )
    }

    /**
     * True only for persisted internal Xtream/Stalker stream URLs that cannot smuggle in a
     * foreign URL: an Xtream `src` direct source and a Stalker command containing `://` are
     * played as-is by the resolvers, so they are rejected here. An empty URL is not internal;
     * playback needs a URL, it is not resolved from provider and content ids alone.
     */
    fun isInternalProviderUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        XtreamUrlFactory.parseInternalStreamUrl(url)?.let { return it.directSource == null }
        val token = StalkerUrlFactory.parseInternalStreamUrl(url) ?: return false
        val commands = listOf(token.cmd) + token.playbackDescriptor?.candidates.orEmpty().map { it.cmd }
        return commands.none { it.contains("://") }
    }

    private inline fun Intent.optionalString(key: String, maxLength: Int, malformed: () -> Nothing): String? {
        if (!hasExtra(key)) return null
        return getStringExtra(key)?.takeIf { it.length <= maxLength } ?: malformed()
    }

    // A wrong-typed extra makes getLongExtra/getIntExtra return the sentinel, which fails the range check.
    private inline fun Intent.optionalLong(key: String, minimum: Long, malformed: () -> Nothing): Long? {
        if (!hasExtra(key)) return null
        return getLongExtra(key, ABSENT_LONG).takeIf { it >= minimum } ?: malformed()
    }

    private inline fun Intent.optionalInt(key: String, malformed: () -> Nothing): Int? {
        if (!hasExtra(key)) return null
        return getIntExtra(key, ABSENT_INT).takeIf { it >= 0 } ?: malformed()
    }
}
