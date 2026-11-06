package com.streamvault.feature.settings.presentation

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.EpgPresetCatalog
import com.streamvault.domain.model.EpgSource
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.EpgPresetManager
import com.streamvault.domain.repository.EpgPresetStatus
import com.streamvault.domain.repository.EpgSourceRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsEpgPresetViewModelTest {

    private class FakeEpgPresetManager : EpgPresetManager {
        val statuses = MutableStateFlow(
            EpgPresetCatalog.all.map { EpgPresetStatus(it.id, false, null, null, false) }
        )
        val setEnabledCalls = mutableListOf<Pair<String, Boolean>>()
        val refreshCalls = mutableListOf<String>()
        var reconcileCalls = 0
        var nextResult: Result<Unit> = Result.Success(Unit)

        override fun observe(): Flow<List<EpgPresetStatus>> = statuses

        override suspend fun setEnabled(presetId: String, enabled: Boolean): Result<Unit> {
            setEnabledCalls += presetId to enabled
            return nextResult
        }

        override suspend fun refreshNow(presetId: String): Result<Unit> {
            refreshCalls += presetId
            return nextResult
        }

        override suspend fun reconcile() {
            reconcileCalls++
        }
    }

    private val beinUrls = EpgPresetCatalog.byId(EpgPresetCatalog.BEIN)!!.sources.map { it.url }

    // ── Mapping ─────────────────────────────────────────────────────

    @Test
    fun `default state lists every catalog preset switched off`() {
        val state = SettingsUiState()

        assertThat(state.epgPresets.map { it.presetId })
            .containsExactlyElementsIn(EpgPresetCatalog.all.map { it.id })
            .inOrder()
        assertThat(state.epgPresets.none { it.enabled }).isTrue()
        assertThat(state.epgPresets.map { it.statusLine }.distinct())
            .containsExactly(EpgPresetStatusLine.Off)
    }

    @Test
    fun `presets missing from the manager output stay off`() {
        val models = mapEpgPresetUiModels(
            statuses = listOf(EpgPresetStatus(EpgPresetCatalog.US, true, 1_000L, null, false)),
            epgSources = emptyList(),
        )

        assertThat(models.map { it.presetId })
            .containsExactlyElementsIn(EpgPresetCatalog.all.map { it.id })
            .inOrder()
        assertThat(models.single { it.presetId == EpgPresetCatalog.BEIN }.enabled).isFalse()
        assertThat(models.single { it.presetId == EpgPresetCatalog.EGYPT }.enabled).isFalse()
        assertThat(models.single { it.presetId == EpgPresetCatalog.US }.enabled).isTrue()
    }

    @Test
    fun `status line reflects off, refreshing, error and last update`() {
        fun line(status: EpgPresetStatus) =
            mapEpgPresetUiModels(listOf(status), emptyList()).single { it.presetId == status.presetId }.statusLine

        val id = EpgPresetCatalog.BEIN
        assertThat(line(EpgPresetStatus(id, false, 5L, "boom", true))).isEqualTo(EpgPresetStatusLine.Off)
        assertThat(line(EpgPresetStatus(id, true, 5L, "boom", true))).isEqualTo(EpgPresetStatusLine.Refreshing)
        assertThat(line(EpgPresetStatus(id, true, 5L, "HTTP 404", false)))
            .isEqualTo(EpgPresetStatusLine.Failed("HTTP 404"))
        assertThat(line(EpgPresetStatus(id, true, 5L, null, false))).isEqualTo(EpgPresetStatusLine.Updated(5L))
        assertThat(line(EpgPresetStatus(id, true, null, null, false))).isEqualTo(EpgPresetStatusLine.Pending)
        assertThat(line(EpgPresetStatus(id, true, null, "  ", false))).isEqualTo(EpgPresetStatusLine.Pending)
    }

    @Test
    fun `preset sources are linked to existing guide sources by address`() {
        val models = mapEpgPresetUiModels(
            statuses = listOf(EpgPresetStatus(EpgPresetCatalog.BEIN, true, null, null, false)),
            epgSources = listOf(
                EpgSource(id = 7L, name = "beIN", url = beinUrls[0]),
                EpgSource(id = 9L, name = "other", url = "https://example.com/guide.xml"),
            ),
        )

        val bein = models.single { it.presetId == EpgPresetCatalog.BEIN }
        assertThat(bein.sources.map { it.sourceId }).containsExactly(7L, null).inOrder()
        assertThat(bein.sources.map { it.url }).containsExactly(beinUrls[0], beinUrls[1]).inOrder()
    }

    @Test
    fun `an edited address stays linked through the remembered source id`() {
        val models = mapEpgPresetUiModels(
            statuses = listOf(EpgPresetStatus(EpgPresetCatalog.BEIN, true, null, null, false)),
            epgSources = listOf(EpgSource(id = 7L, name = "beIN", url = "https://mirror.example/bein.xml")),
            knownSourceIds = mapOf(epgPresetSourceKey(EpgPresetCatalog.BEIN, "BEIN1") to 7L),
        )

        val first = models.single { it.presetId == EpgPresetCatalog.BEIN }.sources.first()
        assertThat(first.sourceId).isEqualTo(7L)
        assertThat(first.url).isEqualTo("https://mirror.example/bein.xml")
    }

    // ── Actions ─────────────────────────────────────────────────────

    @Test
    fun `observing presets publishes manager state into the ui state and reconciles once`() = runTest {
        val manager = FakeEpgPresetManager()
        val uiState = MutableStateFlow(SettingsUiState())
        val actions = SettingsEpgActions(mock(), manager, uiState)

        val job = actions.observeEpgPresets(backgroundScope)
        advanceUntilIdle()
        manager.statuses.value = manager.statuses.value.map {
            if (it.presetId == EpgPresetCatalog.EGYPT) it.copy(enabled = true, refreshing = true) else it
        }
        advanceUntilIdle()

        val egypt = uiState.value.epgPresets.single { it.presetId == EpgPresetCatalog.EGYPT }
        assertThat(egypt.enabled).isTrue()
        assertThat(egypt.statusLine).isEqualTo(EpgPresetStatusLine.Refreshing)
        assertThat(manager.reconcileCalls).isEqualTo(1)
        job.cancel()
    }

    @Test
    fun `toggle forwards to the manager and surfaces a refusal`() = runTest {
        val manager = FakeEpgPresetManager().apply { nextResult = Result.Error("offline") }
        val uiState = MutableStateFlow(SettingsUiState())
        val actions = SettingsEpgActions(mock(), manager, uiState)

        actions.toggleEpgPreset(this, EpgPresetCatalog.BEIN, true)
        advanceUntilIdle()

        assertThat(manager.setEnabledCalls).containsExactly(EpgPresetCatalog.BEIN to true)
        assertThat(uiState.value.userMessage).isEqualTo("offline")
    }

    @Test
    fun `refresh now forwards to the manager and stays quiet on success`() = runTest {
        val manager = FakeEpgPresetManager()
        val uiState = MutableStateFlow(SettingsUiState())
        val actions = SettingsEpgActions(mock(), manager, uiState)

        actions.refreshEpgPresetNow(this, EpgPresetCatalog.US)
        advanceUntilIdle()

        assertThat(manager.refreshCalls).containsExactly(EpgPresetCatalog.US)
        assertThat(uiState.value.userMessage).isNull()
    }

    @Test
    fun `changing an address updates the existing guide source and keeps its id`() = runTest {
        val repository = mock<EpgSourceRepository>()
        val source = EpgSource(id = 7L, name = "beIN", url = beinUrls[0])
        whenever(repository.getSourceById(7L)).thenReturn(source)
        whenever(repository.updateSource(any())).thenReturn(Result.Success(Unit))
        val uiState = MutableStateFlow(SettingsUiState())
        val actions = SettingsEpgActions(repository, FakeEpgPresetManager(), uiState)
        var succeeded = false

        actions.updateEpgPresetAddress(
            scope = this,
            presetId = EpgPresetCatalog.BEIN,
            sourceKey = "BEIN1",
            sourceId = 7L,
            url = "  https://mirror.example/bein.xml ",
            onSuccess = { succeeded = true },
        )
        advanceUntilIdle()

        val captor = argumentCaptor<EpgSource>()
        verify(repository).updateSource(captor.capture())
        assertThat(captor.firstValue.id).isEqualTo(7L)
        assertThat(captor.firstValue.url).isEqualTo("https://mirror.example/bein.xml")
        assertThat(succeeded).isTrue()
    }

    @Test
    fun `a blank address is rejected without touching the source`() = runTest {
        val repository = mock<EpgSourceRepository>()
        val uiState = MutableStateFlow(SettingsUiState())
        val actions = SettingsEpgActions(repository, FakeEpgPresetManager(), uiState)
        var failed = false

        actions.updateEpgPresetAddress(this, EpgPresetCatalog.BEIN, "BEIN1", 7L, "   ", onError = { failed = true })
        advanceUntilIdle()

        verify(repository, never()).updateSource(any())
        assertThat(failed).isTrue()
    }

    @Test
    fun `a failed address update reports the repository error`() = runTest {
        val repository = mock<EpgSourceRepository>()
        whenever(repository.getSourceById(7L)).thenReturn(EpgSource(id = 7L, name = "beIN", url = beinUrls[0]))
        whenever(repository.updateSource(any())).thenReturn(Result.Error("Address not allowed"))
        val uiState = MutableStateFlow(SettingsUiState())
        val actions = SettingsEpgActions(repository, FakeEpgPresetManager(), uiState)
        var failed = false

        actions.updateEpgPresetAddress(this, EpgPresetCatalog.BEIN, "BEIN1", 7L, "http://x", onError = { failed = true })
        advanceUntilIdle()

        assertThat(uiState.value.userMessage).isEqualTo("Address not allowed")
        assertThat(failed).isTrue()
    }
}
