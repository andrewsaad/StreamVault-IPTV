package com.streamvault.data.health

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class ChannelScanNotificationTest {
    private val gap = ChannelScanNotification.MIN_UPDATE_GAP_MS

    @Test
    fun `the first progress change refreshes at once`() {
        assertThat(ChannelScanNotification.shouldRefresh(null, 1_000L, progressChanged = true)).isTrue()
    }

    @Test
    fun `an unchanged count never refreshes`() {
        assertThat(ChannelScanNotification.shouldRefresh(null, 1_000L, progressChanged = false)).isFalse()
        assertThat(ChannelScanNotification.shouldRefresh(0L, gap * 5, progressChanged = false)).isFalse()
    }

    @Test
    fun `a change inside the gap waits and one after it refreshes`() {
        assertThat(ChannelScanNotification.shouldRefresh(10_000L, 10_000L + gap - 1, progressChanged = true)).isFalse()
        assertThat(ChannelScanNotification.shouldRefresh(10_000L, 10_000L + gap, progressChanged = true)).isTrue()
    }

    private val keys = listOf(
        "channel_scan_notification_channel",
        "channel_scan_notification_title",
        "channel_scan_notification_progress",
        "channel_scan_notification_preparing",
        "channel_scan_notification_stop",
    )

    private fun resDir(): File =
        listOf(File("src/main/res"), File("data/src/main/res")).first { it.isDirectory }

    private fun valueOf(file: File, key: String): String? =
        Regex("""<string[^>]*name="$key"[^>]*>(.*?)</string>""").find(file.readText())?.groupValues?.get(1)

    private fun placeholders(text: String) =
        Regex("""%\d+\$[a-z]""").findAll(text).map { it.value }.sorted().toList()

    @Test
    fun `every advertised locale translates every scan notification key with the same placeholders`() {
        val locales = Regex("""android:name="([^"]+)"""")
            .findAll(File(resDir(), "../../../../app/src/main/res/xml/locales_config.xml").readText())
            .map { it.groupValues[1] }.toList()
        assertThat(locales.size).isAtLeast(38)
        val english = File(resDir(), "values/strings.xml")
        for (locale in locales.filter { it != "en" }) {
            val folder = "values-" + locale.replace("-", "-r")
            val file = File(resDir(), "$folder/strings.xml")
            assertThat(file.isFile).isTrue()
            for (key in keys) {
                val translated = valueOf(file, key)
                assertThat(translated).isNotNull()
                assertThat(translated).isNotEmpty()
                assertThat(placeholders(translated!!)).isEqualTo(placeholders(valueOf(english, key)!!))
            }
        }
    }
}
