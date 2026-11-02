package com.streamvault.app.tvinput

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.ProviderType
import org.junit.Test

class TvInputChannelSyncManagerTest {

    @Test
    fun stableTvChannelKey_survivesChangedLocalProviderAndChannelIds() {
        val beforeProvider = LegacyProvider(
            id = 1,
            name = "Provider",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "HTTPS://EXAMPLE.COM/",
            username = "user",
            password = "pass"
        )
        val afterProvider = beforeProvider.copy(id = 99, serverUrl = "https://example.com")
        val beforeChannel = Channel(id = 5, streamId = 42, name = "News", providerId = 1)
        val afterChannel = beforeChannel.copy(id = 500, providerId = 99)

        assertThat(stableTvChannelKey(beforeProvider, beforeChannel))
            .isEqualTo(stableTvChannelKey(afterProvider, afterChannel))
    }

    @Test
    fun sha256Prefix_isByteIdenticalToThePerByteFormatEncoder() {
        val inputs = listOf(
            "", "a", "News", "https://example.com|user|XTREAM_CODES|", "قناة الكأس|1|beIN",
            "Ελληνικά", "\u0000ÿ", "x".repeat(4_096)
        ) + (0 until 200).map { "channel-$it|${it * 7919}|Sports $it" }
        for (input in inputs) {
            for (byteCount in listOf(0, 1, 6, 12, 32, 40)) {
                assertThat(sha256Prefix(input, byteCount)).isEqualTo(referenceSha256Prefix(input, byteCount))
            }
            assertThat(sha256Prefix(input)).isEqualTo(referenceSha256Prefix(input, 12))
        }
    }

    @Test
    fun stableKeys_matchTheFormatBasedEncodingSoExistingRowsAreNotRewritten() {
        val provider = LegacyProvider(
            id = 3,
            name = "Provider",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "http://example.com:80/",
            username = " user ",
            password = "pass"
        )
        val channel = Channel(id = 5, streamId = 4242, name = "beIN Sports 1", providerId = 3)
        val program = Program(channelId = "bein1", title = "Al Hilal vs Al Nassr", description = "Live",
            startTime = 1_000L, endTime = 2_000L)
        val expectedProviderHash = referenceSha256Prefix("http://example.com|user|XTREAM_CODES|", 12)
        val expectedChannelKey = "$expectedProviderHash:4242"

        assertThat(tvProviderHash(provider)).isEqualTo(expectedProviderHash)
        assertThat(stableTvChannelKey(provider, channel)).isEqualTo(expectedChannelKey)
        assertThat(stableTvChannelKey(tvProviderHash(provider), channel)).isEqualTo(expectedChannelKey)
        assertThat(stableTvProgramKey(expectedChannelKey, program)).isEqualTo(
            "$expectedChannelKey:1000:2000:${referenceSha256Prefix("Al Hilal vs Al Nassr|Live", 6)}"
        )
    }

    /** The encoder used before the fast hex conversion. */
    private fun referenceSha256Prefix(value: String, byteCount: Int): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(byteCount)
            .joinToString("") { byte -> "%02x".format(byte) }

    @Test
    fun shouldReplaceTvPrograms_preservesGuideWhenMappedChannelHasEmptySnapshot() {
        val channel = Channel(
            id = 1L,
            name = "News",
            providerId = 2L,
            epgChannelId = "news-hd"
        )

        assertThat(shouldReplaceTvPrograms(channel, emptyList())).isFalse()
    }

    @Test
    fun shouldReplaceTvPrograms_allowsReplacementWhenFreshProgramsExist() {
        val channel = Channel(
            id = 1L,
            name = "News",
            providerId = 2L,
            epgChannelId = "news-hd"
        )
        val programs = listOf(
            Program(
                channelId = "news-hd",
                title = "Morning News",
                startTime = 1_000L,
                endTime = 2_000L
            )
        )

        assertThat(shouldReplaceTvPrograms(channel, programs)).isTrue()
    }

    @Test
    fun shouldReplaceTvPrograms_allowsClearingWhenChannelHasNoGuideIdentity() {
        val channel = Channel(
            id = 1L,
            name = "No EPG",
            providerId = 2L,
            epgChannelId = null
        )

        assertThat(shouldReplaceTvPrograms(channel, emptyList())).isTrue()
    }
}
