package com.streamvault.data.health

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.remote.dto.XtreamAuthResponse
import com.streamvault.data.remote.dto.XtreamServerInfo
import com.streamvault.data.remote.dto.XtreamUserInfo
import com.streamvault.data.remote.xtream.XtreamApiService
import com.streamvault.domain.model.ChannelHealthStatus
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.ProviderSnapshotRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class ChannelHealthRepositoryImplTest {
    private val clock = TestClock(nowMillis = 12L * 3_600_000L)
    private val storage = InMemoryHealthPreferences()
    private val snapshots: ProviderSnapshotRepository = mock()
    private val channelDao: ChannelDao = mock()
    private val scheduler: ChannelHealthScanScheduler = mock()
    private val xtreamApi: XtreamApiService = mock()
    private val results = ChannelHealthResults(storage.preferences, clock)
    private val scanner = ChannelHealthScanner(
        snapshots = snapshots,
        channelDao = channelDao,
        xtreamApi = xtreamApi,
        probe = mock<ChannelHealthProbe>(),
        results = results,
    )
    private val repository = ChannelHealthRepositoryImpl(
        snapshots = snapshots,
        channelDao = channelDao,
        preferences = storage.preferences,
        results = results,
        scanner = scanner,
        scheduler = scheduler,
    )

    private suspend fun stubChannels(vararg channels: com.streamvault.data.local.entity.ChannelEntity) {
        whenever(channelDao.getByProviderSync(any())).thenReturn(channels.toList())
    }

    private suspend fun givenOutputFormats(vararg formats: String) {
        whenever(xtreamApi.authenticate(any(), any())).thenReturn(
            XtreamAuthResponse(
                userInfo = XtreamUserInfo(auth = 1, allowedOutputFormats = formats.toList()),
                serverInfo = XtreamServerInfo(),
            )
        )
    }

    private suspend fun failOnce(channelId: Long, status: ChannelHealthStatus = ChannelHealthStatus.EMPTY) {
        results.record(TEST_PROVIDER_ID, listOf(ChannelProbeOutcome(channelId, status, clock.nowMillis)))
    }

    @Test
    fun `a channel that failed on two separate days becomes a suggestion`() = runTest {
        stubChannels(channelEntity(1L), channelEntity(2L))

        failOnce(1L)
        failOnce(1L)
        assertThat(repository.observeSuggestions(TEST_PROVIDER_ID).first()).isEmpty()

        clock.nowMillis += DAY_MS
        failOnce(1L)
        val suggestions = repository.observeSuggestions(TEST_PROVIDER_ID).first()

        assertThat(suggestions.map { it.channel.id }).containsExactly(1L)
        assertThat(suggestions.single().record.failedDays).isEqualTo(2)
    }

    @Test
    fun `an OK probe on day three removes the suggestion`() = runTest {
        stubChannels(channelEntity(1L))
        failOnce(1L)
        clock.nowMillis += DAY_MS
        failOnce(1L)
        assertThat(repository.observeSuggestions(TEST_PROVIDER_ID).first()).hasSize(1)

        clock.nowMillis += DAY_MS
        failOnce(1L, ChannelHealthStatus.OK)

        assertThat(repository.observeSuggestions(TEST_PROVIDER_ID).first()).isEmpty()
    }

    @Test
    fun `an UNCLEAR probe keeps the suggestion`() = runTest {
        stubChannels(channelEntity(1L))
        failOnce(1L)
        clock.nowMillis += DAY_MS
        failOnce(1L)

        clock.nowMillis += DAY_MS
        failOnce(1L, ChannelHealthStatus.UNCLEAR)

        assertThat(repository.observeSuggestions(TEST_PROVIDER_ID).first()).hasSize(1)
    }

    @Test
    fun `suggestions leave out channels that are already hidden`() = runTest {
        stubChannels(channelEntity(1L), channelEntity(2L))
        listOf(1L, 2L).forEach { failOnce(it, ChannelHealthStatus.DEAD) }
        clock.nowMillis += DAY_MS
        listOf(1L, 2L).forEach { failOnce(it, ChannelHealthStatus.DEAD) }
        storage.hidden.value = setOf(1L)

        val suggestions = repository.observeSuggestions(TEST_PROVIDER_ID).first()

        assertThat(suggestions.map { it.channel.id }).containsExactly(2L)
    }

    @Test
    fun `suggestions are ordered by category name then channel name`() = runTest {
        stubChannels(
            channelEntity(1L, name = "Zeta", category = "Sports"),
            channelEntity(2L, name = "beta", category = "News"),
            channelEntity(3L, name = "Alpha", category = "News"),
            channelEntity(4L, name = "Gamma", category = "Movies"),
        )
        listOf(1L, 2L, 3L, 4L).forEach { failOnce(it) }
        clock.nowMillis += DAY_MS
        listOf(1L, 2L, 3L, 4L).forEach { failOnce(it) }

        val suggestions = repository.observeSuggestions(TEST_PROVIDER_ID).first()

        assertThat(suggestions.map { it.channel.name }).containsExactly("Gamma", "Alpha", "beta", "Zeta").inOrder()
    }

    @Test
    fun `hideChannels adds to the channels that were already hidden`() = runTest {
        storage.hidden.value = setOf(5L)

        repository.hideChannels(TEST_PROVIDER_ID, setOf(7L, 8L))

        verify(storage.preferences).setHiddenChannelIds(TEST_PROVIDER_ID, setOf(5L, 7L, 8L))
        assertThat(storage.hidden.value).containsExactly(5L, 7L, 8L)
    }

    @Test
    fun `clearResults forgets probe results but not hidden channels`() = runTest {
        stubChannels(channelEntity(1L))
        storage.hidden.value = setOf(9L)
        failOnce(1L)
        clock.nowMillis += DAY_MS
        failOnce(1L)
        assertThat(repository.observeSuggestions(TEST_PROVIDER_ID).first()).hasSize(1)

        repository.clearResults(TEST_PROVIDER_ID)

        assertThat(repository.observeSuggestions(TEST_PROVIDER_ID).first()).isEmpty()
        assertThat(storage.health.value).isNull()
        assertThat(storage.hidden.value).containsExactly(9L)
    }

    @Test
    fun `a provider that is not Xtream cannot be scanned`() = runTest {
        whenever(snapshots.getSnapshot(TEST_PROVIDER_ID)).thenReturn(m3uSnapshot())

        val result = repository.startScan(TEST_PROVIDER_ID)

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).message).isNotEmpty()
        verifyNoInteractions(scheduler)
        assertThat(repository.observeScanState(TEST_PROVIDER_ID).first().running).isFalse()
    }

    @Test
    fun `an unknown provider cannot be scanned`() = runTest {
        whenever(snapshots.getSnapshot(TEST_PROVIDER_ID)).thenReturn(null)

        assertThat(repository.startScan(TEST_PROVIDER_ID)).isInstanceOf(Result.Error::class.java)
        verify(scheduler, never()).enqueue(any(), anyOrNull(), any())
    }

    @Test
    fun `an account that lists its formats without m3u8 cannot be scanned`() = runTest {
        whenever(snapshots.getSnapshot(TEST_PROVIDER_ID)).thenReturn(xtreamSnapshot())
        givenOutputFormats("ts", "rtmp")

        val result = repository.startScan(TEST_PROVIDER_ID)

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).message).doesNotContain(TEST_PASSWORD)
        verify(scheduler, never()).enqueue(any(), anyOrNull(), any())
        assertThat(repository.observeScanState(TEST_PROVIDER_ID).first().scanned).isEqualTo(0)
    }

    @Test
    fun `an account that lists no formats is still scanned`() = runTest {
        whenever(snapshots.getSnapshot(TEST_PROVIDER_ID)).thenReturn(xtreamSnapshot())
        givenOutputFormats()

        assertThat(repository.startScan(TEST_PROVIDER_ID)).isEqualTo(Result.success(Unit))
        verify(scheduler).enqueue(any(), anyOrNull(), any())
    }

    @Test
    fun `an Xtream scan is queued with its categories and shows as running`() = runTest {
        whenever(snapshots.getSnapshot(TEST_PROVIDER_ID)).thenReturn(xtreamSnapshot())
        givenOutputFormats("ts", "m3u8")

        val result = repository.startScan(TEST_PROVIDER_ID, setOf(100L, 200L))

        assertThat(result).isEqualTo(Result.success(Unit))
        verify(scheduler).enqueue(eq(TEST_PROVIDER_ID), eq(setOf(100L, 200L)), eq(clock.nowMillis))
        assertThat(repository.observeScanState(TEST_PROVIDER_ID).first().running).isTrue()
    }

    @Test
    fun `cancelScan cancels the queued work and clears the running flag`() = runTest {
        whenever(snapshots.getSnapshot(TEST_PROVIDER_ID)).thenReturn(xtreamSnapshot())
        givenOutputFormats("m3u8")
        repository.startScan(TEST_PROVIDER_ID)

        repository.cancelScan(TEST_PROVIDER_ID)

        verify(scheduler).cancel(TEST_PROVIDER_ID)
        assertThat(repository.observeScanState(TEST_PROVIDER_ID).first().running).isFalse()
    }

    @Test
    fun `the scan state reports when the last scan finished`() = runTest {
        assertThat(repository.observeScanState(TEST_PROVIDER_ID).first().lastFinishedAt).isNull()

        results.markFinished(TEST_PROVIDER_ID)

        assertThat(repository.observeScanState(TEST_PROVIDER_ID).first().lastFinishedAt).isEqualTo(clock.nowMillis)
    }
}
