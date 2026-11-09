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

    private suspend fun givenProbe(status: ChannelHealthStatus) {
        whenever(probe.probe(any(), any())).thenReturn(status)
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
            if (it.getArgument<String>(0).endsWith("/11.m3u8")) ChannelHealthStatus.EMPTY else ChannelHealthStatus.OK
        }

        val finished = scanner.run(TEST_PROVIDER_ID, categoryIds = null, startedAt = 0L)

        assertThat(finished).isTrue()
        val records = results.read(TEST_PROVIDER_ID).records
        assertThat(records.getValue(1L).status).isEqualTo(ChannelHealthStatus.EMPTY)
        assertThat(records.getValue(1L).failedDays).isEqualTo(1)
        assertThat(records.getValue(2L).status).isEqualTo(ChannelHealthStatus.OK)
        val state = scanner.observeState(TEST_PROVIDER_ID).first()
        assertThat(state.running).isFalse()
        assertThat(state.scanned).isEqualTo(2)
        assertThat(state.total).isEqualTo(2)
        assertThat(state.lastFinishedAt).isEqualTo(clock.nowMillis)
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

        assertThat(results.read(TEST_PROVIDER_ID).records.keys).containsExactly(2L)
        assertThat(scanner.observeState(TEST_PROVIDER_ID).first().total).isEqualTo(1)
    }

    @Test
    fun `channels already probed since the scan began are not probed again`() = runTest {
        givenChannels(channelEntity(1L), channelEntity(2L))
        givenAccount("1", "0")
        givenProbe(ChannelHealthStatus.OK)
        results.record(
            TEST_PROVIDER_ID,
            listOf(ChannelProbeOutcome(1L, ChannelHealthStatus.OK, checkedAt = clock.nowMillis)),
        )

        scanner.run(TEST_PROVIDER_ID, categoryIds = null, startedAt = clock.nowMillis - 1_000L)

        verify(probe).probe(any(), any())
        assertThat(scanner.observeState(TEST_PROVIDER_ID).first().scanned).isEqualTo(2)
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
            if (it.getArgument<String>(0).endsWith("/20.m3u8")) ChannelHealthStatus.OK else ChannelHealthStatus.EMPTY
        }

        assertThat(scanner.run(TEST_PROVIDER_ID, null, 0L)).isTrue()

        assertThat(results.read(TEST_PROVIDER_ID).records.keys).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L)
    }

    @Test
    fun `an isolated empty answer between good ones is stored`() = runTest {
        givenChannels(channelEntity(1L), channelEntity(2L), channelEntity(3L))
        givenAccount("1", "0")
        whenever(probe.probe(any(), any())).doAnswer {
            if (it.getArgument<String>(0).endsWith("/12.m3u8")) ChannelHealthStatus.EMPTY else ChannelHealthStatus.OK
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
        assertThat(results.read(TEST_PROVIDER_ID).records).isEmpty()
    }

    @Test
    fun `an unclear answer does not stop a good one from clearing the backoff count`() = runTest {
        givenChannels(*(1L..8L).map { channelEntity(it) }.toTypedArray())
        givenAccount("1", "0")
        var calls = 0
        whenever(probe.probe(any(), any())).doAnswer {
            // Two unclear, one good, repeating: never three unclear in a row.
            if (++calls % 3 == 0) ChannelHealthStatus.OK else ChannelHealthStatus.UNCLEAR
        }

        val finished = scanner.run(TEST_PROVIDER_ID, null, 0L)

        assertThat(finished).isTrue()
        assertThat(scanner.observeState(TEST_PROVIDER_ID).first().lastFinishedAt).isNotNull()
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
        whenever(probe.probe(any(), any())).doReturn(ChannelHealthStatus.DEAD)
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
    }

    @Test
    fun `a provider that is gone ends the scan without running`() = runTest {
        whenever(snapshots.getSnapshot(TEST_PROVIDER_ID)).thenReturn(null)

        assertThat(scanner.run(TEST_PROVIDER_ID, null, 0L)).isFalse()
        verifyNoInteractions(probe)
    }
}
