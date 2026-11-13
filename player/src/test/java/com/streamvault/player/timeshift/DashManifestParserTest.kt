package com.streamvault.player.timeshift

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

/** Runs [parseDashManifest], the parser the DASH session uses on every manifest poll. */
class DashManifestParserTest {

    @Before
    fun setUp() {
        // The JVM test runner has no Android XmlPull implementation; use kxml2.
        System.setProperty("org.xmlpull.v1.XmlPullParserFactory", "org.kxml2.io.KXmlParser")
    }

    private fun timelineMpd(timescale: Long, firstT: Long, d: Long, repeat: Int) = """
        <MPD type="dynamic" minimumUpdatePeriod="PT2S">
          <Period>
            <AdaptationSet contentType="video">
              <Representation id="v1" bandwidth="1000000">
                <SegmentTemplate timescale="$timescale" initialization="init-${'$'}RepresentationID${'$'}.mp4" media="seg-${'$'}Time${'$'}.m4s">
                  <SegmentTimeline>
                    <S t="$firstT" d="$d" r="$repeat"/>
                  </SegmentTimeline>
                </SegmentTemplate>
              </Representation>
            </AdaptationSet>
          </Period>
        </MPD>
    """.trimIndent()

    @Test
    fun `epoch based time at a 10 MHz timescale converts to milliseconds without overflow`() {
        val timescale = 10_000_000L
        val startMs = 1_700_000_000_123L  // about November 2023, in ms since the epoch
        val t = startMs * 10_000L  // 1.7e16 ticks: t * 1000 overflows a Long
        val d = 2L * timescale

        val parsed = parseDashManifest("https://cdn.example/live/manifest.mpd", timelineMpd(timescale, t, d, repeat = 1), depthMs = 60_000L)

        assertThat(parsed.mediaSegments.map { it.startMs }).containsExactly(startMs, startMs + 2_000L).inOrder()
        assertThat(parsed.mediaSegments.map { it.durationMs }).containsExactly(2_000L, 2_000L)
        assertThat(parsed.mediaSegments.first().uri).isEqualTo("https://cdn.example/live/seg-$t.m4s")
    }

    @Test
    fun `dashTicksToMs matches exact arithmetic for small and huge values`() {
        assertThat(dashTicksToMs(ticks = 270_000L, timescale = 90_000L)).isEqualTo(3_000L)
        assertThat(dashTicksToMs(ticks = 1_234L, timescale = 1_000L)).isEqualTo(1_234L)
        assertThat(dashTicksToMs(ticks = 45L, timescale = 90_000L)).isEqualTo(0L)
        val huge = Long.MAX_VALUE / 2
        assertThat(dashTicksToMs(ticks = huge, timescale = 10_000_000L))
            .isEqualTo(java.math.BigInteger.valueOf(huge).multiply(java.math.BigInteger.valueOf(1_000L))
                .divide(java.math.BigInteger.valueOf(10_000_000L)).toLong())
    }
}
