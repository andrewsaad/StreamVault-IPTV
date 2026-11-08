package com.streamvault.feature.settings.presentation

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.EpgPresetCatalog
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.EpgPresetErrors
import com.streamvault.domain.repository.EpgPresetManager
import com.streamvault.domain.repository.EpgPresetSourceStatus
import com.streamvault.domain.repository.EpgPresetStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.mock

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsEpgPresetViewModelTest {

    private class FakeEpgPresetManager : EpgPresetManager {
        val statuses = MutableStateFlow(
            EpgPresetCatalog.all.map { EpgPresetStatus(it.id, false, null, null, false) }
        )
        val setEnabledCalls = mutableListOf<Pair<String, Boolean>>()
        val refreshCalls = mutableListOf<String>()
        val addressCalls = mutableListOf<Triple<String, String, String>>()
        var nextAddressResult: Result<Unit> = Result.Success(Unit)
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

        override suspend fun updateAddress(presetId: String, sourceKey: String, url: String): Result<Unit> {
            addressCalls += Triple(presetId, sourceKey, url)
            return nextAddressResult
        }

        override suspend fun reconcile() {
            reconcileCalls++
        }
    }

    private val beinUrls = EpgPresetCatalog.byId(EpgPresetCatalog.BEIN)!!.sources.map { it.url }
    private val beinKeys = EpgPresetCatalog.byId(EpgPresetCatalog.BEIN)!!.sources.map { it.key }

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
            mapEpgPresetUiModels(listOf(status)).single { it.presetId == status.presetId }.statusLine

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
    fun `each preset file takes its row and address from the manager`() {
        val models = mapEpgPresetUiModels(
            statuses = listOf(
                EpgPresetStatus(
                    EpgPresetCatalog.BEIN, true, null, null, false,
                    sources = listOf(EpgPresetSourceStatus(beinKeys[0], 7L, "https://mirror.example/qatar.xml")),
                )
            ),
        )

        val bein = models.single { it.presetId == EpgPresetCatalog.BEIN }
        assertThat(bein.sources.map { it.sourceId }).containsExactly(7L, null, null).inOrder()
        assertThat(bein.sources.map { it.url })
            .containsExactly("https://mirror.example/qatar.xml", beinUrls[1], beinUrls[2]).inOrder()
    }

    // ── Actions ─────────────────────────────────────────────────────

    @Test
    fun `observing presets publishes manager state into the ui state and reconciles once`() = runTest {
        val manager = FakeEpgPresetManager()
        val uiState = MutableStateFlow(SettingsUiState())
        val actions = SettingsEpgActions(mock(), manager, uiState)

        val job = actions.observeEpgPresets(this)
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
    fun `every manager error key has a localized message`() {
        val keys = EpgPresetErrors::class.java.declaredFields
            .filter { it.type == String::class.java }
            .map { it.get(null) as String }

        assertThat(keys).isNotEmpty()
        assertThat(keys.filter { epgPresetErrorRes(it) == null }).isEmpty()
        assertThat(keys.mapNotNull(::epgPresetErrorRes).distinct()).hasSize(keys.size)
        assertThat(epgPresetErrorRes("HTTP 503")).isNull()
    }

    @Test
    fun `a refusal is shown through the error text resolver`() = runTest {
        val manager = FakeEpgPresetManager().apply { nextResult = Result.Error(EpgPresetErrors.REFRESH_TOO_SOON) }
        val uiState = MutableStateFlow(SettingsUiState())
        val actions = SettingsEpgActions(mock(), manager, uiState, presetErrorText = { "localized:$it" })

        actions.refreshEpgPresetNow(this, EpgPresetCatalog.US)
        advanceUntilIdle()

        assertThat(uiState.value.userMessage).isEqualTo("localized:${EpgPresetErrors.REFRESH_TOO_SOON}")
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
    fun `changing an address goes through the manager and then tries the new address`() = runTest {
        val manager = FakeEpgPresetManager()
        val uiState = MutableStateFlow(SettingsUiState())
        val actions = SettingsEpgActions(mock(), manager, uiState)
        var succeeded = false

        actions.updateEpgPresetAddress(
            scope = this,
            presetId = EpgPresetCatalog.BEIN,
            sourceKey = "BEIN1",
            url = "  https://mirror.example/bein.xml ",
            onSuccess = { succeeded = true },
        )
        advanceUntilIdle()

        assertThat(manager.addressCalls)
            .containsExactly(Triple(EpgPresetCatalog.BEIN, "BEIN1", "  https://mirror.example/bein.xml "))
        assertThat(manager.refreshCalls).containsExactly(EpgPresetCatalog.BEIN)
        assertThat(succeeded).isTrue()
    }

    @Test
    fun `a blank address is rejected without asking the manager`() = runTest {
        val manager = FakeEpgPresetManager()
        val actions = SettingsEpgActions(mock(), manager, MutableStateFlow(SettingsUiState()))
        var failed = false

        actions.updateEpgPresetAddress(this, EpgPresetCatalog.BEIN, "BEIN1", "   ", onError = { failed = true })
        advanceUntilIdle()

        assertThat(manager.addressCalls).isEmpty()
        assertThat(failed).isTrue()
    }

    @Test
    fun `a refused address change reports the error and tries nothing`() = runTest {
        val manager = FakeEpgPresetManager().apply { nextAddressResult = Result.Error("Address not allowed") }
        val uiState = MutableStateFlow(SettingsUiState())
        val actions = SettingsEpgActions(mock(), manager, uiState)
        var failed = false

        actions.updateEpgPresetAddress(this, EpgPresetCatalog.BEIN, "BEIN1", "http://x", onError = { failed = true })
        advanceUntilIdle()

        assertThat(uiState.value.userMessage).isEqualTo("Address not allowed")
        assertThat(manager.refreshCalls).isEmpty()
        assertThat(failed).isTrue()
    }
}
