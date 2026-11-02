package com.streamvault.data.sync.favorites

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class HttpFavoriteSyncTransportTest {
    private lateinit var server: MockWebServer
    private lateinit var transport: HttpFavoriteSyncTransport

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val url = HttpFavoriteSyncTransport.documentUrl(server.url("/").toString())!!
        transport = HttpFavoriteSyncTransport(url, "test-token")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `document url appends the v1 document path`() {
        assertThat(HttpFavoriteSyncTransport.documentUrl("http://192.168.1.10:8787/")?.toString())
            .isEqualTo("http://192.168.1.10:8787/v1/docs/streamvault-favorites")
        assertThat(HttpFavoriteSyncTransport.documentUrl("sync-box:8787")).isNull()
    }

    @Test
    fun `fetch sends the bearer token and returns body and quoted etag`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"abc\"").setBody(Buffer().write(byteArrayOf(1, 2, 3))))

        val result = transport.fetch(ifNoneMatch = null)

        assertThat(result).isInstanceOf(FavoriteSyncFetchResult.Found::class.java)
        result as FavoriteSyncFetchResult.Found
        assertThat(result.body).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(result.etag).isEqualTo("\"abc\"")
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.path).isEqualTo("/v1/docs/streamvault-favorites")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer test-token")
        assertThat(request.getHeader("If-None-Match")).isNull()
    }

    @Test
    fun `conditional fetch maps 304 and 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(304).setHeader("ETag", "\"abc\""))
        server.enqueue(MockResponse().setResponseCode(404))

        assertThat(transport.fetch("\"abc\"")).isEqualTo(FavoriteSyncFetchResult.NotModified("\"abc\""))
        assertThat(server.takeRequest().getHeader("If-None-Match")).isEqualTo("\"abc\"")
        assertThat(transport.fetch(null)).isEqualTo(FavoriteSyncFetchResult.Missing)
    }

    @Test
    fun `first write creates with If-None-Match star and a fixed content length`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"new\""))

        val result = transport.store(byteArrayOf(9, 8, 7, 6), ifMatch = null)

        assertThat(result).isEqualTo(FavoriteSyncStoreResult.Stored("\"new\""))
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("PUT")
        assertThat(request.getHeader("If-None-Match")).isEqualTo("*")
        assertThat(request.getHeader("If-Match")).isNull()
        assertThat(request.getHeader("Content-Length")).isEqualTo("4")
        assertThat(request.getHeader("Transfer-Encoding")).isNull()
        assertThat(request.getHeader("Content-Type")).isEqualTo("application/octet-stream")
    }

    @Test
    fun `replace sends If-Match exactly as received and maps 412 to conflict`() = runTest {
        server.enqueue(MockResponse().setResponseCode(412))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"v2\""))

        assertThat(transport.store(byteArrayOf(1), ifMatch = "\"v1\"")).isEqualTo(FavoriteSyncStoreResult.Conflict)
        val first = server.takeRequest()
        assertThat(first.getHeader("If-Match")).isEqualTo("\"v1\"")
        assertThat(first.getHeader("If-None-Match")).isNull()
        assertThat(transport.store(byteArrayOf(1), ifMatch = "\"v1\"")).isEqualTo(FavoriteSyncStoreResult.Stored("\"v2\""))
    }

    @Test
    fun `replace of a document deleted on the server maps 404 to conflict`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        assertThat(transport.store(byteArrayOf(1), ifMatch = "\"gone\"")).isEqualTo(FavoriteSyncStoreResult.Conflict)
    }

    @Test
    fun `status codes map to distinct failures`() = runTest {
        assertFailure(401, FavoriteSyncFailure.UNAUTHORIZED)
        assertFailure(413, FavoriteSyncFailure.TOO_LARGE)
        assertFailure(428, FavoriteSyncFailure.SERVER_ERROR)
        assertFailure(503, FavoriteSyncFailure.SERVER_ERROR)
    }

    @Test
    fun `unreachable server is reported as unreachable`() = runTest {
        val url = HttpFavoriteSyncTransport.documentUrl(server.url("/").toString())!!
        server.shutdown()
        try {
            HttpFavoriteSyncTransport(url, "secret-token-value").fetch(null)
            fail("expected failure")
        } catch (error: FavoriteSyncTransportException) {
            assertThat(error.reason).isEqualTo(FavoriteSyncFailure.UNREACHABLE)
            assertThat(error.message).doesNotContain("secret-token-value")
        }
    }

    private suspend fun assertFailure(code: Int, expected: FavoriteSyncFailure) {
        server.enqueue(MockResponse().setResponseCode(code))
        try {
            transport.store(byteArrayOf(1), ifMatch = "\"x\"")
            fail("expected failure for $code")
        } catch (error: FavoriteSyncTransportException) {
            assertThat(error.reason).isEqualTo(expected)
            assertThat(error.message).doesNotContain("test-token")
        }
    }
}
