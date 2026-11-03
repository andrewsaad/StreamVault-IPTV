package com.streamvault.player.diagnostics

import com.google.common.truth.Truth.assertThat
import com.streamvault.player.PlaybackState
import com.streamvault.player.PlayerStats
import com.streamvault.player.stats.PlaybackBufferingTracker.Cause
import com.streamvault.player.stats.PlaybackBufferingTracker.Event
import com.streamvault.player.stats.PlaybackBufferingTracker.Outcome
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaybackJournalTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()
    private var wallMs = 1_793_707_200_000L // 2026-11-03T12:00:00Z
    private var elapsedMs = 10_000L
    private val failures = mutableListOf<Throwable>()
    private val stats = PlayerStats(
        bandwidthEstimate = 6_000_000L,
        measuredBitrate = 4_500_000L,
        videoBitrate = 5_000_000,
        width = 1920,
        height = 1080,
        bufferedDurationMs = 12_000L,
        rebufferCount = 1,
        droppedFrames = 3,
        ttffMs = 1_400L
    )

    private fun journal(
        dir: File = temporaryFolder.root,
        maxFileBytes: Long = PlaybackJournal.MAX_FILE_BYTES,
        capacity: Int = PlaybackJournal.CHANNEL_CAPACITY,
        networkProbe: () -> String = { "net=wifi rssi=-58 linkMbps=433 downKbps=60000" }
    ) = PlaybackJournal(
        directoryProvider = { dir },
        wallClockMs = { wallMs },
        elapsedMs = { elapsedMs },
        zone = { ZoneId.of("UTC") },
        networkProbe = networkProbe,
        writerScope = CoroutineScope(StandardTestDispatcher(scheduler)),
        maxFileBytes = maxFileBytes,
        capacity = capacity,
        failureLog = { _, error -> failures += error }
    )

    private fun PlaybackJournal.drain(): List<String> {
        close()
        scheduler.advanceUntilIdle()
        return File(temporaryFolder.root, PlaybackJournal.CURRENT_FILE).readLines()
    }

    private fun PlaybackJournal.start(url: String = "http://provider.example:8080/live/alice/s3cret/1234.ts") =
        startSession(
            channelName = "beIN Sports 1 \"HD\"",
            mediaId = "abc123",
            streamType = "MPEG_TS_LIVE",
            url = url,
            decoderPolicy = "AUTO",
            audioOutputPath = "SURROUND_UPMIX_AC3",
            upmix = true,
            bufferPolicy = "live-ts"
        )

    @Test
    fun `session start line has timestamp event and key values`() {
        val journal = journal()
        journal.start()

        val line = journal.drain().single()

        assertThat(line).startsWith("2026-11-03T12:00:00Z session-start ")
        assertThat(line).contains("channel=\"beIN Sports 1 \\\"HD\\\"\"")
        assertThat(line).contains("mediaId=abc123 type=MPEG_TS_LIVE")
        assertThat(line).contains("upmix=true policy=\"live-ts\"")
        assertThat(line).endsWith("net=wifi rssi=-58 linkMbps=433 downKbps=60000")
    }

    @Test
    fun `lines never contain credentials or raw urls`() {
        val journal = journal()
        journal.start()
        journal.startSession("x", "m", "HLS", "https://cdn.example/alice/s3cret/99?token=tok123", "AUTO", "AUTO", false, null)
        journal.onLoadError(
            httpCode = 403,
            errorClass = "InvalidResponseCodeException",
            message = "Response code: 403 for http://provider.example/live/alice/s3cret/1.ts?password=s3cret",
            dataType = 1
        )
        journal.onPlayerError("ERROR_CODE_IO_BAD_HTTP_STATUS", "HTTP_FORBIDDEN", "fetch https://h.example/alice/s3cret/7.m3u8 failed")

        val text = journal.drain().joinToString("\n")

        assertThat(text).doesNotContain("alice")
        assertThat(text).doesNotContain("s3cret")
        assertThat(text).doesNotContain("tok123")
        assertThat(text).doesNotContain("http://")
        assertThat(text).doesNotContain("https://")
        assertThat(text).contains("target=provider.example/live/<redacted>/<redacted>/1234.ts")
        assertThat(text).contains("target=cdn.example/<redacted>/<redacted>/99")
        assertThat(text).contains("load-error http=403 error=InvalidResponseCodeException")
    }

    @Test
    fun `non-http stream urls and two-segment paths are scrubbed too`() {
        val message = PlaybackJournal.journalMessage("connect rtmp://live.example/alice/s3cret failed; also rtsp://cam.example:554/alice/s3cret")

        assertThat(message).doesNotContain("alice")
        assertThat(message).doesNotContain("s3cret")
        assertThat(message).doesNotContain("rtmp://")
        assertThat(message).doesNotContain("rtsp://")
        assertThat(PlaybackJournal.journalTarget("https://cdn.example/alice/s3cret")).isEqualTo("cdn.example/<redacted>/<redacted>")
        assertThat(PlaybackJournal.journalTarget("https://cdn.example/hls/index.m3u8")).isEqualTo("cdn.example/<redacted>/index.m3u8")
        assertThat(PlaybackJournal.journalTarget("https://cdn.example/index.m3u8")).isEqualTo("cdn.example/index.m3u8")
    }

    @Test
    fun `buffering start and end track stall duration and session totals`() {
        val journal = journal()
        journal.start()
        journal.onBufferingEvent(Event(Cause.STARTUP, Outcome.STARTED, 0), 0, stats)
        elapsedMs += 1_500
        journal.onBufferingEvent(Event(Cause.STARTUP, Outcome.RESUMED, 1_500), 4_000, stats)
        journal.onPlaybackStarted()
        elapsedMs += 60_000
        journal.onBufferingEvent(Event(Cause.REBUFFER, Outcome.STARTED, 0), 0, stats)
        elapsedMs += 2_500
        journal.onBufferingEvent(Event(Cause.REBUFFER, Outcome.RESUMED, 2_500), 3_000, stats)
        journal.onBufferingEvent(Event(Cause.RECOVERY, Outcome.STARTED, 0), 0, stats)
        elapsedMs += 4_000
        journal.endSession("stop")

        assertThat(journal.stallCountForTest).isEqualTo(2)
        assertThat(journal.stallTotalMsForTest).isEqualTo(6_500)
        val lines = journal.drain()
        assertThat(lines.first { " startup-buffering " in it }).contains("durationMs=1500 sinceSessionStartMs=1500 ttffMs=1400")
        assertThat(lines.first { " buffering-start " in it })
            .contains("cause=rebuffer bufferMs=0 estimateBps=6000000 deliveredBps=4500000 selectedBps=5000000 size=1920x1080")
        assertThat(lines.first { " buffering-end " in it }).contains("cause=rebuffer outcome=resumed durationMs=2500 bufferMs=3000")
        assertThat(lines.last()).contains(
            "session-end reason=stop watchMs=68000 started=true rebuffers=2 rebufferMs=6500 worstStallMs=4000"
        )
    }

    @Test
    fun `sample is written every thirty active ticks only`() {
        val journal = journal()
        journal.start()
        repeat(29) { journal.onStatsTick(PlaybackState.READY, isPlaying = true, stats = stats) }
        repeat(10) { journal.onStatsTick(PlaybackState.READY, isPlaying = false, stats = stats) }
        repeat(10) { journal.onStatsTick(PlaybackState.IDLE, isPlaying = false, stats = stats) }
        journal.onStatsTick(PlaybackState.BUFFERING, isPlaying = false, stats = stats)
        repeat(30) { journal.onStatsTick(PlaybackState.READY, isPlaying = true, stats = stats) }

        val samples = journal.drain().filter { " sample " in it }

        assertThat(samples).hasSize(2)
        assertThat(samples[0]).contains("sample state=buffering bufferMs=12000")
        assertThat(samples[1]).contains("sample state=playing")
        assertThat(samples[1]).contains("rebuffers=1 drops=3 net=wifi rssi=-58")
    }

    @Test
    fun `enqueue never blocks and overflow is dropped then reported`() {
        val journal = journal(capacity = 4)
        journal.start()
        // The writer has not run yet, so the channel stays full and later lines are dropped.
        repeat(100) { journal.onVideoFormat(bitrate = it, width = 1280, height = 720) }

        scheduler.runCurrent()
        journal.onRetry(1, 3, 1_000, "transient-network")
        val lines = journal.drain()

        assertThat(lines.size).isLessThan(20)
        assertThat(lines.any { "journal-overflow dropped=" in it }).isTrue()
        assertThat(lines.last()).contains("retry attempt=1/3")
        assertThat(failures).isEmpty()
    }

    @Test
    fun `rotation keeps at most two files under the size cap`() {
        val journal = journal(maxFileBytes = 1_024)
        journal.start()
        repeat(200) { journal.onVideoFormat(bitrate = it, width = 1920, height = 1080) }
        journal.drain()

        val files = temporaryFolder.root.listFiles().orEmpty().map { it.name }.sorted()
        assertThat(files).containsExactly(PlaybackJournal.CURRENT_FILE, PlaybackJournal.ROTATED_FILE)
        files.forEach { assertThat(File(temporaryFolder.root, it).length()).isAtMost(1_024L) }
        assertThat(File(temporaryFolder.root, PlaybackJournal.CURRENT_FILE).readText()).contains("to=1920x1080@199")
    }

    @Test
    fun `dropped frames below the burst threshold are ignored`() {
        val journal = journal()
        journal.start()
        journal.onDroppedFrames(9, 2_000)
        journal.onDroppedFrames(25, 2_000)

        val drops = journal.drain().filter { "dropped-frames" in it }

        assertThat(drops).hasSize(1)
        assertThat(drops.single()).contains("count=25 windowMs=2000")
    }

    @Test
    fun `failing storage and network probe never throw into playback`() {
        val blocker = File(temporaryFolder.root, "not-a-dir").apply { writeText("x") }
        val journal = journal(dir = File(blocker, "child"), networkProbe = { error("binder died") })
        journal.start()
        journal.onBufferingEvent(Event(Cause.REBUFFER, Outcome.STARTED, 0), 0, stats)
        journal.endSession("stop")
        journal.close()
        scheduler.advanceUntilIdle()

        assertThat(File(blocker, "child").exists()).isFalse()
    }

    @Test
    fun `events outside a session are ignored`() {
        val journal = journal()
        journal.onPlayerError("ERROR_CODE_IO_UNSPECIFIED", "NETWORK", null)
        journal.endSession("stop")
        journal.close()
        scheduler.advanceUntilIdle()

        assertThat(File(temporaryFolder.root, PlaybackJournal.CURRENT_FILE).exists()).isFalse()
    }
}
