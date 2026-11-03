package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.ProgramDao
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.repository.EpgEndpointMissingException
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.EpgResolutionSummary
import com.streamvault.domain.model.GuideSourcePolicy
import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.ProviderEpgSourceAssignment
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.SyncMetadata
import com.streamvault.domain.repository.EpgRepository
import com.streamvault.domain.repository.EpgSourceRepository
import com.streamvault.domain.repository.SyncMetadataRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Bug #66: a provider without xmltv.php must not raise the guide banner on every sync. */
class ProviderXmltvUnavailableTest {
    private val preferences: PreferencesRepository = mock()
    private val epgRepository: EpgRepository = mock()
    private val sources: EpgSourceRepository = mock()
    private val programs: ProgramDao = mock()
    private val metadata: SyncMetadataRepository = mock()
    private val remembered = mutableMapOf<Long, Pair<Long, String>>()
    private val transaction = object : DatabaseTransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }
    private val executor = ProviderEpgSyncExecutor(preferencesRepository = preferences,
        epgRepository = epgRepository, epgSourceRepository = sources, channelDao = mock(),
        programDao = programs, transactionRunner = transaction, syncMetadataRepository = metadata,
        stalkerPortalStateStore = mock(), stalkerRequestCoordinator = mock(),
        createStalkerProvider = { error("Unexpected Stalker request") },
        xtreamSupport = SyncManagerXtreamSupport(mock(), { false }, { it?.message.orEmpty() },
            { _, _, _ -> }, 1L, 1L, "fixture"),
        progress = { _, _, _ -> }, sanitizeThrowableMessage = { it?.message.orEmpty() })
    private val provider = LegacyProvider(id = 7L, name = "Fixture", type = ProviderType.XTREAM_CODES,
        serverUrl = "https://example.test", username = "fixture", password = "fixture",
        guideSourcePolicy = GuideSourcePolicy.AUTO)

    @Before
    fun setup() = runTest {
        whenever(preferences.getHiddenCategoryIds(7L, ContentType.LIVE)).thenReturn(flowOf(emptySet()))
        whenever(preferences.getProviderXmltvUnavailableSince(any(), any())).thenAnswer { invocation ->
            remembered[invocation.getArgument<Long>(0)]
                ?.takeIf { it.second == invocation.getArgument<String>(1) }
                ?.first
        }
        whenever(preferences.markProviderXmltvUnavailable(any(), any(), any())).thenAnswer { invocation ->
            remembered[invocation.getArgument(0)] = invocation.getArgument<Long>(2) to invocation.getArgument<String>(1)
            Unit
        }
        whenever(preferences.clearProviderXmltvUnavailable(any())).thenAnswer { invocation ->
            remembered.remove(invocation.getArgument<Long>(0))
            Unit
        }
        whenever(sources.refreshAllForProvider(7L)).thenReturn(Result.success(Unit))
        whenever(sources.resolveForProvider(eq(7L), any())).thenReturn(EpgResolutionSummary())
        whenever(sources.getAssignmentsForProvider(7L)).thenReturn(
            flowOf(listOf(ProviderEpgSourceAssignment(providerId = 7L, epgSourceId = 3L)))
        )
        whenever(programs.countByProvider(7L)).thenReturn(5)
        stubXmltv(missing(404))
    }

    @Test
    fun `missing provider XMLTV is remembered without a warning when external guides exist`() = runTest {
        val outcome = sync(provider, NOW)

        assertThat(outcome.warnings).isEmpty()
        assertThat(outcome.hasRetryableFailure).isFalse()
        assertThat(remembered[7L]?.first).isEqualTo(NOW)
        verify(sources).refreshAllForProvider(7L)
        verify(metadata, never()).updateMetadata(any())
    }

    @Test
    fun `HTTP 410 is treated as a missing endpoint too`() = runTest {
        stubXmltv(missing(410))

        assertThat(sync(provider, NOW).warnings).isEmpty()
        assertThat(remembered[7L]?.first).isEqualTo(NOW)
    }

    @Test
    fun `remembered missing XMLTV skips the download for seven days then re-probes`() = runTest {
        sync(provider, NOW)

        val skipped = sync(provider, NOW + SEVEN_DAYS - 1L)
        assertThat(skipped.warnings).isEmpty()
        verify(epgRepository, times(1)).refreshEpg(eq(7L), any())
        verify(sources, times(2)).refreshAllForProvider(7L)

        stubXmltv(Result.success(Unit))
        val reprobed = sync(provider, NOW + SEVEN_DAYS)
        assertThat(reprobed.warnings).isEmpty()
        verify(epgRepository, times(2)).refreshEpg(eq(7L), any())
        assertThat(remembered).doesNotContainKey(7L)
    }

    @Test
    fun `re-probe that is still missing renews the memory quietly`() = runTest {
        sync(provider, NOW)

        val reprobed = sync(provider, NOW + SEVEN_DAYS + 1L)

        assertThat(reprobed.warnings).isEmpty()
        verify(epgRepository, times(2)).refreshEpg(eq(7L), any())
        assertThat(remembered[7L]?.first).isEqualTo(NOW + SEVEN_DAYS + 1L)
    }

    @Test
    fun `missing provider XMLTV without any external source gives one gentle warning`() = runTest {
        whenever(sources.getAssignmentsForProvider(7L)).thenReturn(
            flowOf(listOf(ProviderEpgSourceAssignment(providerId = 7L, epgSourceId = 3L, enabled = false)))
        )

        val outcome = sync(provider, NOW)

        assertThat(outcome.warnings).containsExactly(
            "This provider does not offer a TV guide. Add an external EPG source to see programs."
        )
        assertThat(outcome.hasRetryableFailure).isFalse()
        assertThat(sync(provider, NOW + 1L).warnings).isEmpty()
    }

    @Test
    fun `timeouts and server errors keep the retryable warning and are not remembered`() = runTest {
        val failures = listOf(
            Result.error("Failed to download EPG: HTTP 500", java.io.IOException("Failed to download EPG: HTTP 500")),
            Result.error("timeout", java.net.SocketTimeoutException("timeout"))
        )
        for (failure in failures) {
            stubXmltv(failure)

            val outcome = sync(provider, NOW)

            assertThat(outcome.warnings).contains("EPG XMLTV sync failed.")
            assertThat(outcome.hasRetryableFailure).isTrue()
        }
        verify(preferences, never()).markProviderXmltvUnavailable(any(), any(), any())
    }

    @Test
    fun `user-entered EPG URL that 404s still warns and is never remembered or skipped`() = runTest {
        val custom = provider.copy(epgUrl = "https://guide.example.test/epg.xml")

        assertThat(sync(custom, NOW).warnings).contains("EPG XMLTV sync failed.")
        assertThat(sync(custom, NOW + 1L).warnings).contains("EPG XMLTV sync failed.")

        verify(epgRepository, times(2)).refreshEpg(eq(7L), any())
        verify(preferences, never()).markProviderXmltvUnavailable(any(), any(), any())
    }

    @Test
    fun `provider-only policy still probes and warns while remembered`() = runTest {
        sync(provider, NOW)
        val providerOnly = provider.copy(guideSourcePolicy = GuideSourcePolicy.PROVIDER_ONLY)

        val outcome = sync(providerOnly, NOW + 1L)

        assertThat(outcome.warnings).contains("EPG XMLTV sync failed.")
        verify(epgRepository, times(2)).refreshEpg(eq(7L), any())
    }

    @Test
    fun `changing the provider server probes again immediately`() = runTest {
        sync(provider, NOW)

        sync(provider.copy(serverUrl = "https://other.example.test"), NOW + 1L)

        verify(epgRepository, times(2)).refreshEpg(eq(7L), any())
    }

    private suspend fun sync(target: LegacyProvider, now: Long) =
        executor.syncXtreamProviderEpg(target, SyncMetadata(7L), now, force = false, onProgress = null)

    private suspend fun stubXmltv(result: Result<Unit>) {
        whenever(epgRepository.refreshEpg(eq(7L), any())).thenReturn(result)
    }

    private fun missing(code: Int): Result<Unit> =
        Result.error("Failed to download EPG: HTTP $code", EpgEndpointMissingException(code))

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val SEVEN_DAYS = 7L * 24L * 60L * 60L * 1000L
    }
}
