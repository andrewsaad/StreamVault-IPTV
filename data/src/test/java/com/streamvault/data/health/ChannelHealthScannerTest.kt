package com.streamvault.data.health

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.remote.dto.XtreamAuthResponse
import com.streamvault.data.remote.dto.XtreamServerInfo
import com.streamvault.data.remote.dto.XtreamUserInfo
import com.streamvault.data.remote.xtream.XtreamApiService
import com.streamvault.domain.model.ChannelHealthPauseReason
import com.streamvault.domain.model.ChannelHealthScanState
import com.streamvault.domain.model.ChannelHealthStatus
import com.streamvault.domain.repository.ProviderSnapshotRepository
import com.streamvault.domain.sync.AutomaticSyncActivity
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelHealthScannerTest {
    private val clock = TestClock(nowMillis = 12L * 3_600_000L)
    private val storage = InMemoryHealthPreferences()
    private val results = ChannelHealthResults(storage.preferences, clock)
    private val snapshots: ProviderSnapshotRepository = mock()
    private val channelDao: ChannelDao = mock()
    private val xtreamApi: XtreamApiService = mock()
    private val probe: ChannelHealthProbe = mock()
    private val scanner = ChannelHealthScanner(
        snapshots = snapshots,
        channelDao = channelDao,
        xtreamApi = xtreamApi,
        probe = probe,
        results = results,
        timings = ChannelHealthTimings(),
    )

    private suspend fun givenChannels(vararg channels: ChannelEntity) {
        whenever(snapshots.getSnapshot(TEST_PROVIDER_ID)).thenReturn(xtreamSnapshot())
        whenever(channelDao.getByProviderSync(any())).thenReturn(channels.toList())
    }

    private suspend fun givenAccount(maxConnections: String, vararg activeConnections: String) {
        val answers = activeConnections.map { active ->
            XtreamAuthResponse(
                userInfo = XtreamUserInfo(auth = 1, maxConnections = maxConnections, activeConnections = active),
                serverInfo = XtreamServerInfo(),
            )
        }
        whenever(xtreamApi.authenticate(any(), any())).thenReturn(answers.first(), *answers.drop(1).toTypedArray())
    }

    private val ok = ChannelProbeResult(ChannelHealthStatus.OK)
    private val empty = ChannelProbeResult(ChannelHealthStatus.EMPTY)
    private val unclear = ChannelProbeResult(ChannelHealthStatus.UNCLEAR)
    private val serverError = ChannelProbeResult(ChannelHealthStatus.UNCLEAR, serverError = true)

    private suspend fun givenProbe(status: ChannelHealthStatus) {
        whenever(probe.probe(any(), any())).thenReturn(ChannelProbeResult(status))
    }

    private fun TestScope.collectStates(into: MutableList<ChannelHealthScanState>) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            scanner.observeState(TEST_PROVIDER_ID).toList(into)
        }
    }

    @Test
    fun `a scan probes every live channel with the provider login and records the answers`() = runTest {
        givenChannels(channelEntity(1L), channelEntity(2L))
        givenAccount("1", "0")
        whenever(probe.probe(any(), any())).doAnswer {
            ChannelProbeResult(if (it.getArgument<String>(0).endsWith("/11.m3u8")) ChannelHealthStatus.EMPTY else ChannelHealthStatus.OK)
        }

        val finished = scanner.run(TEST_PROVIDER_ID, categoryIds = null, startedAt = 0L)

        assertThat(finished).isTrue()
        val records = results.read(TEST_PROVIDER_ID).records
        assertThat(records.getValue(1L).status).isEqualTo(ChannelHealthStatus.EMPTY)
        assertThat(records.getValue(1L).failedDays).isEqualTo(1)
        // A channel that works has no record.
        assertThat(records.keys).containsExactly(1L)
        val state = scanner.observeState(TEST_PROVIDER_ID).first()
        assertThat(state.running).isFalse()
        assertThat(state.scanned).isEqualTo(2)
        assertThat(state.total).isEqualTo(2)
        assertThat(state.lastFinishedAt).isEqualTo(clock.nowMillis)
        assertThat(state.gaveUp).isFalse()
        verify(probe).probe(
            "$TEST_SERVER/live/$TEST_USERNAME/$TEST_PASSWORD/11.m3u8",
            emptyMap(),
        )
    }

    @Test
    fun `only the chosen categories are scanned`() = runTest {
        givenChannels(
            channelEntity(1L, categoryId = 100L),
            channelEntity(2L, categoryId = 200L),
            channelEntity(3L, categoryId = null),
        )
        givenAccount("1", "0")
        givenProbe(ChannelHealthStatus.OK)

        scanner.run(TEST_PROVIDER_ID, categoryIds = setOf(200L), startedAt = 0L)

        verify(probe).probe(any(), any())
        assertThat(scanner.observeState(TEST_PROVIDER_ID).first().total).isEqualTo(1)
    }

    @Test
    fun `a scan that was restarted skips the channels it had already settled`() = runTest {
        givenChannels(channelEntity(1L), channelEntity(2L))
        givenAccount("1", "0")
        givenProbe(ChannelHealthStatus.OK)
        results.record(TEST_PROVIDER_ID, emptyList(), ChannelHealthResume(startedAt = 500L, afterChannelId = 1L))

        scanner.run(TEST_PROVIDER_ID, categoryIds = null, startedAt = 500L)

        verify(probe).probe(any(), any())
        assertThat(scanner.observeState(TEST_PROVIDER_ID).first().scanned).isEqualTo(2)
        assertThat(results.read(TEST_PROVIDER_ID).resume).isNull()
    }

    @Test
    fun `a progress marker of an older scan is ignored`() = runTest {
        givenChannels(channelEntity(1L), channelEntity(2L))
        givenAccount("1", "0")
        givenProbe(ChannelHealthStatus.OK)
        results.record(TEST_PROVIDER_ID, emptyList(), ChannelHealthResume(startedAt = 100L, afterChannelId = 2L))

        scanner.run(TEST_PROVIDER_ID, categoryIds = null, startedAt = 500L)

        verify(probe, times(2)).probe(any(), any())
    }

    @Test
    fun `a scan stopped part way carries on after the last probed channel`() = runTest {
        givenChannels(*(1L..5L).map { channelEntity(it) }.toTypedArray())
        givenAccount("1", "0")
        givenProbe(ChannelHealthStatus.OK)
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { scanner.run(TEST_PROVIDER_ID, null, 500L) }
        // Probes at 0 s, 6 s and 12 s.
        advanceTimeBy(12_000L)
        runCurrent()
        job.cancel()
        advanceUntilIdle()
        assertThat(results.read(TEST_PROVIDER_ID).resume).isEqualTo(ChannelHealthResume(500L, 3L))

        scanner.run(TEST_PROVIDER_ID, null, 500L)

        // Three probes before the stop and the two channels left afterwards.
        verify(probe, times(5)).probe(any(), any())
    }

    @Test
    fun `three unclear answers in a row pause the scan for the provider backoff`() = runTest {
        givenChannels(*(1L..6L).map { channelEntity(it) }.toTypedArray())
        givenAccount("1", "0")
        givenProbe(ChannelHealthStatus.UNCLEAR)
        val job = backgroundScope.launch { scanner.run(TEST_PROVIDER_ID, null, 0L) }

        // Probes at 0 s, 6 s and 12 s, then the backoff starts.
        advanceTimeBy(12_000L)
        runCurrent()
        assertThat(scanner.observeState(TEST_PROVIDER_ID).first().paused)
            .isEqualTo(ChannelHealthPauseReason.PROVIDER_BACKOFF)
        assertThat(scanner.observeState(TEST_PROVIDER_ID).first().scanned).isEqualTo(3)

        advanceTimeBy(5L * 60_000L)
        runCurrent()
        assertThat(scanner.observeState(TEST_PROVIDER_ID).first().paused).isNull()
        job.cancel()
    }

    @Test
    fun `ten empty answers in a row store nothing and back the scan off`() = runTest {
        givenChannels(*(1L..14L).map { channelEntity(it) }.toTypedArray())
        givenAccount("1", "0")
        givenProbe(ChannelHealthStatus.EMPTY)
        val job = backgroundScope.launch { scanner.run(TEST_PROVIDER_ID, null, 0L) }

        // The tenth probe is at 54 s; the backoff starts right after it.
        advanceTimeBy(54_000L)
        runCurrent()
        assertThat(scanner.observeState(TEST_PROVIDER_ID).first().paused)
            .isEqualTo(ChannelHealthPauseReason.PROVIDER_BACKOFF)
        assertThat(results.read(TEST_PROVIDER_ID).records).isEmpty()

        advanceTimeBy(5L * 60_000L)
        runCurrent()
        // The account info is read again before the scan carries on.
        verify(xtreamApi, times(2)).authenticate(any(), any())
        job.cancel()
        advanceUntilIdle()
        assertThat(results.read(TEST_PROVIDER_ID).records.keys).doesNotContain(1L)
    }

    @Test
    fun `nine empty answers followed by a good one are all stored`() = runTest {
        givenChannels(*(1L..10L).map { channelEntity(it) }.toTypedArray())
        givenAccount("1", "0")
        whenever(probe.probe(any(), any())).doAnswer {
            ChannelProbeResult(if (it.getArgument<String>(0).endsWith("/20.m3u8")) ChannelHealthStatus.OK else ChannelHealthStatus.EMPTY)
        }

        assertThat(scanner.run(TEST_PROVIDER_ID, null, 0L)).isTrue()

        assertThat(results.read(TEST_PROVIDER_ID).records.keys).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L)
    }

    @Test
    fun `an isolated empty answer between good ones is stored`() = runTest {
        givenChannels(channelEntity(1L), channelEntity(2L), channelEntity(3L))
        givenAccount("1", "0")
        whenever(probe.probe(any(), any())).doAnswer {
            ChannelProbeResult(if (it.getArgument<String>(0).endsWith("/12.m3u8")) ChannelHealthStatus.EMPTY else ChannelHealthStatus.OK)
        }

        assertThat(scanner.run(TEST_PROVIDER_ID, null, 0L)).isTrue()

        val records = results.read(TEST_PROVIDER_ID).records
        assertThat(records.getValue(2L).status).isEqualTo(ChannelHealthStatus.EMPTY)
        assertThat(records.getValue(2L).failedDays).isEqualTo(1)
    }

    @Test
    fun `a scan gives up after repeated backoffs and does not count as finished`() = runTest {
        givenChannels(*(1L..20L).map { channelEntity(it) }.toTypedArray())
        givenAccount("1", "0")
        givenProbe(ChannelHealthStatus.UNCLEAR)

        val finished = scanner.run(TEST_PROVIDER_ID, null, 0L)

        assertThat(finished).isFalse()
        val state = scanner.observeState(TEST_PROVIDER_ID).first()
        assertThat(state.running).isFalse()
        assertThat(state.paused).isNull()
        assertThat(state.lastFinishedAt).isNull()
        assertThat(state.gaveUp).isTrue()
        assertThat(results.read(TEST_PROVIDER_ID).records).isEmpty()
    }

    @Test
    fun `repeated mass failures also count as giving up`() = runTest {
        givenChannels(*(1L..40L).map { channelEntity(it) }.toTypedArray())
        givenAccount("1", "0")
        givenProbe(ChannelHealthStatus.EMPTY)

        assertThat(scanner.run(TEST_PROVIDER_ID, null, 0L)).isFalse()

        assertThat(scanner.observeState(TEST_PROVIDER_ID).first().gaveUp).isTrue()
    }

    @Test
    fun `a scan that gave up stops reporting it once a new scan is queued or the user stops`() = runTest {
        givenChannels(*(1L..20L).map { channelEntity(it) }.toTypedArray())
        givenAccount("1", "0")
        givenProbe(ChannelHealthStatus.UNCLEAR)
        scanner.run(TEST_PROVIDER_ID, null, 0L)
        assertThat(scanner.observeState(TEST_PROVIDER_ID).first().gaveUp).isTrue()

        scanner.markQueued(TEST_PROVIDER_ID)
        assertThat(scanner.observeState(TEST_PROVIDER_ID).first().gaveUp).isFalse()

        // A different startedAt each time, so no run carries on from the previous one's progress.
        scanner.run(TEST_PROVIDER_ID, null, 1L)
        assertThat(scanner.observeState(TEST_PROVIDER_ID).first().gaveUp).isTrue()
        scanner.markStopped(TEST_PROVIDER_ID)
        assertThat(scanner.observeState(TEST_PROVIDER_ID).first().gaveUp).isFalse()

        scanner.run(TEST_PROVIDER_ID, null, 2L)
        assertThat(scanner.observeState(TEST_PROVIDER_ID).first().gaveUp).isTrue()
        scanner.clearGaveUp(TEST_PROVIDER_ID)
        assertThat(scanner.observeState(TEST_PROVIDER_ID).first().gaveUp).isFalse()
    }

    @Test
    fun `a scan the user cancels does not count as giving up`() = runTest {
        givenChannels(*(1L..5L).map { channelEntity(it) }.toTypedArray())
        givenAccount("1", "0")
        givenProbe(ChannelHealthStatus.OK)
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { scanner.run(TEST_PROVIDER_ID, null, 500L) }
        advanceTimeBy(6_000L)
        runCurrent()

        job.cancel()
        advanceUntilIdle()

        val state = scanner.observeState(TEST_PROVIDER_ID).first()
        assertThat(state.running).isFalse()
        assertThat(state.gaveUp).isFalse()
    }

    @Test
    fun `an unclear answer does not stop a good one from clearing the backoff count`() = runTest {
        givenChannels(*(1L..8L).map { channelEntity(it) }.toTypedArray())
        givenAccount("1", "0")
        var calls = 0
        whenever(probe.probe(any(), any())).doAnswer {
            // Two unclear, one good, repeating: never three unclear in a row.
            ChannelProbeResult(if (++calls % 3 == 0) ChannelHealthStatus.OK else ChannelHealthStatus.UNCLEAR)
        }

        val finished = scanner.run(TEST_PROVIDER_ID, null, 0L)

        assertThat(finished).isTrue()
        assertThat(scanner.observeState(TEST_PROVIDER_ID).first().lastFinishedAt).isNotNull()
    }

    /**
     * Answers per channel id, one entry per attempt; the last one repeats. Returns the ids in the order
     * they were probed.
     */
    private suspend fun givenProbeAnswers(answers: Map<Long, List<ChannelProbeResult>>): MutableList<Long> {
        val probed = mutableListOf<Long>()
        val attempts = mutableMapOf<Long, Int>()
        whenever(probe.probe(any(), any())).doAnswer {
            // The stream id is the channel id plus 10 in these tests.
            val id = it.getArgument<String>(0).substringAfterLast('/').removeSuffix(".m3u8").toLong() - 10L
            probed += id
            val attempt = attempts.getOrDefault(id, 0)
            attempts[id] = attempt + 1
            val list = answers.getValue(id)
            list[minOf(attempt, list.lastIndex)]
        }
        return probed
    }

    @Test
    fun `channels that answered unclear are probed once more after the provider backoff`() = runTest {
        givenChannels(*(1L..4L).map { channelEntity(it) }.toTypedArray())
        givenAccount("1", "0")
        val probed = givenProbeAnswers(
            mapOf(
                1L to listOf(ok),
                2L to listOf(unclear, ok),
                3L to listOf(unclear, ok),
                4L to listOf(ok),
            ),
        )

        val finished = scanner.run(TEST_PROVIDER_ID, null, 0L)

        assertThat(finished).isTrue()
        assertThat(probed).containsExactly(1L, 2L, 3L, 4L, 2L, 3L).inOrder()
        // Four probes 6 s apart, then the 5 minute backoff before the retries.
        assertThat(testScheduler.currentTime).isAtLeast(18_000L + 5L * 60_000L)
        val state = scanner.observeState(TEST_PROVIDER_ID).first()
        assertThat(state.scanned).isEqualTo(6)
        assertThat(state.total).isEqualTo(6)
        assertThat(state.lastFinishedAt).isEqualTo(clock.nowMillis)
        assertThat(results.read(TEST_PROVIDER_ID).resume).isNull()
    }

    @Test
    fun `a retry that now answers empty is stored with one failed day`() = runTest {
        givenChannels(channelEntity(1L), channelEntity(2L))
        givenAccount("1", "0")
        givenProbeAnswers(
            mapOf(
                1L to listOf(unclear, empty),
                2L to listOf(ok),
            ),
        )

        assertThat(scanner.run(TEST_PROVIDER_ID, null, 0L)).isTrue()

        val records = results.read(TEST_PROVIDER_ID).records
        assertThat(records.keys).containsExactly(1L)
        assertThat(records.getValue(1L).status).isEqualTo(ChannelHealthStatus.EMPTY)
        assertThat(records.getValue(1L).failedDays).isEqualTo(1)
    }

    @Test
    fun `a retry that answers ok clears the channel's record`() = runTest {
        givenChannels(channelEntity(1L))
        givenAccount("1", "0")
        results.record(
            TEST_PROVIDER_ID,
            listOf(ChannelProbeOutcome(1L, ChannelHealthStatus.EMPTY, clock.nowMillis - DAY_MS)),
        )
        assertThat(results.read(TEST_PROVIDER_ID).records.keys).containsExactly(1L)
        givenProbeAnswers(mapOf(1L to listOf(unclear, ok)))

        assertThat(scanner.run(TEST_PROVIDER_ID, null, 0L)).isTrue()

        assertThat(results.read(TEST_PROVIDER_ID).records).isEmpty()
    }

    @Test
    fun `a channel that is still unclear after the retry is neither stored nor tried a third time`() = runTest {
        givenChannels(channelEntity(1L), channelEntity(2L))
        givenAccount("1", "0")
        val probed = givenProbeAnswers(
            mapOf(
                1L to listOf(unclear),
                2L to listOf(ok),
            ),
        )

        assertThat(scanner.run(TEST_PROVIDER_ID, null, 0L)).isTrue()

        assertThat(probed).containsExactly(1L, 2L, 1L).inOrder()
        assertThat(results.read(TEST_PROVIDER_ID).records).isEmpty()
        assertThat(scanner.observeState(TEST_PROVIDER_ID).first().lastFinishedAt).isNotNull()
    }

    @Test
    fun `a scan stopped during the retry pass resumes the remaining retries after a restart`() = runTest {
        givenChannels(*(1L..5L).map { channelEntity(it) }.toTypedArray())
        givenAccount("1", "0")
        val probed = givenProbeAnswers(
            mapOf(
                1L to listOf(unclear, empty),
                2L to listOf(ok),
                3L to listOf(unclear, ok),
                4L to listOf(ok),
                5L to listOf(unclear, empty),
            ),
        )
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { scanner.run(TEST_PROVIDER_ID, null, 500L) }
        // Main pass probes at 0 to 24 s, the backoff ends at 324 s, then retries at 324 s and 330 s.
        advanceTimeBy(330_000L)
        runCurrent()
        job.cancel()
        advanceUntilIdle()
        assertThat(probed).containsExactly(1L, 2L, 3L, 4L, 5L, 1L, 3L).inOrder()
        assertThat(results.read(TEST_PROVIDER_ID).resume)
            .isEqualTo(
                ChannelHealthResume(500L, 5L, listOf(1L, 3L, 5L), 3L, retryOk = 1, retryAttempts = 2),
            )

        assertThat(scanner.run(TEST_PROVIDER_ID, null, 500L)).isTrue()

        // Only the last retry is left; no new backoff and no second main pass.
        assertThat(probed.drop(7)).containsExactly(5L)
        val document = results.read(TEST_PROVIDER_ID)
        assertThat(document.records.keys).containsExactly(1L, 5L)
        assertThat(document.records.getValue(5L).failedDays).isEqualTo(1)
        assertThat(document.resume).isNull()
        assertThat(document.lastFinishedAt).isEqualTo(clock.nowMillis)
        val state = scanner.observeState(TEST_PROVIDER_ID).first()
        assertThat(state.scanned).isEqualTo(8)
        assertThat(state.total).isEqualTo(8)
    }

    @Test
    fun `no retry pass or backoff when nothing was unclear`() = runTest {
        givenChannels(channelEntity(1L), channelEntity(2L))
        givenAccount("1", "0")
        val probed = givenProbeAnswers(
            mapOf(
                1L to listOf(ok),
                2L to listOf(empty),
            ),
        )

        assertThat(scanner.run(TEST_PROVIDER_ID, null, 0L)).isTrue()

        assertThat(probed).containsExactly(1L, 2L).inOrder()
        assertThat(testScheduler.currentTime).isLessThan(60_000L)
    }

    /**
     * Answers for channels 1..[total] where the [refused] ones answer 503 every time, the [recovering] ones
     * time out once and then answer OK, and the rest answer OK. Keep refused and recovering ids apart so the
     * main pass never has three unclear answers in a row.
     */
    private fun flakyProvider(total: Long, refused: Set<Long>, recovering: Set<Long>): Map<Long, List<ChannelProbeResult>> =
        (1L..total).associateWith {
            when (it) {
                in refused -> listOf(serverError)
                in recovering -> listOf(unclear, ok)
                else -> listOf(ok)
            }
        }

    @Test
    fun `a channel the provider refuses with a server error on both passes is stored as empty`() = runTest {
        givenChannels(*(1L..7L).map { channelEntity(it) }.toTypedArray())
        givenAccount("1", "0")
        // Three other channels recover on the retry, so the provider was serving while it refused channel 1.
        val probed = givenProbeAnswers(flakyProvider(7L, refused = setOf(1L), recovering = setOf(3L, 5L, 7L)))

        assertThat(scanner.run(TEST_PROVIDER_ID, null, 0L)).isTrue()

        assertThat(probed.drop(7)).containsExactly(1L, 3L, 5L, 7L).inOrder()
        val records = results.read(TEST_PROVIDER_ID).records
        assertThat(records.keys).containsExactly(1L)
        assertThat(records.getValue(1L).status).isEqualTo(ChannelHealthStatus.EMPTY)
        assertThat(records.getValue(1L).failedDays).isEqualTo(1)
    }

    @Test
    fun `server errors on two channels with three good answers around them in the retry pass are stored`() = runTest {
        givenChannels(*(1L..9L).map { channelEntity(it) }.toTypedArray())
        givenAccount("1", "0")
        givenProbeAnswers(flakyProvider(9L, refused = setOf(1L, 5L), recovering = setOf(3L, 7L, 9L)))

        assertThat(scanner.run(TEST_PROVIDER_ID, null, 0L)).isTrue()

        val records = results.read(TEST_PROVIDER_ID).records
        assertThat(records.keys).containsExactly(1L, 5L)
        assertThat(records.values.map { it.status }.distinct()).containsExactly(ChannelHealthStatus.EMPTY)
    }

    @Test
    fun `a server error provider wide with only five retry channels stores nothing`() = runTest {
        givenChannels(*(1L..5L).map { channelEntity(it) }.toTypedArray())
        givenAccount("1", "0")
        val probed = givenProbeAnswers((1L..5L).associateWith { listOf(serverError) })

        // Too few retry channels for the mass failure guard or the give up count to act.
        assertThat(scanner.run(TEST_PROVIDER_ID, null, 0L)).isTrue()

        assertThat(probed).hasSize(10)
        assertThat(results.read(TEST_PROVIDER_ID).records).isEmpty()
        assertThat(scanner.observeState(TEST_PROVIDER_ID).first().lastFinishedAt).isNotNull()
    }

    @Test
    fun `intermittent server errors with at least half of the retry pass refused store nothing`() = runTest {
        givenChannels(*(1L..11L).map { channelEntity(it) }.toTypedArray())
        givenAccount("1", "0")
        // The retry pass alternates 503 and OK: three refused out of six attempts is exactly half.
        givenProbeAnswers(flakyProvider(11L, refused = setOf(1L, 5L, 9L), recovering = setOf(3L, 7L, 11L)))

        assertThat(scanner.run(TEST_PROVIDER_ID, null, 0L)).isTrue()

        assertThat(results.read(TEST_PROVIDER_ID).records).isEmpty()
        assertThat(scanner.observeState(TEST_PROVIDER_ID).first().lastFinishedAt).isNotNull()
    }

    @Test
    fun `a small retry queue after a healthy main pass with one good retry answer stores the refused channels`() = runTest {
        givenChannels(*(1L..53L).map { channelEntity(it) }.toTypedArray())
        givenAccount("1", "0")
        // Exactly 50 good main pass answers (48 + channels 50 and 52); the retry queue is 49, 51 and 53.
        givenProbeAnswers(flakyProvider(53L, refused = setOf(49L, 53L), recovering = setOf(51L)))

        assertThat(scanner.run(TEST_PROVIDER_ID, null, 0L)).isTrue()

        assertThat(results.read(TEST_PROVIDER_ID).records.keys).containsExactly(49L, 53L)
    }

    @Test
    fun `a healthy main pass does not help when the retry pass has no good answer`() = runTest {
        givenChannels(*(1L..53L).map { channelEntity(it) }.toTypedArray())
        givenAccount("1", "0")
        givenProbeAnswers(flakyProvider(53L, refused = setOf(49L, 51L, 53L), recovering = emptySet()))

        assertThat(scanner.run(TEST_PROVIDER_ID, null, 0L)).isTrue()

        assertThat(results.read(TEST_PROVIDER_ID).records).isEmpty()
    }

    @Test
    fun `refused channels are stored when the retry pass alone shows a healthy provider`() {
        assertThat(shouldStoreRefusals(mainOk = 0, retryOk = 3, retryAttempts = 6, refused = 2)).isTrue()
        assertThat(shouldStoreRefusals(mainOk = 0, retryOk = 3, retryAttempts = 4, refused = 1)).isTrue()
        assertThat(shouldStoreRefusals(mainOk = 0, retryOk = 4, retryAttempts = 7, refused = 3)).isTrue()
    }

    @Test
    fun `refused channels are dropped when the retry pass has fewer than three good answers`() {
        assertThat(shouldStoreRefusals(mainOk = 0, retryOk = 2, retryAttempts = 4, refused = 1)).isFalse()
        assertThat(shouldStoreRefusals(mainOk = 49, retryOk = 0, retryAttempts = 5, refused = 5)).isFalse()
    }

    @Test
    fun `refused channels are dropped when half or more of the retry pass was refused`() {
        assertThat(shouldStoreRefusals(mainOk = 0, retryOk = 3, retryAttempts = 6, refused = 3)).isFalse()
        assertThat(shouldStoreRefusals(mainOk = 0, retryOk = 3, retryAttempts = 6, refused = 4)).isFalse()
    }

    @Test
    fun `a main pass with fifty good answers and one good retry answer is enough`() {
        assertThat(shouldStoreRefusals(mainOk = 50, retryOk = 1, retryAttempts = 3, refused = 2)).isTrue()
        assertThat(shouldStoreRefusals(mainOk = 49, retryOk = 1, retryAttempts = 3, refused = 2)).isFalse()
        assertThat(shouldStoreRefusals(mainOk = 500, retryOk = 0, retryAttempts = 3, refused = 3)).isFalse()
    }

    @Test
    fun `nothing is stored when no channel was refused`() {
        assertThat(shouldStoreRefusals(mainOk = 500, retryOk = 10, retryAttempts = 10, refused = 0)).isFalse()
    }

    @Test
    fun `a server error that turns into a good answer on the retry clears the record`() = runTest {
        givenChannels(channelEntity(1L))
        givenAccount("1", "0")
        results.record(
            TEST_PROVIDER_ID,
            listOf(ChannelProbeOutcome(1L, ChannelHealthStatus.EMPTY, clock.nowMillis - DAY_MS)),
        )
        givenProbeAnswers(mapOf(1L to listOf(serverError, ok)))

        assertThat(scanner.run(TEST_PROVIDER_ID, null, 0L)).isTrue()

        assertThat(results.read(TEST_PROVIDER_ID).records).isEmpty()
    }

    @Test
    fun `a server error followed by a timeout on the retry is not stored`() = runTest {
        givenChannels(channelEntity(1L), channelEntity(2L))
        givenAccount("1", "0")
        givenProbeAnswers(mapOf(1L to listOf(serverError, unclear), 2L to listOf(ok)))

        assertThat(scanner.run(TEST_PROVIDER_ID, null, 0L)).isTrue()

        assertThat(results.read(TEST_PROVIDER_ID).records).isEmpty()
    }

    @Test
    fun `a timeout on the main pass followed by a server error on the retry is not stored`() = runTest {
        givenChannels(channelEntity(1L), channelEntity(2L))
        givenAccount("1", "0")
        givenProbeAnswers(mapOf(1L to listOf(unclear, serverError), 2L to listOf(ok)))

        assertThat(scanner.run(TEST_PROVIDER_ID, null, 0L)).isTrue()

        assertThat(results.read(TEST_PROVIDER_ID).records).isEmpty()
    }

    @Test
    fun `a storm of server errors in the retry pass stores nothing and backs the scan off`() = runTest {
        val stormScanner = ChannelHealthScanner(
            snapshots = snapshots,
            channelDao = channelDao,
            xtreamApi = xtreamApi,
            probe = probe,
            results = results,
            // Out of the way, so the ten-in-a-row mass failure guard is the one that acts.
            timings = ChannelHealthTimings(unclearInARowBeforeBackoff = 100),
        )
        givenChannels(*(1L..40L).map { channelEntity(it) }.toTypedArray())
        givenAccount("1", "0")
        // Odd channels answer 503 every time, even ones are fine: 20 retries, all refused.
        givenProbeAnswers((1L..40L).associateWith { if (it % 2L == 1L) listOf(serverError) else listOf(ok) })

        assertThat(stormScanner.run(TEST_PROVIDER_ID, null, 0L)).isTrue()

        assertThat(results.read(TEST_PROVIDER_ID).records).isEmpty()
    }

    @Test
    fun `a scan that gives up in a retry pass full of server errors stores nothing`() = runTest {
        givenChannels(*(1L..40L).map { channelEntity(it) }.toTypedArray())
        givenAccount("1", "0")
        givenProbeAnswers((1L..40L).associateWith { if (it % 2L == 1L) listOf(serverError) else listOf(ok) })

        assertThat(scanner.run(TEST_PROVIDER_ID, null, 0L)).isFalse()

        assertThat(results.read(TEST_PROVIDER_ID).records).isEmpty()
        assertThat(scanner.observeState(TEST_PROVIDER_ID).first().gaveUp).isTrue()
    }

    @Test
    fun `server errors remembered for the retry pass survive a restart`() = runTest {
        givenChannels(*(1L..7L).map { channelEntity(it) }.toTypedArray())
        givenAccount("1", "0")
        givenProbeAnswers(flakyProvider(7L, refused = setOf(1L), recovering = setOf(3L, 5L, 7L)))
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { scanner.run(TEST_PROVIDER_ID, null, 500L) }
        // Main pass at 0 to 36 s, the backoff ends at 336 s, then the first retry (channel 1, refused).
        advanceTimeBy(336_000L)
        runCurrent()
        job.cancel()
        advanceUntilIdle()
        // This used to pin the retry point at 0: the refusal held it back, so every restart re-probed it. The
        // refusal is now saved with the resume (and still counts toward the mass-failure streak), the retry pass
        // resumes after it, and nothing is stored as EMPTY yet.
        assertThat(results.read(TEST_PROVIDER_ID).resume)
            .isEqualTo(
                ChannelHealthResume(
                    500L,
                    7L,
                    listOf(1L, 3L, 5L, 7L),
                    1L,
                    listOf(1L),
                    listOf(ChannelRefusal(1L, clock.nowMillis, inStreak = true)),
                    retryOk = 0,
                    retryAttempts = 1,
                ),
            )
        assertThat(results.read(TEST_PROVIDER_ID).records).isEmpty()

        assertThat(scanner.run(TEST_PROVIDER_ID, null, 500L)).isTrue()

        assertThat(results.read(TEST_PROVIDER_ID).records.keys).containsExactly(1L)
    }

    @Test
    fun `a scan stopped over and over in a long retry pass with an early refusal still finishes without re-probing`() = runTest {
        val total = 300L
        givenChannels(*(1L..total).map { channelEntity(it) }.toTypedArray())
        givenAccount("1", "0")
        // Channel 1 is refused with a 503 on both passes; the other odd channels time out once and recover, so
        // the retry queue holds 150 channels (about 15 minutes at the 6 s pace, more than one run).
        val probed = givenProbeAnswers(
            (1L..total).associateWith {
                when {
                    it == 1L -> listOf(serverError, serverError)
                    it % 2L == 1L -> listOf(unclear, ok)
                    else -> listOf(ok)
                }
            },
        )
        var finished = false
        var runs = 0
        while (!finished && runs < 30) {
            runs++
            val before = probed.size
            val saved = results.read(TEST_PROVIDER_ID).resume
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                finished = scanner.run(TEST_PROVIDER_ID, null, 500L)
            }
            // WorkManager stops the job about every 10 minutes, which is about 100 channels at the 6 s pace.
            advanceTimeBy(600_000L)
            runCurrent()
            if (saved != null) {
                val inRetry = saved.retryAfterChannelId != null || saved.afterChannelId >= total
                val point = when {
                    saved.retryAfterChannelId != null -> saved.retryAfterChannelId!!
                    inRetry -> 0L
                    else -> saved.afterChannelId
                }
                val fresh = probed.drop(before)
                // The part of the run that continues the saved pass is ascending and must start after the saved point.
                val samePass = fresh.withIndex().takeWhile { (i, id) -> i == 0 || id > fresh[i - 1] }.map { it.value }
                assertThat(samePass.filter { it <= point }).isEmpty()
            }
            if (!job.isCompleted) {
                job.cancel()
                advanceUntilIdle()
            }
        }

        assertThat(finished).isTrue()
        assertThat(runs).isLessThan(30)
        // Main pass once, retry pass once: the early refusal is never probed again after a stop.
        assertThat(probed.count { it == 1L }).isEqualTo(2)
        val document = results.read(TEST_PROVIDER_ID)
        assertThat(document.resume).isNull()
        assertThat(document.records.keys).containsExactly(1L)
        assertThat(document.records.getValue(1L).status).isEqualTo(ChannelHealthStatus.EMPTY)
    }

    @Test
    fun `refusals dropped by a mass failure no longer count as refused when the provider is judged`() = runTest {
        val noBackoffOnUnclear = ChannelHealthScanner(
            snapshots = snapshots,
            channelDao = channelDao,
            xtreamApi = xtreamApi,
            probe = probe,
            results = results,
            // Out of the way, so the ten-in-a-row mass failure guard is the one that acts.
            timings = ChannelHealthTimings(unclearInARowBeforeBackoff = 100),
        )
        givenChannels(*(1L..43L).map { channelEntity(it) }.toTypedArray())
        givenAccount("1", "0")
        // Ten refusals in a row in the retry pass (dropped by the mass failure guard), eleven recoveries, then one
        // refusal that stays. 22 retry attempts and 11 good answers: the old stale count of 11 refusals failed the
        // half rule (22 < 22) and stored nothing; the one real refusal passes it (2 < 22).
        givenProbeAnswers(
            (1L..43L).associateWith {
                when {
                    it % 2L == 0L -> listOf(ok)
                    it <= 19L || it == 43L -> listOf(serverError)
                    else -> listOf(unclear, ok)
                }
            },
        )

        assertThat(noBackoffOnUnclear.run(TEST_PROVIDER_ID, null, 0L)).isTrue()

        val records = results.read(TEST_PROVIDER_ID).records
        assertThat(records.keys).containsExactly(43L)
        assertThat(records.getValue(43L).status).isEqualTo(ChannelHealthStatus.EMPTY)
    }

    @Test
    fun `refusals saved in the mass failure streak still count toward it after a restart`() = runTest {
        givenChannels(*(1L..12L).map { channelEntity(it) }.toTypedArray())
        givenAccount("1", "0")
        // Nine refusals were in the streak when the scan was stopped; one more refusal after the restart makes ten
        // in a row. Eleven good answers and 60 attempts would pass the half rule, so only the guard stops them
        // being stored as EMPTY.
        results.record(
            TEST_PROVIDER_ID,
            emptyList(),
            ChannelHealthResume(
                500L,
                12L,
                (1L..10L).toList(),
                9L,
                (1L..10L).toList(),
                (1L..9L).map { ChannelRefusal(it, 77L, inStreak = true) },
                retryOk = 11,
                retryAttempts = 60,
            ),
        )
        val probed = givenProbeAnswers((1L..12L).associateWith { listOf(serverError) })

        assertThat(scanner.run(TEST_PROVIDER_ID, null, 500L)).isTrue()

        assertThat(probed).containsExactly(10L)
        assertThat(results.read(TEST_PROVIDER_ID).records).isEmpty()
    }

    @Test
    fun `a progress marker stored before retries existed still reads back`() = runTest {
        storage.health.value = """{"resumeStartedAt":500,"resumeAfterId":1}"""

        assertThat(results.read(TEST_PROVIDER_ID).resume).isEqualTo(ChannelHealthResume(500L, 1L))
    }

    @Test
    fun `a progress marker saved by the build before refusals were stored still decodes and resumes`() = runTest {
        // Exactly the fields the previous build wrote: no refusals, no retry counts.
        storage.health.value =
            """{"resumeStartedAt":500,"resumeAfterId":4,"resumeRetryIds":[1],"resumeRetryAfterId":0,"resumeServerErrorIds":[1]}"""
        val resume = results.read(TEST_PROVIDER_ID).resume
        assertThat(resume).isEqualTo(ChannelHealthResume(500L, 4L, listOf(1L), 0L, listOf(1L)))
        assertThat(resume!!.refused).isEmpty()
        assertThat(resume.retryOk).isEqualTo(0)
        assertThat(resume.retryAttempts).isEqualTo(0)
        givenChannels(*(1L..4L).map { channelEntity(it) }.toTypedArray())
        givenAccount("1", "0")
        val probed = givenProbeAnswers((1L..4L).associateWith { if (it == 1L) listOf(serverError) else listOf(ok) })

        assertThat(scanner.run(TEST_PROVIDER_ID, null, 500L)).isTrue()

        // Only the retry of channel 1 is left; the main pass is not repeated.
        assertThat(probed).containsExactly(1L)
        assertThat(results.read(TEST_PROVIDER_ID).resume).isNull()
    }

    @Test
    fun `refusals and retry counts round trip through the stored progress marker`() = runTest {
        val resume = ChannelHealthResume(
            500L,
            9L,
            listOf(1L, 3L),
            1L,
            listOf(1L),
            listOf(ChannelRefusal(1L, 77L, inStreak = true), ChannelRefusal(2L, 78L)),
            retryOk = 4,
            retryAttempts = 6,
        )
        results.record(TEST_PROVIDER_ID, emptyList(), resume)

        assertThat(results.read(TEST_PROVIDER_ID).resume).isEqualTo(resume)
    }

    @Test
    fun `a one connection account yields to playback and resumes afterwards`() = runTest {
        givenChannels(channelEntity(1L))
        givenAccount("1", "0")
        givenProbe(ChannelHealthStatus.OK)
        val player = Any()
        AutomaticSyncActivity.started(player, TEST_PROVIDER_ID)
        try {
            val job = backgroundScope.launch { scanner.run(TEST_PROVIDER_ID, null, 0L) }

            advanceTimeBy(1_000L)
            runCurrent()
            assertThat(scanner.observeState(TEST_PROVIDER_ID).first().paused)
                .isEqualTo(ChannelHealthPauseReason.PLAYBACK_ACTIVE)
            verifyNoInteractions(probe)

            AutomaticSyncActivity.stopped(player)
            advanceTimeBy(20_000L)
            runCurrent()

            verify(probe).probe(any(), any())
            assertThat(scanner.observeState(TEST_PROVIDER_ID).first().paused).isNull()
            job.cancel()
        } finally {
            AutomaticSyncActivity.stopped(player)
        }
    }

    @Test
    fun `a scan waits while the account reports no free connection`() = runTest {
        givenChannels(channelEntity(1L))
        // First answer: another device is using the only connection. Later answers: free.
        givenAccount("1", "1", "0")
        givenProbe(ChannelHealthStatus.OK)
        val job = backgroundScope.launch { scanner.run(TEST_PROVIDER_ID, null, 0L) }

        advanceTimeBy(1_000L)
        runCurrent()
        assertThat(scanner.observeState(TEST_PROVIDER_ID).first().paused)
            .isEqualTo(ChannelHealthPauseReason.PLAYBACK_ACTIVE)
        verify(probe, never()).probe(any(), any())

        advanceTimeBy(20_000L)
        runCurrent()

        verify(probe).probe(any(), any())
        job.cancel()
    }

    @Test
    fun `a scan gives up after an hour of playback and does not count as finished`() = runTest {
        givenChannels(channelEntity(1L))
        givenAccount("1", "0")
        givenProbe(ChannelHealthStatus.OK)
        val player = Any()
        AutomaticSyncActivity.started(player, TEST_PROVIDER_ID)
        try {
            val finished = scanner.run(TEST_PROVIDER_ID, null, 0L)

            assertThat(finished).isFalse()
            assertThat(testScheduler.currentTime).isAtLeast(60L * 60_000L)
            assertThat(testScheduler.currentTime).isAtMost(65L * 60_000L)
            verifyNoInteractions(probe)
            val state = scanner.observeState(TEST_PROVIDER_ID).first()
            assertThat(state.running).isFalse()
            assertThat(state.paused).isNull()
            assertThat(state.lastFinishedAt).isNull()
            assertThat(state.gaveUp).isTrue()
        } finally {
            AutomaticSyncActivity.stopped(player)
        }
    }

    @Test
    fun `polling a saturated account backs off from 15 seconds to 5 minutes and stops after an hour`() = runTest {
        givenChannels(channelEntity(1L))
        givenProbe(ChannelHealthStatus.OK)
        val calledAt = mutableListOf<Long>()
        whenever(xtreamApi.authenticate(any(), any())).thenAnswer {
            calledAt += testScheduler.currentTime
            XtreamAuthResponse(
                userInfo = XtreamUserInfo(auth = 1, maxConnections = "1", activeConnections = "1"),
                serverInfo = XtreamServerInfo(),
            )
        }

        val finished = scanner.run(TEST_PROVIDER_ID, null, 0L)

        assertThat(finished).isFalse()
        val gaps = calledAt.zipWithNext { a, b -> b - a }
        assertThat(gaps.take(5)).containsExactly(15_000L, 30_000L, 60_000L, 120_000L, 240_000L).inOrder()
        assertThat(gaps.drop(5).distinct()).containsExactly(300_000L)
        assertThat(calledAt.size).isAtMost(25)
        verifyNoInteractions(probe)
    }

    @Test
    fun `an account with several connections keeps scanning during playback`() = runTest {
        givenChannels(channelEntity(1L))
        givenAccount("3", "0")
        givenProbe(ChannelHealthStatus.OK)
        val player = Any()
        AutomaticSyncActivity.started(player, TEST_PROVIDER_ID)
        try {
            val finished = scanner.run(TEST_PROVIDER_ID, null, 0L)

            assertThat(finished).isTrue()
            verify(probe).probe(any(), any())
        } finally {
            AutomaticSyncActivity.stopped(player)
        }
    }

    @Test
    fun `the login never appears in scan state, stored results or a failed account call`() = runTest {
        givenChannels(channelEntity(1L), channelEntity(2L))
        whenever(xtreamApi.authenticate(any(), any())).thenAnswer {
            throw IOException("failed: ${it.getArgument<String>(0)}")
        }
        whenever(probe.probe(any(), any())).doReturn(ChannelProbeResult(ChannelHealthStatus.DEAD))
        val states = mutableListOf<ChannelHealthScanState>()
        collectStates(states)

        val finished = scanner.run(TEST_PROVIDER_ID, null, 0L)

        assertThat(finished).isTrue()
        assertThat(states).isNotEmpty()
        val everything = states.joinToString("|") + storage.health.value
        assertThat(everything).doesNotContain(TEST_USERNAME)
        assertThat(everything).doesNotContain(TEST_PASSWORD)
        assertThat(everything).doesNotContain(TEST_SERVER)
    }

    @Test
    fun `a scan does not run when the account stopped offering m3u8`() = runTest {
        givenChannels(channelEntity(1L))
        whenever(xtreamApi.authenticate(any(), any())).thenReturn(
            XtreamAuthResponse(
                userInfo = XtreamUserInfo(auth = 1, allowedOutputFormats = listOf("ts")),
                serverInfo = XtreamServerInfo(),
            )
        )

        assertThat(scanner.run(TEST_PROVIDER_ID, null, 0L)).isFalse()

        verifyNoInteractions(probe)
        assertThat(scanner.observeState(TEST_PROVIDER_ID).first().lastFinishedAt).isNull()
        assertThat(scanner.observeState(TEST_PROVIDER_ID).first().gaveUp).isTrue()
    }

    @Test
    fun `a provider that is gone ends the scan without running`() = runTest {
        whenever(snapshots.getSnapshot(TEST_PROVIDER_ID)).thenReturn(null)

        assertThat(scanner.run(TEST_PROVIDER_ID, null, 0L)).isFalse()
        verifyNoInteractions(probe)
        assertThat(scanner.observeState(TEST_PROVIDER_ID).first().gaveUp).isFalse()
    }
}
