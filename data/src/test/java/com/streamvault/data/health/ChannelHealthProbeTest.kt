package com.streamvault.data.health

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ChannelHealthStatus
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test

class ChannelHealthProbeTest {
    private val server = MockWebServer()
    private val probe = ChannelHealthProbe(OkHttpClient())

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun playlistUrl(): String = server.url("/live/user/pass/1.m3u8").toString()

    private fun playlist(vararg lines: String) = MockResponse().setBody(lines.joinToString("\n"))

    private fun segment(size: Int, firstByte: Int = 0x47) = MockResponse().setBody(
        Buffer().write(ByteArray(size) { index -> if (index == 0) firstByte.toByte() else 0x10 })
    )

    private fun probeNow(): ChannelHealthStatus = runBlocking { probe.probe(playlistUrl()) }.status

    @Test
    fun `playlist and MPEG-TS segment are OK and the segment is asked with a range`() {
        server.enqueue(playlist("#EXTM3U", "#EXTINF:6,", "seg0.ts"))
        server.enqueue(segment(8000))

        assertThat(probeNow()).isEqualTo(ChannelHealthStatus.OK)

        assertThat(server.takeRequest().path).isEqualTo("/live/user/pass/1.m3u8")
        val segmentRequest = server.takeRequest()
        assertThat(segmentRequest.path).isEqualTo("/live/user/pass/seg0.ts")
        assertThat(segmentRequest.getHeader("Range")).isEqualTo("bytes=0-65535")
    }

    @Test
    fun `master playlist is followed one level to the first variant`() {
        server.enqueue(playlist("#EXTM3U", "#EXT-X-STREAM-INF:BANDWIDTH=1000", "variant.m3u8"))
        server.enqueue(playlist("#EXTM3U", "#EXTINF:6,", "seg0.ts"))
        server.enqueue(segment(5000))

        assertThat(probeNow()).isEqualTo(ChannelHealthStatus.OK)

        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `a provider that answers with nothing is EMPTY`() {
        server.enqueue(MockResponse().setBody(""))

        assertThat(probeNow()).isEqualTo(ChannelHealthStatus.EMPTY)
    }

    @Test
    fun `not found and gone playlists are DEAD`() {
        listOf(404, 410).forEach { code ->
            server.enqueue(MockResponse().setResponseCode(code))

            assertThat(probeNow()).isEqualTo(ChannelHealthStatus.DEAD)
        }
    }

    @Test
    fun `unauthorized and forbidden playlists are UNCLEAR because they can mean a connection limit`() {
        listOf(401, 403).forEach { code ->
            server.enqueue(MockResponse().setResponseCode(code))

            assertThat(probeNow()).isEqualTo(ChannelHealthStatus.UNCLEAR)
            server.enqueue(MockResponse().setResponseCode(code))
            assertThat(runBlocking { probe.probe(playlistUrl()) }.serverError).isFalse()
        }
    }

    @Test
    fun `a forbidden segment is UNCLEAR`() {
        server.enqueue(playlist("#EXTM3U", "#EXTINF:6,", "seg0.ts"))
        server.enqueue(MockResponse().setResponseCode(403))

        assertThat(probeNow()).isEqualTo(ChannelHealthStatus.UNCLEAR)
    }

    @Test
    fun `provider errors are UNCLEAR`() {
        listOf(500, 502, 503).forEach { code ->
            server.enqueue(MockResponse().setResponseCode(code))

            assertThat(probeNow()).isEqualTo(ChannelHealthStatus.UNCLEAR)
            server.enqueue(MockResponse().setResponseCode(code))
            assertThat(runBlocking { probe.probe(playlistUrl()) }.serverError).isTrue()
        }
    }

    @Test
    fun `an unreachable provider is UNCLEAR`() {
        val url = playlistUrl()
        server.shutdown()

        val result = runBlocking { probe.probe(url) }

        assertThat(result).isEqualTo(ChannelProbeResult(ChannelHealthStatus.UNCLEAR, serverError = false))
    }

    @Test
    fun `an address that cannot be parsed is UNCLEAR`() {
        assertThat(runBlocking { probe.probe("not an address") })
            .isEqualTo(ChannelProbeResult(ChannelHealthStatus.UNCLEAR, serverError = false))
    }

    @Test
    fun `a playlist without segments is DEAD`() {
        server.enqueue(playlist("#EXTM3U", "#EXT-X-ENDLIST"))

        assertThat(probeNow()).isEqualTo(ChannelHealthStatus.DEAD)
    }

    @Test
    fun `an answer that is not a playlist is DEAD`() {
        server.enqueue(MockResponse().setBody("<html>sorry</html>"))

        assertThat(probeNow()).isEqualTo(ChannelHealthStatus.DEAD)
    }

    @Test
    fun `a missing segment is DEAD`() {
        server.enqueue(playlist("#EXTM3U", "#EXTINF:6,", "seg0.ts"))
        server.enqueue(MockResponse().setResponseCode(404))

        assertThat(probeNow()).isEqualTo(ChannelHealthStatus.DEAD)
    }

    @Test
    fun `a segment under 4096 bytes is DEAD`() {
        server.enqueue(playlist("#EXTM3U", "#EXTINF:6,", "seg0.ts"))
        server.enqueue(segment(4095))

        assertThat(probeNow()).isEqualTo(ChannelHealthStatus.DEAD)
    }

    @Test
    fun `a segment of exactly 4096 bytes is accepted`() {
        server.enqueue(playlist("#EXTM3U", "#EXTINF:6,", "seg0.ts"))
        server.enqueue(segment(4096))

        assertThat(probeNow()).isEqualTo(ChannelHealthStatus.OK)
    }

    @Test
    fun `a segment that does not start with the MPEG-TS sync byte is UNCLEAR`() {
        server.enqueue(playlist("#EXTM3U", "#EXTINF:6,", "seg0.ts"))
        server.enqueue(segment(8000, firstByte = 0x00))

        assertThat(probeNow()).isEqualTo(ChannelHealthStatus.UNCLEAR)
    }

    @Test
    fun `a provider error on the segment is UNCLEAR`() {
        server.enqueue(playlist("#EXTM3U", "#EXTINF:6,", "seg0.ts"))
        server.enqueue(MockResponse().setResponseCode(502))

        assertThat(runBlocking { probe.probe(playlistUrl()) })
            .isEqualTo(ChannelProbeResult(ChannelHealthStatus.UNCLEAR, serverError = true))
    }
}
