package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.EpgResolutionSummary
import com.streamvault.domain.model.GuideSourcePolicy
import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.EpgSourceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** A provider added after a preset was enabled must get the preset's sources on its first guide sync. */
class ProviderEpgSyncPresetReconcileTest {
    private val preferences: PreferencesRepository = mock()
    private val sources: EpgSourceRepository = mock()
    private val steps = mutableListOf<String>()
    private val provider = LegacyProvider(id = 7L, name = "Fixture", type = ProviderType.M3U,
        serverUrl = "https://example.test", guideSourcePolicy = GuideSourcePolicy.EXTERNAL_ONLY)

    private fun executor(reconcile: suspend () -> Unit) = ProviderEpgSyncExecutor(
        preferencesRepository = preferences, epgRepository = mock(), epgSourceRepository = sources,
        channelDao = mock(), programDao = mock(),
        transactionRunner = object : DatabaseTransactionRunner {
            override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
        },
        syncMetadataRepository = mock(), stalkerPortalStateStore = mock(), stalkerRequestCoordinator = mock(),
        createStalkerProvider = { error("Unexpected Stalker request") },
        xtreamSupport = SyncManagerXtreamSupport(mock(), { false }, { it?.message.orEmpty() },
            { _, _, _ -> }, 1L, 1L, "fixture"),
        progress = { _, _, _ -> }, sanitizeThrowableMessage = { it?.message.orEmpty() },
        reconcileGuidePresets = reconcile,
    )

    @Before
    fun setup() = runTest {
        whenever(preferences.getHiddenCategoryIds(7L, ContentType.LIVE)).thenReturn(flowOf(emptySet()))
        whenever(sources.refreshAllForProvider(7L)).thenAnswer {
            steps += "refresh"
            Result.success(Unit)
        }
        whenever(sources.resolveForProvider(eq(7L), any())).thenReturn(EpgResolutionSummary())
    }

    @Test
    fun `guideSync_reconcilesPresetsBeforeRefreshingExternalSources`() = runTest {
        executor { steps += "reconcile" }.syncM3uEpgOnly(provider, null)

        assertThat(steps).containsExactly("reconcile", "refresh").inOrder()
    }

    @Test
    fun `guideSync_stillRefreshesWhenReconcileFails`() = runTest {
        executor { error("store unavailable") }.syncM3uEpgOnly(provider, null)

        assertThat(steps).containsExactly("refresh")
    }

    @Test
    fun `guideSync_doesNotSwallowCancellation`() {
        assertThrows(CancellationException::class.java) {
            runTest { executor { throw CancellationException("stopped") }.syncM3uEpgOnly(provider, null) }
        }
        assertThat(steps).isEmpty()
    }
}
