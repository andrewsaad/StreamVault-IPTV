package com.streamvault.feature.settings.presentation

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.EpgPresetCatalog
import com.streamvault.domain.model.EpgSource
import com.streamvault.domain.model.ProviderEpgSourceAssignment
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.EpgPresetErrors
import com.streamvault.domain.repository.EpgPresetManager
import com.streamvault.domain.repository.EpgPresetSourceStatus
import com.streamvault.domain.repository.EpgPresetStatus
import com.streamvault.domain.repository.EpgRefreshOutcome
import com.streamvault.domain.repository.EpgSourceRepository
import com.streamvault.domain.repository.EpgSourceErrors
import com.streamvault.feature.settings.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

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
        var reconcileFailure: Exception? = null

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
            reconcileFailure?.let { throw it }
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
        assertThat(bein.sources.map { it.sourceId }).containsExactly(7L, null).inOrder()
        assertThat(bein.sources.map { it.url })
            .containsExactly("https://mirror.example/qatar.xml", beinUrls[1]).inOrder()
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
    fun `every stored refresh error key has a localized message, even among several`() {
        // Review R3: an empty guide file used to be stored and shown as English text.
        val keys = EpgSourceErrors::class.java.declaredFields
            .filter { it.type == String::class.java }
            .map { it.get(null) as String }
        val text = { res: Int -> "res:$res" }

        assertThat(keys).isNotEmpty()
        assertThat(keys.filter { epgPresetErrorRes(it) == null }).isEmpty()
        val empty = EpgSourceErrors.EMPTY_PAYLOAD
        val http = { code: Int -> "http:$code" }
        assertThat(localizeEpgError(empty, text, http))
            .isEqualTo("res:${com.streamvault.feature.settings.R.string.epg_source_error_empty_payload}")
        assertThat(localizeEpgError("HTTP 404; $empty", text, http))
            .isEqualTo("http:404; res:${com.streamvault.feature.settings.R.string.epg_source_error_empty_payload}")
    }

    @Test
    fun `an unrecognised guide error is replaced by the generic localized message, never shown raw`() {
        val text = { res: Int -> "res:$res" }
        val http = { code: Int -> "http:$code" }
        val generic = "res:${com.streamvault.feature.settings.R.string.epg_error_generic}"
        val raw = "Failed to download EPG: http://host.example/xmltv.php?username=alice&password=s3cret"

        assertThat(localizeEpgError("Connection reset", text, http)).isEqualTo(generic)
        assertThat(localizeEpgError(raw, text, http)).isEqualTo(generic)
        assertThat(localizeEpgError("HTTP 404; $raw", text, http)).isEqualTo("http:404; $generic")
        assertThat(localizeEpgError("", text, http)).isEqualTo(generic)
    }

    @Test
    fun `a download error with an http status is localized with its code`() {
        val text = { res: Int -> "res:$res" }
        val http = { code: Int -> "http:$code" }

        assertThat(localizeEpgError("HTTP 404", text, http)).isEqualTo("http:404")
        assertThat(localizeEpgError("Failed to download EPG: HTTP 503", text, http)).isEqualTo("http:503")
        assertThat(epgHttpStatus("HTTP 404")).isEqualTo(404)
        assertThat(epgHttpStatus("HTTP 4040")).isNull()
        assertThat(epgHttpStatus("Failed to refresh EPG source: HTTP 404 while reading")).isNull()
    }

    @Test
    fun `a refusal is shown through the error text resolver`() = runTest {
        val manager = FakeEpgPresetManager().apply { nextResult = Result.Error(EpgPresetErrors.REFRESH_TOO_SOON) }
        val uiState = MutableStateFlow(SettingsUiState())
        val actions = SettingsEpgActions(mock(), manager, uiState, epgErrorText = { "localized:$it" })

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
    fun `changing an address goes through the manager, which imports it, with no whole-preset refresh`() = runTest {
        val manager = FakeEpgPresetManager()
        val uiState = MutableStateFlow(SettingsUiState())
        val actions = SettingsEpgActions(mock(), manager, uiState)
        var succeeded = false

        actions.updateEpgPresetAddress(
            scope = this,
            presetId = EpgPresetCatalog.BEIN,
            sourceKey = "AE1",
            url = "  https://mirror.example/bein.xml ",
            onSuccess = { succeeded = true },
        )
        advanceUntilIdle()

        assertThat(manager.addressCalls)
            .containsExactly(Triple(EpgPresetCatalog.BEIN, "AE1", "  https://mirror.example/bein.xml "))
        // Review nit 7: the manager imports only the edited file, so Settings must not refresh them all.
        assertThat(manager.refreshCalls).isEmpty()
        assertThat(succeeded).isTrue()
    }

    @Test
    fun `a blank address is rejected without asking the manager`() = runTest {
        val manager = FakeEpgPresetManager()
        val actions = SettingsEpgActions(mock(), manager, MutableStateFlow(SettingsUiState()))
        var failed = false

        actions.updateEpgPresetAddress(this, EpgPresetCatalog.BEIN, "AE1", "   ", onError = { failed = true })
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

        actions.updateEpgPresetAddress(this, EpgPresetCatalog.BEIN, "AE1", "http://x", onError = { failed = true })
        advanceUntilIdle()

        assertThat(uiState.value.userMessage).isEqualTo("Address not allowed")
        assertThat(manager.refreshCalls).isEmpty()
        assertThat(failed).isTrue()
    }

    @Test
    fun `a refreshed source says so through the localized notice`() = runTest {
        val repository: EpgSourceRepository = mock()
        whenever(repository.getProviderIdsForSource(7L)).thenReturn(emptyList())
        whenever(repository.refreshSource(7L)).thenReturn(Result.success(EpgRefreshOutcome.REFRESHED))
        val uiState = MutableStateFlow(SettingsUiState())
        val actions = SettingsEpgActions(
            repository, FakeEpgPresetManager(), uiState, epgNotice = { resId, _ -> "notice:$resId" },
        )

        actions.refreshEpgSource(this, 7L)
        advanceUntilIdle()

        assertThat(uiState.value.userMessage)
            .isEqualTo("notice:${com.streamvault.feature.settings.R.string.epg_source_refreshed}")
    }

    @Test
    fun `a timezone saved but not refreshed reports the localized failure with the localized error`() = runTest {
        val repository: EpgSourceRepository = mock()
        val source = EpgSource(id = 7L, name = "Mine", url = "https://example.com/mine.xml")
        whenever(repository.updateSource(any())).thenReturn(Result.success(Unit))
        whenever(repository.getProviderIdsForSource(7L)).thenReturn(emptyList())
        whenever(repository.refreshSource(7L)).thenReturn(Result.error("HTTP 404"))
        val uiState = MutableStateFlow(SettingsUiState())
        val actions = SettingsEpgActions(
            repository, FakeEpgPresetManager(), uiState,
            epgErrorText = { "localized:$it" },
            epgNotice = { resId, args -> "notice:$resId:${args.joinToString("|")}" },
        )

        actions.updateEpgSourceTimezone(this, source, "Europe/Amsterdam")
        advanceUntilIdle()

        assertThat(uiState.value.userMessage).isEqualTo(
            "notice:${com.streamvault.feature.settings.R.string.settings_epg_timezone_refresh_failed}:localized:HTTP 404"
        )
    }

    @Test
    fun `a saved and refreshed timezone says so through the localized notice`() = runTest {
        val repository: EpgSourceRepository = mock()
        val source = EpgSource(id = 7L, name = "Mine", url = "https://example.com/mine.xml")
        whenever(repository.updateSource(any())).thenReturn(Result.success(Unit))
        whenever(repository.getProviderIdsForSource(7L)).thenReturn(emptyList())
        whenever(repository.refreshSource(7L)).thenReturn(Result.success(EpgRefreshOutcome.REFRESHED))
        val uiState = MutableStateFlow(SettingsUiState())
        val actions = SettingsEpgActions(
            repository, FakeEpgPresetManager(), uiState, epgNotice = { resId, _ -> "notice:$resId" },
        )

        actions.updateEpgSourceTimezone(this, source, "Europe/Amsterdam")
        advanceUntilIdle()

        assertThat(uiState.value.userMessage)
            .isEqualTo("notice:${com.streamvault.feature.settings.R.string.epg_timezone_updated}")
    }

    @Test
    fun `an unexpected guide failure without a message falls back to a localized notice`() = runTest {

        val source = EpgSource(id = 7L, name = "Mine", url = "https://example.com/mine.xml")
        val assignments = listOf(
            ProviderEpgSourceAssignment(providerId = 1L, epgSourceId = 10L, priority = 1),
            ProviderEpgSourceAssignment(providerId = 1L, epgSourceId = 11L, priority = 2),
        )
        val boom = RuntimeException()

        // Each case builds its own repository so only the call under test throws.
        val cases: List<Triple<Int, (EpgSourceRepository) -> Unit, (SettingsEpgActions, kotlinx.coroutines.CoroutineScope) -> Unit>> = listOf(
            Triple(
                R.string.epg_error_load_assignments_failed,
                { r -> whenever(r.getAssignmentsForProvider(1L)).thenThrow(boom) },
                { a, s -> a.loadEpgAssignments(s, 1L) },
            ),
            Triple(
                R.string.epg_error_add_source_failed,
                { r -> runBlocking { whenever(r.addSource(any(), any(), any(), anyOrNull())).thenThrow(boom) } },
                { a, s -> a.addEpgSource(s, "Mine", "https://example.com/mine.xml", null) },
            ),
            Triple(
                R.string.epg_error_update_timezone_failed,
                { r -> runBlocking { whenever(r.updateSource(any())).thenThrow(boom) } },
                { a, s -> a.updateEpgSourceTimezone(s, source, "Europe/Amsterdam") },
            ),
            Triple(
                R.string.epg_error_delete_source_failed,
                { r -> runBlocking { whenever(r.getProviderIdsForSource(7L)).thenThrow(boom) } },
                { a, s -> a.deleteEpgSource(s, 7L) },
            ),
            Triple(
                R.string.epg_error_update_source_failed,
                { r -> runBlocking { whenever(r.getProviderIdsForSource(7L)).thenThrow(boom) } },
                { a, s -> a.toggleEpgSourceEnabled(s, 7L, true) },
            ),
            Triple(
                R.string.epg_error_refresh_source_failed,
                { r -> runBlocking { whenever(r.getProviderIdsForSource(7L)).thenThrow(boom) } },
                { a, s -> a.refreshEpgSource(s, 7L) },
            ),
            Triple(
                R.string.epg_error_assign_source_failed,
                { r -> runBlocking { whenever(r.assignSourceToProvider(any(), any(), any(), any())).thenThrow(boom) } },
                { a, s -> a.assignEpgSourceToProvider(s, 1L, 10L) },
            ),
            Triple(
                R.string.epg_error_remove_source_failed,
                { r -> runBlocking { whenever(r.unassignSourceFromProvider(1L, 10L)).thenThrow(boom) } },
                { a, s -> a.unassignEpgSourceFromProvider(s, 1L, 10L) },
            ),
            Triple(
                R.string.epg_error_reorder_source_failed,
                { r -> runBlocking { whenever(r.swapAssignmentPriorities(any(), any(), any(), any(), any())).thenThrow(boom) } },
                { a, s -> a.moveEpgSourceAssignmentUp(s, 1L, 11L) },
            ),
            Triple(
                R.string.epg_error_reorder_source_failed,
                { r -> runBlocking { whenever(r.swapAssignmentPriorities(any(), any(), any(), any(), any())).thenThrow(boom) } },
                { a, s -> a.moveEpgSourceAssignmentDown(s, 1L, 10L) },
            ),
        )

        cases.forEach { (expectedRes, arrange, act) ->
            val repository: EpgSourceRepository = mock()
            arrange(repository)
            val uiState = MutableStateFlow(SettingsUiState(epgSourceAssignments = mapOf(1L to assignments)))
            val actions = SettingsEpgActions(
                repository, FakeEpgPresetManager(), uiState, epgNotice = { resId, _ -> "notice:$resId" },
            )

            act(actions, this)
            advanceUntilIdle()

            assertThat(uiState.value.userMessage).isEqualTo("notice:$expectedRes")
        }
    }

    @Test
    fun `an unexpected guide failure with a message shows only the localized text`() = runTest {
        val repository: EpgSourceRepository = mock()
        whenever(repository.getProviderIdsForSource(7L)).thenThrow(RuntimeException("disk full"))
        val uiState = MutableStateFlow(SettingsUiState())
        val actions = SettingsEpgActions(
            repository, FakeEpgPresetManager(), uiState, epgNotice = { resId, _ -> "notice:$resId" },
        )

        actions.deleteEpgSource(this, 7L)
        advanceUntilIdle()

        assertThat(uiState.value.userMessage)
            .isEqualTo("notice:${com.streamvault.feature.settings.R.string.epg_error_delete_source_failed}")
    }

    @Test
    fun `a refused new guide source shows the localized notice and never the raw error`() = runTest {
        val repository: EpgSourceRepository = mock()
        whenever(repository.addSource(any(), any(), any(), anyOrNull())).thenReturn(Result.error("A source with this URL already exists"))
        val uiState = MutableStateFlow(SettingsUiState())
        val actions = SettingsEpgActions(
            repository, FakeEpgPresetManager(), uiState, epgNotice = { resId, _ -> "notice:$resId" },
        )
        var failed = false

        actions.addEpgSource(this, "Mine", "https://example.com/mine.xml", null, onError = { failed = true })
        advanceUntilIdle()

        assertThat(failed).isTrue()
        assertThat(uiState.value.userMessage).isEqualTo("notice:${R.string.epg_error_add_source_failed}")
    }

    @Test
    fun `a refused timezone change shows the localized notice and never the raw error`() = runTest {
        val repository: EpgSourceRepository = mock()
        whenever(repository.updateSource(any())).thenReturn(Result.error("Source not found"))
        val uiState = MutableStateFlow(SettingsUiState())
        val actions = SettingsEpgActions(
            repository, FakeEpgPresetManager(), uiState, epgNotice = { resId, _ -> "notice:$resId" },
        )

        actions.updateEpgSourceTimezone(
            this, EpgSource(id = 7L, name = "Mine", url = "https://example.com/mine.xml"), "Europe/Amsterdam"
        )
        advanceUntilIdle()

        assertThat(uiState.value.userMessage).isEqualTo("notice:${R.string.epg_error_update_timezone_failed}")
    }

    @Test
    fun `a refused assignment shows the localized notice and never the raw error`() = runTest {
        val repository: EpgSourceRepository = mock()
        whenever(repository.assignSourceToProvider(any(), any(), any(), any())).thenReturn(Result.error("Provider not found"))
        val uiState = MutableStateFlow(SettingsUiState())
        val actions = SettingsEpgActions(
            repository, FakeEpgPresetManager(), uiState, epgNotice = { resId, _ -> "notice:$resId" },
        )

        actions.assignEpgSourceToProvider(this, 1L, 10L)
        advanceUntilIdle()

        assertThat(uiState.value.userMessage).isEqualTo("notice:${R.string.epg_error_assign_source_failed}")
    }

    @Test
    fun `an unexpected preset failure shows the localized notice and never the exception text`() = runTest {
        val manager = FakeEpgPresetManager().apply { reconcileFailure = IllegalStateException("raw preset failure") }
        val uiState = MutableStateFlow(SettingsUiState())
        val actions = SettingsEpgActions(
            mock(), manager, uiState, epgNotice = { resId, _ -> "notice:$resId" },
        )

        val job = actions.observeEpgPresets(this)
        advanceUntilIdle()

        assertThat(uiState.value.userMessage).isEqualTo("notice:${R.string.settings_epg_preset_failed}")
        job.cancel()
    }

    @Test
    fun `a skipped source refresh does not claim the source was refreshed`() = runTest {
        val repository: EpgSourceRepository = mock()
        whenever(repository.getProviderIdsForSource(7L)).thenReturn(emptyList())
        whenever(repository.refreshSource(7L)).thenReturn(Result.success(EpgRefreshOutcome.SKIPPED))
        val uiState = MutableStateFlow(SettingsUiState())
        val actions = SettingsEpgActions(
            repository, FakeEpgPresetManager(), uiState, epgNotice = { resId, _ -> "notice:$resId" },
        )

        actions.refreshEpgSource(this, 7L)
        advanceUntilIdle()

        assertThat(uiState.value.userMessage)
            .isEqualTo("notice:${com.streamvault.feature.settings.R.string.epg_source_refresh_skipped}")
    }
}
