package com.streamvault.app.plugins

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Before
import org.junit.Test

class PluginApkRedirectTest {

    private val httpsServer = MockWebServer()
    private val httpServer = MockWebServer()
    private lateinit var client: OkHttpClient
    private lateinit var target: File

    @Before
    fun start() {
        val certificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        httpsServer.useHttps(serverCertificates.sslSocketFactory(), false)
        httpsServer.start()
        httpServer.start()
        val trustedCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        // Same redirect policy as the app's shared client (NetworkModule): follow redirects, including across schemes.
        client = OkHttpClient.Builder()
            .sslSocketFactory(trustedCertificates.sslSocketFactory(), trustedCertificates.trustManager)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        target = Files.createTempFile("plugin", ".apk").toFile()
    }

    @After
    fun stop() {
        httpsServer.shutdown()
        httpServer.shutdown()
        target.delete()
    }

    @Test
    fun `https link that redirects to http fails without fetching over cleartext`() = runBlocking {
        httpServer.enqueue(MockResponse().setBody("swapped apk"))
        httpsServer.enqueue(
            MockResponse().setResponseCode(302).setHeader("Location", httpServer.url("/plugin.apk").toString())
        )

        val failure = runCatching {
            downloadPluginApk(client, httpsServer.url("/plugin.apk").toString(), target, maxBytes = 1_000L)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(httpServer.requestCount).isEqualTo(0)
        assertThat(target.readText()).isEmpty()
    }

    @Test
    fun `https redirect to another https path is followed`() = runBlocking {
        httpsServer.enqueue(
            MockResponse().setResponseCode(302).setHeader("Location", httpsServer.url("/v2/plugin.apk").toString())
        )
        httpsServer.enqueue(MockResponse().setBody("plugin apk"))

        downloadPluginApk(client, httpsServer.url("/plugin.apk").toString(), target, maxBytes = 1_000L)

        assertThat(target.readText()).isEqualTo("plugin apk")
    }

    @Test
    fun `download over the size limit fails`() = runBlocking {
        httpsServer.enqueue(MockResponse().setBody("x".repeat(2_000)))

        val failure = runCatching {
            downloadPluginApk(client, httpsServer.url("/plugin.apk").toString(), target, maxBytes = 1_000L)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
    }
}
