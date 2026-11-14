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

    @Test
    fun `timeline segments carry their time as position and start in milliseconds`() {
        val mpd = """
            <MPD type="dynamic">
              <Period>
                <AdaptationSet mimeType="video/mp4">
                  <Representation id="v1" bandwidth="1000000">
                    <SegmentTemplate timescale="90000" startNumber="5" initialization="init.mp4" media="${'$'}Number${'$'}-${'$'}Time${'$'}.m4s">
                      <SegmentTimeline>
                        <S t="900000" d="180000" r="2"/>
                        <S d="180000"/>
                      </SegmentTimeline>
                    </SegmentTemplate>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()

        val parsed = parseDashManifest("https://cdn.example/live/manifest.mpd", mpd, depthMs = 60_000L)

        assertThat(parsed.mediaSegments.map { it.position })
            .containsExactly(900_000L, 1_080_000L, 1_260_000L, 1_440_000L).inOrder()
        assertThat(parsed.mediaSegments.map { it.startMs })
            .containsExactly(10_000L, 12_000L, 14_000L, 16_000L).inOrder()
        assertThat(parsed.mediaSegments.first().uri).isEqualTo("https://cdn.example/live/5-900000.m4s")
        assertThat(parsed.mediaSegments.last().uri).isEqualTo("https://cdn.example/live/8-1440000.m4s")
        assertThat(parsed.initSegmentUrl).isEqualTo("https://cdn.example/live/init.mp4")
    }

    @Test
    fun `a timeline at adaptation set level is inherited by its representations`() {
        val mpd = """
            <MPD type="dynamic">
              <Period>
                <AdaptationSet contentType="audio">
                  <SegmentTemplate timescale="48000" media="audio-${'$'}Time${'$'}.m4s">
                    <SegmentTimeline>
                      <S t="0" d="96000" r="9"/>
                    </SegmentTimeline>
                  </SegmentTemplate>
                  <Representation id="a1" bandwidth="128000"/>
                </AdaptationSet>
                <AdaptationSet contentType="video">
                  <SegmentTemplate timescale="90000" initialization="init-${'$'}RepresentationID${'$'}.mp4" media="${'$'}RepresentationID${'$'}-${'$'}Time${'$'}.m4s">
                    <SegmentTimeline>
                      <S t="900000" d="180000" r="1"/>
                      <S d="180000"/>
                    </SegmentTimeline>
                  </SegmentTemplate>
                  <Representation id="low" bandwidth="500000"/>
                  <Representation id="mid" bandwidth="1000000"/>
                  <Representation id="high" bandwidth="3000000"/>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()

        val parsed = parseDashManifest("https://cdn.example/live/manifest.mpd", mpd, depthMs = 60_000L)

        assertThat(parsed.mediaSegments.map { it.uri }).containsExactly(
            "https://cdn.example/live/mid-900000.m4s",
            "https://cdn.example/live/mid-1080000.m4s",
            "https://cdn.example/live/mid-1260000.m4s"
        ).inOrder()
        assertThat(parsed.mediaSegments.map { it.durationMs }).containsExactly(2_000L, 2_000L, 2_000L)
        assertThat(parsed.initSegmentUrl).isEqualTo("https://cdn.example/live/init-mid.mp4")
    }

    @Test
    fun `a representation timeline overrides the adaptation set timeline`() {
        val mpd = """
            <MPD type="dynamic">
              <Period>
                <AdaptationSet contentType="video">
                  <SegmentTemplate timescale="1000" media="as-${'$'}Time${'$'}.m4s">
                    <SegmentTimeline>
                      <S t="0" d="2000" r="4"/>
                    </SegmentTimeline>
                  </SegmentTemplate>
                  <Representation id="v1" bandwidth="1000000">
                    <SegmentTemplate timescale="1000" media="rep-${'$'}Time${'$'}.m4s">
                      <SegmentTimeline>
                        <S t="10000" d="4000"/>
                      </SegmentTimeline>
                    </SegmentTemplate>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()

        val parsed = parseDashManifest("https://cdn.example/live/manifest.mpd", mpd, depthMs = 60_000L)

        assertThat(parsed.mediaSegments.map { it.uri }).containsExactly("https://cdn.example/live/rep-10000.m4s")
        assertThat(parsed.mediaSegments.single().durationMs).isEqualTo(4_000L)
    }

    @Test
    fun `fixed duration segments carry their number as position and start in milliseconds`() {
        val mpd = """
            <MPD type="dynamic" availabilityStartTime="2024-01-01T00:00:00Z">
              <Period>
                <AdaptationSet contentType="video">
                  <Representation id="v1" bandwidth="1000000">
                    <SegmentTemplate timescale="1000" duration="2000" startNumber="1" media="seg-${'$'}Number${'$'}.m4s"/>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val availabilityStartMs = java.time.Instant.parse("2024-01-01T00:00:00Z").toEpochMilli()

        // 100 s after availability start: segment 50 is the newest, and a 10 s depth keeps 5 + 2 more.
        val parsed = parseDashManifest(
            "https://cdn.example/live/manifest.mpd",
            mpd,
            depthMs = 10_000L,
            nowMs = availabilityStartMs + 100_000L
        )

        assertThat(parsed.mediaSegments.map { it.position }).containsExactlyElementsIn(43L..50L).inOrder()
        assertThat(parsed.mediaSegments.map { it.startMs }).containsExactlyElementsIn((43L..50L).map { it * 2_000L }).inOrder()
        assertThat(parsed.mediaSegments.first().uri).isEqualTo("https://cdn.example/live/seg-43.m4s")
        assertThat(parsed.mediaSegments.all { it.durationMs == 2_000L }).isTrue()
    }
}
