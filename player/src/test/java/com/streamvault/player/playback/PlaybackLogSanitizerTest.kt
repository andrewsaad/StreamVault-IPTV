package com.streamvault.player.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackLogSanitizerTest {
    @Test
    fun `subtitle origin logging removes private path and query`() {
        val sanitized = PlaybackLogSanitizer.sanitizeOrigin(
            "https://subtitles.example.test/private/configured/path.srt?token=hidden"
        )

        assertThat(sanitized).isEqualTo("https://subtitles.example.test")
        assertThat(sanitized).doesNotContain("private")
        assertThat(sanitized).doesNotContain("hidden")
    }

    @Test
    fun `redacts live username and password path segments`() {
        val sanitized = PlaybackLogSanitizer.sanitizeUrl(
            "https://example.test/live/user-name/password-value/61351.m3u8"
        )

        assertThat(sanitized).isEqualTo("example.test/live/<redacted>/<redacted>/61351.m3u8")
    }

    @Test
    fun `redacts opaque segment token path`() {
        val sanitized = PlaybackLogSanitizer.sanitizeUrl(
            "https://example.test/r/a4b5206dad97d1070000000000006eb4/61351_1429.ts"
        )

        assertThat(sanitized).isEqualTo("example.test/r/<redacted>/61351_1429.ts")
    }

    @Test
    fun `redacts opaque segment token in messages`() {
        val sanitized = PlaybackLogSanitizer.sanitizeMessage(
            "read target=https://example.test/r/a4b5206dad97d1070000000000006eb4/61351_1429.ts"
        )

        assertThat(sanitized).doesNotContain("a4b5206dad97d1070000000000006eb4")
        assertThat(sanitized).contains("/r/<redacted>/61351_1429.ts")
    }

    @Test
    fun `redacts short Xtream user and password path without a type segment`() {
        assertThat(PlaybackLogSanitizer.sanitizeUrl("http://example.test:8080/user-name/password-value/61351"))
            .isEqualTo("example.test/<redacted>/<redacted>/61351")
        assertThat(PlaybackLogSanitizer.sanitizeUrl("http://example.test/user-name/password-value/61351.ts"))
            .isEqualTo("example.test/<redacted>/<redacted>/61351.ts")
    }

    @Test
    fun `redacts short Xtream credentials inside messages and leaves typed paths single redacted`() {
        val sanitized = PlaybackLogSanitizer.sanitizeMessage(
            "open failed url=http://example.test/user-name/password-value/61351.m3u8 retrying"
        )

        assertThat(sanitized).doesNotContain("user-name")
        assertThat(sanitized).doesNotContain("password-value")
        assertThat(sanitized).contains("example.test/<redacted>/<redacted>/61351.m3u8 retrying")
        assertThat(PlaybackLogSanitizer.sanitizeUrl("https://example.test/live/u/p/61351.m3u8"))
            .isEqualTo("example.test/live/<redacted>/<redacted>/61351.m3u8")
    }

    @Test
    fun `short form rule leaves ordinary paths alone`() {
        assertThat(PlaybackLogSanitizer.sanitizeUrl("https://cdn.example.test/hls/index.m3u8"))
            .isEqualTo("cdn.example.test/hls/index.m3u8")
        assertThat(PlaybackLogSanitizer.sanitizeUrl("https://cdn.example.test/a/b/c/1.ts"))
            .isEqualTo("cdn.example.test/a/b/c/1.ts")
    }
}
