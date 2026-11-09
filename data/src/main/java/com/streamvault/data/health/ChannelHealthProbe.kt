package com.streamvault.data.health

import com.streamvault.data.remote.http.useCancellableResponse
import com.streamvault.domain.model.ChannelHealthStatus
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor

/**
 * Checks one live channel the way the player would start it: the HLS playlist, then the first
 * piece of video. The url carries the provider login, so it is never logged, returned or put in
 * an exception message here.
 */
internal class ChannelHealthProbe @Inject constructor(client: OkHttpClient) {
    // No HTTP cache (a cached playlist would hide a dead channel) and no request logging.
    private val client: OkHttpClient = client.newBuilder()
        .apply { interceptors().removeAll { it is HttpLoggingInterceptor } }
        .cache(null)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    suspend fun probe(playlistUrl: String, headers: Map<String, String> = emptyMap()): ChannelHealthStatus =
        try {
            classify(playlistUrl, headers)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // IO errors, timeouts and unparsable urls say nothing about the channel.
            ChannelHealthStatus.UNCLEAR
        }

    private suspend fun classify(playlistUrl: String, headers: Map<String, String>): ChannelHealthStatus {
        var playlist = when (val fetched = fetchPlaylist(playlistUrl, headers)) {
            is Fetched.Verdict -> return fetched.status
            is Fetched.Playlist -> fetched
        }
        if (playlist.isMaster) {
            // One variant level is enough: the first variant is what the player starts with.
            val variantUrl = playlist.firstEntry ?: return ChannelHealthStatus.DEAD
            playlist = when (val fetched = fetchPlaylist(variantUrl.toString(), headers)) {
                is Fetched.Verdict -> return fetched.status
                is Fetched.Playlist -> fetched
            }
        }
        val segmentUrl = playlist.firstEntry ?: return ChannelHealthStatus.DEAD
        return checkFirstBytes(segmentUrl, headers)
    }

    private suspend fun fetchPlaylist(url: String, headers: Map<String, String>): Fetched =
        client.newCall(request(url, headers).build()).useCancellableResponse { response ->
            statusForHttpCode(response.code)?.let { return@useCancellableResponse Fetched.Verdict(it) }
            val text = readText(response.body, MAX_PLAYLIST_BYTES)
            when {
                text.isBlank() -> Fetched.Verdict(ChannelHealthStatus.EMPTY)
                !text.contains("#EXTM3U") -> Fetched.Verdict(ChannelHealthStatus.DEAD)
                else -> Fetched.Playlist(
                    firstEntry = firstEntry(text)?.let { response.request.url.resolve(it) },
                    isMaster = text.contains("#EXT-X-STREAM-INF")
                )
            }
        }

    private suspend fun checkFirstBytes(segmentUrl: HttpUrl, headers: Map<String, String>): ChannelHealthStatus =
        client.newCall(
            request(segmentUrl.toString(), headers).header("Range", "bytes=0-${MAX_SEGMENT_BYTES - 1}").build()
        ).useCancellableResponse { response ->
            statusForHttpCode(response.code)?.let { return@useCancellableResponse it }
            val source = response.body?.source() ?: return@useCancellableResponse ChannelHealthStatus.DEAD
            source.request(MAX_SEGMENT_BYTES)
            val size = minOf(source.buffer.size, MAX_SEGMENT_BYTES)
            when {
                size < MIN_SEGMENT_BYTES -> ChannelHealthStatus.DEAD
                source.buffer[0] != MPEG_TS_SYNC_BYTE -> ChannelHealthStatus.UNCLEAR
                else -> ChannelHealthStatus.OK
            }
        }

    private fun request(url: String, headers: Map<String, String>): Request.Builder =
        Request.Builder().url(url).apply { headers.forEach { (name, value) -> header(name, value) } }

    private fun statusForHttpCode(code: Int): ChannelHealthStatus? = when (code) {
        in 200..299 -> null
        401, 403, 404, 410 -> ChannelHealthStatus.DEAD
        else -> ChannelHealthStatus.UNCLEAR
    }

    private fun readText(body: ResponseBody?, maxBytes: Long): String {
        val source = body?.source() ?: return ""
        source.request(maxBytes)
        return source.buffer.readUtf8(minOf(source.buffer.size, maxBytes))
    }

    private fun firstEntry(playlist: String): String? =
        playlist.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }

    private sealed interface Fetched {
        class Verdict(val status: ChannelHealthStatus) : Fetched
        class Playlist(val firstEntry: HttpUrl?, val isMaster: Boolean) : Fetched
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 10L
        // The provider can take ~11 s to answer with nothing for a channel without a source.
        const val READ_TIMEOUT_SECONDS = 20L
        const val CALL_TIMEOUT_SECONDS = 45L
        const val MAX_PLAYLIST_BYTES = 256L * 1024L
        const val MAX_SEGMENT_BYTES = 64L * 1024L
        const val MIN_SEGMENT_BYTES = 4096L
        const val MPEG_TS_SYNC_BYTE: Byte = 0x47
    }
}
