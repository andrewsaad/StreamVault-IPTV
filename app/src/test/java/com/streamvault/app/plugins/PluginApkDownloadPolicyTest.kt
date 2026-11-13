package com.streamvault.app.plugins

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertThrows
import org.junit.Test

class PluginApkDownloadPolicyTest {

    @Test
    fun `only https links with a host are accepted for plugin downloads`() {
        assertThat(isHttpsPluginUrl("https://example.com/plugin.apk")).isTrue()
        assertThat(isHttpsPluginUrl("HTTPS://example.com/plugin.apk")).isTrue()

        assertThat(isHttpsPluginUrl("http://example.com/plugin.apk")).isFalse()
        assertThat(isHttpsPluginUrl("http://192.168.1.10:8080/plugin.apk")).isFalse()
        assertThat(isHttpsPluginUrl("ftp://example.com/plugin.apk")).isFalse()
        assertThat(isHttpsPluginUrl("file:///sdcard/plugin.apk")).isFalse()
        assertThat(isHttpsPluginUrl("https:///plugin.apk")).isFalse()
        assertThat(isHttpsPluginUrl("not a url")).isFalse()
        assertThat(isHttpsPluginUrl("")).isFalse()
    }

    @Test
    fun `download within the limit is copied in full`() {
        val payload = ByteArray(10_000) { it.toByte() }
        val output = ByteArrayOutputStream()

        val copied = copyAtMost(ByteArrayInputStream(payload), output, maxBytes = payload.size.toLong())

        assertThat(copied).isEqualTo(payload.size.toLong())
        assertThat(output.toByteArray()).isEqualTo(payload)
    }

    @Test
    fun `download over the limit fails without writing past it`() {
        val payload = ByteArray(20_000)
        val output = ByteArrayOutputStream()

        assertThrows(IOException::class.java) {
            copyAtMost(ByteArrayInputStream(payload), output, maxBytes = 10_000L)
        }
        assertThat(output.size()).isAtMost(10_000)
    }
}
