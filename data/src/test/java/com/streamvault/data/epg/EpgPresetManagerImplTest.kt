package com.streamvault.data.epg

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ChannelEpgMapping
import com.streamvault.domain.model.EpgOverrideCandidate
import com.streamvault.domain.model.EpgPresetCatalog
import com.streamvault.domain.model.EpgResolutionSummary
import com.streamvault.domain.model.EpgSource
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.ProviderEpgSourceAssignment
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.XmltvTimezonePolicy
import com.streamvault.domain.repository.EpgPresetErrors
import com.streamvault.domain.repository.EpgRefreshOutcome
import com.streamvault.domain.repository.EpgSourceRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EpgPresetManagerImplTest {

    private var now = 1_700_000_000_000L
    private val providerIds = mutableListOf(1L, 2L)
    private lateinit var repo: FakeEpgSourceRepository
    private lateinit var store: EpgPresetStateStore
    private lateinit var manager: EpgPresetManagerImpl

    private val ae1 = EpgPresetCatalog.byId(EpgPresetCatalog.EGYPT)!!.sources.single()

    @Before
    fun setup() {
        store = testPresetStore { now }
        repo = FakeEpgSourceRepository(store).also { it.currentTime = { now } }
        manager = EpgPresetManagerImpl(
            sources = repo,
            providers = EpgPresetProviderDirectory { providerIds.toList() },
            store = store,
        )
    }

    private fun sourceIdFor(url: String): Long = repo.rows.value.single { it.url == url }.id

    /** A manager whose background imports run on the test's scheduler, so a test can wait for them. */
    private fun TestScope.managerOnTestScheduler() =
        EpgPresetManagerImpl(repo, EpgPresetProviderDirectory { providerIds.toList() }, store, importScope = backgroundScope)

    @Test
    fun `offByDefault_everyPresetIsOff_andNothingIsDownloaded`() = runTest {
        val statuses = manager.observe().first()

        assertThat(statuses.map { it.presetId }).containsExactlyElementsIn(EpgPresetCatalog.all.map { it.id }).inOrder()
        assertThat(statuses.none { it.enabled }).isTrue()
        assertThat(repo.rows.value).isEmpty()
        assertThat(repo.refreshCalls).isEmpty()
    }

    @Test
    fun `enable_createsSources_assignsAtLowerPriorityToEveryProvider_andImportsOnce`() = runTest {
        val handAdded = repo.addSource("Mine", "https://example.com/mine.xml").getOrNull()!!
        repo.assignSourceToProvider(1L, handAdded.id, priority = 4)

        val result = manager.setEnabled(EpgPresetCatalog.EGYPT, true)

        assertThat(result.isSuccess).isTrue()
        val ae1Id = sourceIdFor(ae1.url)
        assertThat(repo.assignments[1L]!![ae1Id]).isEqualTo(5)
        assertThat(repo.assignments[2L]!![ae1Id]).isEqualTo(0)
        assertThat(repo.refreshCalls).containsExactly(ae1Id)
        val status = manager.observe().first().single { it.presetId == EpgPresetCatalog.EGYPT }
        assertThat(status.enabled).isTrue()
        assertThat(status.lastSuccessAt).isEqualTo(now)
        assertThat(status.refreshing).isFalse()
    }

    @Test
    fun `sharedAddress_usesOneRow_andDisablingOnePresetKeepsItForTheOther`() = runTest {
        manager.setEnabled(EpgPresetCatalog.BEIN, true)
        manager.setEnabled(EpgPresetCatalog.EGYPT, true)

        assertThat(repo.rows.value.count { it.url == ae1.url }).isEqualTo(1)
        val ae1Id = sourceIdFor(ae1.url)

        manager.setEnabled(EpgPresetCatalog.BEIN, false)

        assertThat(repo.rows.value.map { it.id }).containsExactly(ae1Id)
        assertThat(repo.assignments[1L]!!.keys).containsExactly(ae1Id)

        manager.setEnabled(EpgPresetCatalog.EGYPT, false)

        assertThat(repo.rows.value).isEmpty()
        assertThat(repo.assignments.values.all { it.isEmpty() }).isTrue()
        // The attempt history stays (F18); everything else is gone.
        assertThat(store.current().copy(lastAttemptAtByKey = emptyMap())).isEqualTo(EpgPresetState())
    }

    @Test
    fun `turningAPresetOffAndOn_doesNotResetTheRefreshCap`() = runTest {
        manager.setEnabled(EpgPresetCatalog.EGYPT, true)
        val firstRowId = sourceIdFor(ae1.url)
        manager.setEnabled(EpgPresetCatalog.EGYPT, false)
        now += 60L * 60L * 1000L

        manager.setEnabled(EpgPresetCatalog.EGYPT, true)

        // A new row, but the same file: its attempt from an hour ago still counts (SC-005).
        assertThat(sourceIdFor(ae1.url)).isNotEqualTo(firstRowId)
        assertThat(repo.refreshCalls).containsExactly(firstRowId)
        now += EpgPresetStateStore.MIN_ATTEMPT_GAP_MILLIS
        assertThat(manager.refreshNow(EpgPresetCatalog.EGYPT).isSuccess).isTrue()
        assertThat(repo.refreshCalls).hasSize(2)
    }

    @Test
    fun `enableBein_namesEachRowAfterItsHost_andRanksThemInCatalogOrder`() = runTest {
        manager.setEnabled(EpgPresetCatalog.BEIN, true)

        val rows = repo.rows.value.associateBy { it.url }
        val bein = EpgPresetCatalog.byId(EpgPresetCatalog.BEIN)!!.sources
        assertThat(bein.map { rows.getValue(it.url).name })
            .containsExactly("open-epg QATAR1", "epgshare01 BEIN1", "epgshare01 AE1").inOrder()
        val priorities = repo.assignments.getValue(1L)
        assertThat(bein.map { priorities.getValue(rows.getValue(it.url).id) }).isInStrictOrder()
    }

    @Test
    fun `disable_neverDeletesAHandAddedSourceWithTheSameAddress`() = runTest {
        val handAdded = repo.addSource("Mine", ae1.url).getOrNull()!!
        repo.assignSourceToProvider(1L, handAdded.id, priority = 0)

        manager.setEnabled(EpgPresetCatalog.EGYPT, true)
        assertThat(repo.rows.value.map { it.id }).containsExactly(handAdded.id)
        assertThat(repo.assignments[2L]!!.keys).containsExactly(handAdded.id)

        manager.setEnabled(EpgPresetCatalog.EGYPT, false)

        assertThat(repo.rows.value.map { it.id }).containsExactly(handAdded.id)
        assertThat(repo.assignments[1L]!!.keys).containsExactly(handAdded.id)
        assertThat(repo.assignments[2L].orEmpty()).isEmpty()
    }

    @Test
    fun `refreshFailure_keepsThePresetOnAndTheSource_andShowsTheError`() = runTest {
        repo.failRefreshWith = "HTTP 503"

        val result = manager.setEnabled(EpgPresetCatalog.EGYPT, true)

        assertThat(result.isSuccess).isTrue()
        val status = manager.observe().first().single { it.presetId == EpgPresetCatalog.EGYPT }
        assertThat(status.enabled).isTrue()
        assertThat(status.lastError).isEqualTo("HTTP 503")
        assertThat(status.lastSuccessAt).isNull()
        assertThat(repo.rows.value).hasSize(1)
    }

    @Test
    fun `refreshNow_isRefusedInsideTheGap_andAllowedAfterIt`() = runTest {
        manager.setEnabled(EpgPresetCatalog.EGYPT, true)

        now += EpgPresetStateStore.MIN_ATTEMPT_GAP_MILLIS - 1
        val refused = manager.refreshNow(EpgPresetCatalog.EGYPT)
        now += 1
        val allowed = manager.refreshNow(EpgPresetCatalog.EGYPT)

        assertThat(refused.errorMessageOrNull()).isEqualTo(EpgPresetErrors.REFRESH_TOO_SOON)
        assertThat(allowed.isSuccess).isTrue()
        assertThat(repo.refreshCalls).hasSize(2)
    }

    @Test
    fun `refreshNow_unknownOrOffPreset_isAnError`() = runTest {
        assertThat(manager.refreshNow("nope").errorMessageOrNull()).isEqualTo(EpgPresetErrors.UNKNOWN_PRESET)
        assertThat(manager.refreshNow(EpgPresetCatalog.US).errorMessageOrNull()).isEqualTo(EpgPresetErrors.NOT_ENABLED)
        assertThat(manager.setEnabled("nope", true).errorMessageOrNull()).isEqualTo(EpgPresetErrors.UNKNOWN_PRESET)
    }

    @Test
    fun `reconcile_recreatesASourceDeletedByHand`() = runTest {
        manager.setEnabled(EpgPresetCatalog.EGYPT, true)
        repo.deleteSource(sourceIdFor(ae1.url))

        manager.reconcile()

        val recreated = sourceIdFor(ae1.url)
        assertThat(repo.assignments[1L]!!.keys).containsExactly(recreated)
        assertThat(repo.assignments[2L]!!.keys).containsExactly(recreated)
        assertThat(repo.refreshCalls.last()).isEqualTo(recreated)
        assertThat(store.current().sources.getValue(ae1.key).sourceId).isEqualTo(recreated)
    }

    /** The state a process death leaves behind: the row was saved and assigned, the preset never marked on. */
    private suspend fun leavePresetRowBehind() {
        manager.setEnabled(EpgPresetCatalog.EGYPT, true)
        store.update { it.copy(enabledPresetIds = emptySet()) }
    }

    @Test
    fun `reconcile_deletesAnOwnedRowLeftBehindByAnInterruptedEnable`() = runTest {
        leavePresetRowBehind()

        manager.reconcile()

        assertThat(repo.rows.value).isEmpty()
        assertThat(repo.assignments.values.flatMap { it.keys }).isEmpty()
        assertThat(store.current().sources).isEmpty()
    }

    @Test
    fun `reconcile_deletesTheOwnedRowOfACatalogKeyNoLongerInUse_andKeepsTheEnabledPreset`() = runTest {
        manager.setEnabled(EpgPresetCatalog.EGYPT, true)
        val removed = repo.addSource("epgshare01 OLD", "https://example.com/old.xml.gz").getOrNull()!!
        repo.assignSourceToProvider(1L, removed.id, priority = 9)
        store.update {
            it.copy(sources = it.sources + ("OLD" to EpgPresetSourceRecord(removed.id, true, setOf(1L))))
        }

        manager.reconcile()

        assertThat(repo.rows.value.map { it.url }).containsExactly(ae1.url)
        assertThat(repo.assignments[1L]!!.keys).containsExactly(sourceIdFor(ae1.url))
        assertThat(store.current().sources.keys).containsExactly(ae1.key)
    }

    @Test
    fun `reconcile_keepsAHandAddedRowLeftBehind_andUndoesOnlyThePresetAssignments`() = runTest {
        val handAdded = repo.addSource("Mine", ae1.url).getOrNull()!!
        repo.assignSourceToProvider(1L, handAdded.id, priority = 0)
        leavePresetRowBehind()

        manager.reconcile()

        assertThat(repo.rows.value.map { it.id }).containsExactly(handAdded.id)
        assertThat(repo.assignments[1L]!!.keys).containsExactly(handAdded.id)
        assertThat(repo.assignments[2L].orEmpty()).isEmpty()
        assertThat(store.current().sources).isEmpty()
    }

    @Test
    fun `enableAfterReconcileCleanup_ownsTheNewRow`() = runTest {
        leavePresetRowBehind()
        manager.reconcile()

        manager.setEnabled(EpgPresetCatalog.EGYPT, true)

        assertThat(store.current().sources.getValue(ae1.key).owned).isTrue()
    }

    @Test
    fun `reconcile_leavesAProviderTheUserUnassigned_andStillAssignsANewOne`() = runTest {
        manager.setEnabled(EpgPresetCatalog.EGYPT, true)
        val sourceId = sourceIdFor(ae1.url)
        repo.unassignSourceFromProvider(1L, sourceId)
        providerIds += 3L

        manager.reconcile()
        manager.reconcile()

        assertThat(repo.assignments[1L].orEmpty()).isEmpty()
        assertThat(repo.assignments[2L]!!.keys).containsExactly(sourceId)
        assertThat(repo.assignments[3L]!!.keys).containsExactly(sourceId)
    }

    @Test
    fun `reconcile_assignsEnabledPresetsToAProviderAddedLater_withoutDownloadingAgain`() = runTest {
        manager.setEnabled(EpgPresetCatalog.EGYPT, true)
        val refreshesBefore = repo.refreshCalls.size
        providerIds += 3L

        manager.reconcile()

        assertThat(repo.assignments[3L]!!.keys).containsExactly(sourceIdFor(ae1.url))
        assertThat(repo.refreshCalls).hasSize(refreshesBefore)
    }

    @Test
    fun `leavingTheScreen_doesNotCancelTheImport`() = runTest {
        val manager = EpgPresetManagerImpl(repo, { providerIds.toList() }, store, backgroundScope)
        val release = CompletableDeferred<Unit>()
        repo.refreshBlocker = release

        val screen = launch { manager.setEnabled(EpgPresetCatalog.EGYPT, true) }
        runCurrent()
        screen.cancelAndJoin()
        release.complete(Unit)
        runCurrent()

        val status = manager.observe().first().single { it.presetId == EpgPresetCatalog.EGYPT }
        assertThat(status.lastSuccessAt).isEqualTo(now)
        assertThat(status.refreshing).isFalse()
    }

    @Test
    fun `reconcile_retriesAnImportLostToProcessDeath_insideTheGap`() = runTest {
        repo.loseRefreshOutcome = true
        manager.setEnabled(EpgPresetCatalog.EGYPT, true)
        repo.loseRefreshOutcome = false
        val ae1Id = sourceIdFor(ae1.url)
        assertThat(repo.refreshCalls).containsExactly(ae1Id)

        manager.reconcile()

        assertThat(repo.refreshCalls).containsExactly(ae1Id, ae1Id)
        assertThat(repo.rows.value.single().lastSuccessAt).isEqualTo(now)
    }

    @Test
    fun `reconcile_doesNotRetryAFailedImportInsideTheGap`() = runTest {
        repo.failRefreshWith = "HTTP 503"
        manager.setEnabled(EpgPresetCatalog.EGYPT, true)

        manager.reconcile()

        assertThat(repo.refreshCalls).hasSize(1)
    }

    @Test
    fun `reconcile_doesNotRestartAnImportStillRunning`() = runTest {
        val manager = EpgPresetManagerImpl(repo, { providerIds.toList() }, store, backgroundScope)
        val release = CompletableDeferred<Unit>()
        repo.refreshBlocker = release
        val enabling = launch { manager.setEnabled(EpgPresetCatalog.EGYPT, true) }
        runCurrent()

        manager.reconcile()
        release.complete(Unit)
        enabling.join()
        runCurrent()

        assertThat(repo.refreshCalls).hasSize(1)
    }

    // ── F12: preset rows rank in catalog order ───────────────────────

    private val beinSources = EpgPresetCatalog.byId(EpgPresetCatalog.BEIN)!!.sources

    /** The preset files assigned to [providerId], best ranked first. */
    private fun rankedKeys(providerId: Long): List<String> {
        val keyById = repo.rows.value.associate { row -> row.id to beinSources.firstOrNull { it.url == row.url }?.key }
        return repo.assignments.getValue(providerId).entries.sortedBy { it.value }.mapNotNull { keyById[it.key] }
    }

    @Test
    fun `enablingEgyptBeforeBein_stillRanksTheBeinFilesInCatalogOrder_belowHandAddedSources`() = runTest {
        val handAdded = repo.addSource("Mine", "https://example.com/mine.xml").getOrNull()!!
        repo.assignSourceToProvider(1L, handAdded.id, priority = 0)

        manager.setEnabled(EpgPresetCatalog.EGYPT, true)
        manager.setEnabled(EpgPresetCatalog.BEIN, true)

        assertThat(rankedKeys(1L)).containsExactly("QATAR1", "BEIN1", "AE1").inOrder()
        assertThat(rankedKeys(2L)).containsExactly("QATAR1", "BEIN1", "AE1").inOrder()
        val lowestPreset = repo.assignments.getValue(1L).filterKeys { it != handAdded.id }.values.min()
        assertThat(repo.assignments.getValue(1L).getValue(handAdded.id)).isLessThan(lowestPreset)
    }

    @Test
    fun `aFileAddedToTheCatalogLater_takesItsCatalogPlace_notTheLast`() = runTest {
        // A device that turned beIN on before QATAR1 existed: only BEIN1 and AE1 were assigned.
        manager.setEnabled(EpgPresetCatalog.BEIN, true)
        val qatarId = sourceIdFor(beinSources.first().url)
        repo.deleteSource(qatarId)
        store.update { it.copy(sources = it.sources - "QATAR1") }

        manager.reconcile()

        assertThat(rankedKeys(1L)).containsExactly("QATAR1", "BEIN1", "AE1").inOrder()
    }

    @Test
    fun `reconcile_keepsTheUsersOwnOrderOfPresetRows_whenNothingNewIsAssigned`() = runTest {
        manager.setEnabled(EpgPresetCatalog.BEIN, true)
        val ae1Id = sourceIdFor(ae1.url)
        repo.updateAssignmentPriority(1L, ae1Id, -1)

        manager.reconcile()

        assertThat(rankedKeys(1L).first()).isEqualTo("AE1")
    }

    // ── F10: a corrected built-in address ────────────────────────────

    /** The row as an earlier build left it: created at [oldUrl], which was then the built-in address. */
    private suspend fun leaveRowAtAnOlderBuiltInAddress(oldUrl: String, rowUrl: String = oldUrl): Long {
        manager.setEnabled(EpgPresetCatalog.EGYPT, true)
        val row = repo.rows.value.single()
        repo.updateSource(row.copy(url = rowUrl))
        store.update { state ->
            state.copy(sources = state.sources.mapValues { (_, record) -> record.copy(defaultUrl = oldUrl) })
        }
        return row.id
    }

    @Test
    fun `enable_remembersTheBuiltInAddressOfEachRow`() = runTest {
        manager.setEnabled(EpgPresetCatalog.EGYPT, true)

        assertThat(store.current().sources.getValue(ae1.key).defaultUrl).isEqualTo(ae1.url)
    }

    @Test
    fun `reconcile_movesAnUneditedRowToTheCorrectedBuiltInAddress_andImportsIt`() = runTest {
        val rowId = leaveRowAtAnOlderBuiltInAddress("https://old.example/ae1.xml.gz")
        val refreshesBefore = repo.refreshCalls.size
        now += 60_000L

        manager.reconcile()

        assertThat(repo.rows.value.map { it.id to it.url }).containsExactly(rowId to ae1.url)
        assertThat(store.current().sources.getValue(ae1.key).defaultUrl).isEqualTo(ae1.url)
        // A new file, so it is imported now rather than after the gap left by the old one.
        assertThat(repo.refreshCalls.drop(refreshesBefore)).containsExactly(rowId)
    }

    @Test
    fun `reconcile_keepsAnAddressTheUserEdited_whenTheBuiltInOneChanges`() = runTest {
        val rowId = leaveRowAtAnOlderBuiltInAddress("https://old.example/ae1.xml.gz", rowUrl = "https://mirror.example/ae1.xml")

        manager.reconcile()

        assertThat(repo.rows.value.map { it.id to it.url }).containsExactly(rowId to "https://mirror.example/ae1.xml")
    }

    @Test
    fun `reconcile_neverChangesTheAddressOfAHandAddedRow`() = runTest {
        val handAdded = repo.addSource("Mine", ae1.url).getOrNull()!!
        manager.setEnabled(EpgPresetCatalog.EGYPT, true)
        repo.updateSource(handAdded.copy(url = "https://old.example/ae1.xml.gz"))
        store.update { state ->
            state.copy(sources = state.sources.mapValues { (_, r) -> r.copy(defaultUrl = "https://old.example/ae1.xml.gz") })
        }

        manager.reconcile()

        assertThat(repo.rows.value.map { it.url }).containsExactly("https://old.example/ae1.xml.gz")
    }

    @Test
    fun `reconcile_adoptsTheBuiltInAddressForRowsSavedBeforeItWasRemembered`() = runTest {
        manager.setEnabled(EpgPresetCatalog.EGYPT, true)
        store.update { state -> state.copy(sources = state.sources.mapValues { (_, r) -> r.copy(defaultUrl = null) }) }

        manager.reconcile()

        assertThat(store.current().sources.getValue(ae1.key).defaultUrl).isEqualTo(ae1.url)
        assertThat(repo.rows.value.map { it.url }).containsExactly(ae1.url)
    }

    // ── F9: editing a preset's address ───────────────────────────────

    @Test
    fun `status_reportsEachFileWithItsRowAndAddress_soAnEditedRowStaysLinkedAfterARestart`() = runTest {
        val manager = managerOnTestScheduler()
        manager.setEnabled(EpgPresetCatalog.EGYPT, true)
        val ae1Id = sourceIdFor(ae1.url)
        manager.updateAddress(EpgPresetCatalog.EGYPT, ae1.key, "https://mirror.example/ae1.xml")
        runCurrent()

        // A new manager over the same store, like the app after a restart.
        val restarted = EpgPresetManagerImpl(repo, EpgPresetProviderDirectory { providerIds.toList() }, store)
        val egypt = restarted.observe().first().single { it.presetId == EpgPresetCatalog.EGYPT }

        assertThat(egypt.sources.map { Triple(it.key, it.sourceId, it.url) })
            .containsExactly(Triple(ae1.key, ae1Id, "https://mirror.example/ae1.xml"))
    }

    @Test
    fun `status_ofAnOffPreset_listsItsFilesWithoutRows`() = runTest {
        val us = manager.observe().first().single { it.presetId == EpgPresetCatalog.US }

        assertThat(us.sources.map { it.key to it.sourceId })
            .containsExactlyElementsIn(EpgPresetCatalog.byId(EpgPresetCatalog.US)!!.sources.map { it.key to null })
            .inOrder()
    }

    @Test
    fun `updateAddress_changesTheSameRow_andImportsItAtOnceInsideTheGap`() = runTest {
        val manager = managerOnTestScheduler()
        manager.setEnabled(EpgPresetCatalog.EGYPT, true)
        val ae1Id = sourceIdFor(ae1.url)
        now += 60_000L

        val result = manager.updateAddress(EpgPresetCatalog.EGYPT, ae1.key, "  https://mirror.example/ae1.xml ")
        runCurrent()

        assertThat(result.isSuccess).isTrue()
        assertThat(repo.rows.value.single().id).isEqualTo(ae1Id)
        assertThat(repo.rows.value.single().url).isEqualTo("https://mirror.example/ae1.xml")
        // The enable's import, then the edited file's own; nobody had to ask for it.
        assertThat(repo.refreshCalls).containsExactly(ae1Id, ae1Id)
    }

    @Test
    fun `updateAddress_importsOnlyTheEditedFile_andNothingWhenTheAddressIsUnchanged`() = runTest {
        // Review nit 7: Settings used to refresh the whole preset after every save.
        val manager = managerOnTestScheduler()
        manager.setEnabled(EpgPresetCatalog.BEIN, true)
        val bein = EpgPresetCatalog.byId(EpgPresetCatalog.BEIN)!!.sources
        val edited = bein.single { it.key == "BEIN1" }
        repo.refreshCalls.clear()
        now += 25 * 60 * 60_000L // every file is past its gap, so a whole-preset refresh would take them all

        manager.updateAddress(EpgPresetCatalog.BEIN, edited.key, edited.url)
        runCurrent()
        assertThat(repo.refreshCalls).isEmpty()

        manager.updateAddress(EpgPresetCatalog.BEIN, edited.key, "https://mirror.example/bein1.xml")
        runCurrent()
        assertThat(repo.refreshCalls).containsExactly(sourceIdFor("https://mirror.example/bein1.xml"))
    }

    @Test
    fun `updateAddress_withTheSameAddress_keepsTheGap`() = runTest {
        manager.setEnabled(EpgPresetCatalog.EGYPT, true)

        val result = manager.updateAddress(EpgPresetCatalog.EGYPT, ae1.key, " ${ae1.url} ")

        assertThat(result.isSuccess).isTrue()
        assertThat(manager.refreshNow(EpgPresetCatalog.EGYPT).errorMessageOrNull())
            .isEqualTo(EpgPresetErrors.REFRESH_TOO_SOON)
    }

    @Test
    fun `updateAddress_thatNormalizesToTheStoredAddress_keepsTheGap`() = runTest {
        // Review R4: the same-address check ran before the repository added the scheme back.
        manager.setEnabled(EpgPresetCatalog.EGYPT, true)
        repo.normalizeUrl = { if (it.contains("://")) it else "https://$it" }

        val result = manager.updateAddress(EpgPresetCatalog.EGYPT, ae1.key, ae1.url.removePrefix("https://"))

        assertThat(result.isSuccess).isTrue()
        assertThat(repo.rows.value.single().url).isEqualTo(ae1.url)
        assertThat(store.current().addresses).isEmpty()
        assertThat(manager.refreshNow(EpgPresetCatalog.EGYPT).errorMessageOrNull())
            .isEqualTo(EpgPresetErrors.REFRESH_TOO_SOON)
    }

    @Test
    fun `updateAddress_survivesTurningThePresetOffAndOn`() = runTest {
        val manager = managerOnTestScheduler()
        manager.setEnabled(EpgPresetCatalog.EGYPT, true)
        manager.updateAddress(EpgPresetCatalog.EGYPT, ae1.key, "https://mirror.example/ae1.xml")
        runCurrent()

        manager.setEnabled(EpgPresetCatalog.EGYPT, false)
        assertThat(repo.rows.value).isEmpty()
        manager.setEnabled(EpgPresetCatalog.EGYPT, true)

        assertThat(repo.rows.value.map { it.url }).containsExactly("https://mirror.example/ae1.xml")
    }

    @Test
    fun `anAddressEditedInTheOrdinarySourceEditor_alsoSurvivesOffAndOn`() = runTest {
        manager.setEnabled(EpgPresetCatalog.EGYPT, true)
        val row = repo.rows.value.single()
        repo.updateSource(row.copy(url = "https://mirror.example/ae1.xml"))

        manager.setEnabled(EpgPresetCatalog.EGYPT, false)
        manager.setEnabled(EpgPresetCatalog.EGYPT, true)

        assertThat(repo.rows.value.map { it.url }).containsExactly("https://mirror.example/ae1.xml")
    }

    @Test
    fun `updateAddress_backToTheBuiltInAddress_forgetsTheEdit`() = runTest {
        val manager = managerOnTestScheduler()
        manager.setEnabled(EpgPresetCatalog.EGYPT, true)
        manager.updateAddress(EpgPresetCatalog.EGYPT, ae1.key, "https://mirror.example/ae1.xml")
        runCurrent()
        manager.updateAddress(EpgPresetCatalog.EGYPT, ae1.key, ae1.url)
        runCurrent()

        assertThat(store.current().addresses).isEmpty()
    }

    @Test
    fun `updateAddress_refusesUnknownFilesOffPresetsAndBlankAddresses`() = runTest {
        assertThat(manager.updateAddress("nope", ae1.key, "https://x.example/a.xml").errorMessageOrNull())
            .isEqualTo(EpgPresetErrors.UNKNOWN_PRESET)
        assertThat(manager.updateAddress(EpgPresetCatalog.EGYPT, "US2", "https://x.example/a.xml").errorMessageOrNull())
            .isEqualTo(EpgPresetErrors.UNKNOWN_PRESET)
        assertThat(manager.updateAddress(EpgPresetCatalog.EGYPT, ae1.key, "https://x.example/a.xml").errorMessageOrNull())
            .isEqualTo(EpgPresetErrors.NOT_ENABLED)
        manager.setEnabled(EpgPresetCatalog.EGYPT, true)
        assertThat(manager.updateAddress(EpgPresetCatalog.EGYPT, ae1.key, "   ").errorMessageOrNull())
            .isEqualTo(EpgPresetErrors.INVALID_ADDRESS)
        assertThat(repo.rows.value.single().url).isEqualTo(ae1.url)
    }

    @Test
    fun `updateAddress_passesOnTheRepositoryRefusal_andRemembersNothing`() = runTest {
        manager.setEnabled(EpgPresetCatalog.EGYPT, true)
        repo.refuseUpdatesWith = "Address not allowed"

        val result = manager.updateAddress(EpgPresetCatalog.EGYPT, ae1.key, "ftp://x.example/a.xml")

        assertThat(result.errorMessageOrNull()).isEqualTo("Address not allowed")
        assertThat(store.current().addresses).isEmpty()
    }

    @Test
    fun `aDeathMidEnable_onAHandAddedRow_isUndoneByTheNextReconcile`() = runTest {
        // Review R2: the record used to be saved with no assignments until every provider was done.
        val handAdded = repo.addSource("Mine", ae1.url).getOrNull()!!
        repo.dieOnAssignTo = 2L

        val died = runCatching { manager.setEnabled(EpgPresetCatalog.EGYPT, true) }

        assertThat(died.isFailure).isTrue()
        assertThat(repo.assignments[1L]!!.keys).containsExactly(handAdded.id)
        assertThat(store.current().sources.getValue(ae1.key).assignedProviderIds).containsExactly(1L)

        repo.dieOnAssignTo = null
        val restarted = EpgPresetManagerImpl(repo, EpgPresetProviderDirectory { providerIds.toList() }, store)
        restarted.reconcile()

        assertThat(repo.assignments[1L].orEmpty()).isEmpty()
        assertThat(repo.rows.value.map { it.id }).containsExactly(handAdded.id)
        assertThat(store.current().sources).isEmpty()
    }

    // ── F13: one guide match pass per provider ─────────────────────────

    @Test
    fun `enablingBein_runsOneMatchPassPerProvider_notOnePerFileAndRanking`() = runTest {
        manager.setEnabled(EpgPresetCatalog.BEIN, true)

        assertThat(repo.resolves.sorted()).containsExactly(1L, 2L).inOrder()
    }

    @Test
    fun `aSharedFileInsideItsGap_stillGetsItsNewProvidersResolved`() = runTest {
        manager.setEnabled(EpgPresetCatalog.EGYPT, true)
        providerIds += 3L
        repo.resolves.clear()

        // AE1 is shared and inside its gap, so its import is skipped; provider 3 is new to it.
        manager.setEnabled(EpgPresetCatalog.BEIN, true)

        assertThat(repo.resolves.count { it == 3L }).isEqualTo(1)
        assertThat(repo.resolves.groupingBy { it }.eachCount().values.all { it == 1 }).isTrue()
    }

    @Test
    fun `reconcile_resolvesAProviderAddedLater_once_evenWithNothingToImport`() = runTest {
        manager.setEnabled(EpgPresetCatalog.BEIN, true)
        providerIds += 3L
        repo.resolves.clear()

        manager.reconcile()

        assertThat(repo.resolves).containsExactly(3L)
    }

    @Test
    fun `stateIsDeviceLocal_aSecondStoreFileSeesEveryPresetOff`() = runTest {
        manager.setEnabled(EpgPresetCatalog.EGYPT, true)
        val otherDevice = testPresetStore { now }

        assertThat(otherDevice.current().enabledPresetIds).isEmpty()
    }
}

/** In-memory [EpgSourceRepository]; refreshes go through the same gate as the real one. */
private class FakeEpgSourceRepository(private val gate: EpgSourceRefreshGate) : EpgSourceRepository {
    val rows = MutableStateFlow<List<EpgSource>>(emptyList())
    /** providerId -> (sourceId -> priority) */
    val assignments = mutableMapOf<Long, MutableMap<Long, Int>>()
    val refreshCalls = mutableListOf<Long>()
    var failRefreshWith: String? = null
    /** Holds a refresh open, like a slow download. */
    var refreshBlocker: CompletableDeferred<Unit>? = null
    /** The attempt is recorded but nothing about its outcome is, like process death mid-import. */
    var loseRefreshOutcome = false
    private var nextId = 100L

    override fun getAllSources(): Flow<List<EpgSource>> = rows
    override suspend fun getSourceById(id: Long): EpgSource? = rows.value.firstOrNull { it.id == id }

    override suspend fun addSource(
        name: String,
        url: String,
        timezonePolicy: XmltvTimezonePolicy,
        timezoneId: String?,
    ): Result<EpgSource> {
        if (rows.value.any { it.url == url }) return Result.error("A source with this URL already exists")
        val source = EpgSource(id = nextId++, name = name, url = url)
        rows.value = rows.value + source
        return Result.success(source)
    }

    var refuseUpdatesWith: String? = null
    /** Like the real repository adding a missing scheme. */
    var normalizeUrl: (String) -> String = { it }

    override suspend fun updateSource(source: EpgSource): Result<Unit> {
        refuseUpdatesWith?.let { return Result.error(it) }
        val normalized = source.copy(url = normalizeUrl(source.url))
        rows.value = rows.value.map { if (it.id == source.id) normalized else it }
        return Result.success(Unit)
    }

    override suspend fun deleteSource(id: Long) {
        rows.value = rows.value.filterNot { it.id == id }
        assignments.values.forEach { it.remove(id) }
    }

    override suspend fun setSourceEnabled(id: Long, enabled: Boolean) = Unit
    override suspend fun getProviderIdsForSource(sourceId: Long): List<Long> =
        assignments.filterValues { sourceId in it }.keys.toList()

    override fun getAssignmentsForProvider(providerId: Long): Flow<List<ProviderEpgSourceAssignment>> =
        rows.map {
            assignments[providerId].orEmpty().map { (sourceId, priority) ->
                ProviderEpgSourceAssignment(providerId = providerId, epgSourceId = sourceId, priority = priority)
            }
        }

    /** Assigning to this provider throws, like the process dying mid-enable. */
    var dieOnAssignTo: Long? = null

    /** Each guide match pass the real repository would run, by provider, in order (F13). */
    val resolves = mutableListOf<Long>()

    override suspend fun assignSourceToProvider(
        providerId: Long,
        epgSourceId: Long,
        priority: Int,
        resolve: Boolean,
    ): Result<Unit> {
        if (providerId == dieOnAssignTo) throw IllegalStateException("process died")
        assignments.getOrPut(providerId) { mutableMapOf() }[epgSourceId] = priority
        if (resolve) resolves += providerId
        return Result.success(Unit)
    }

    override suspend fun unassignSourceFromProvider(providerId: Long, epgSourceId: Long) {
        assignments[providerId]?.remove(epgSourceId)
    }

    override suspend fun updateAssignmentPriority(providerId: Long, epgSourceId: Long, priority: Int, resolve: Boolean) {
        assignments[providerId]?.let { if (epgSourceId in it) it[epgSourceId] = priority }
        if (resolve) resolves += providerId
    }

    override suspend fun refreshSources(sourceIds: List<Long>, alsoResolve: Set<Long>): Map<Long, Result<EpgRefreshOutcome>> {
        val results = sourceIds.distinct().associateWith { importWithoutResolving(it) }
        val changed = results.filterValues { it !is Result.Error }.keys.flatMap { getProviderIdsForSource(it) }
        resolves += (alsoResolve + changed).distinct()
        return results
    }
    override suspend fun swapAssignmentPriorities(
        providerId: Long,
        epgSourceId1: Long,
        newPriority1: Int,
        epgSourceId2: Long,
        newPriority2: Int,
    ) = Unit

    override suspend fun refreshSource(sourceId: Long): Result<EpgRefreshOutcome> =
        importWithoutResolving(sourceId).also { result ->
            if (result !is Result.Error) resolves += getProviderIdsForSource(sourceId)
        }

    private suspend fun importWithoutResolving(sourceId: Long): Result<EpgRefreshOutcome> {
        if (!gate.tryBeginRefreshAttempt(sourceId)) return Result.success(EpgRefreshOutcome.SKIPPED)
        refreshCalls += sourceId
        refreshBlocker?.await()
        if (loseRefreshOutcome) return Result.success(EpgRefreshOutcome.REFRESHED)
        val error = failRefreshWith
        rows.value = rows.value.map {
            when {
                it.id != sourceId -> it
                error != null -> it.copy(lastError = error)
                else -> it.copy(lastSuccessAt = currentTime(), lastRefreshAt = currentTime(), lastError = null)
            }
        }
        return if (error == null) Result.success(EpgRefreshOutcome.REFRESHED) else Result.error(error)
    }

    var currentTime: () -> Long = { 0L }

    override suspend fun refreshAllForProvider(providerId: Long): Result<Unit> = Result.success(Unit)
    override suspend fun resolveForProvider(providerId: Long, hiddenLiveCategoryIds: Set<Long>) = EpgResolutionSummary()
    override suspend fun getResolutionSummary(providerId: Long) = EpgResolutionSummary()
    override suspend fun getChannelMapping(providerId: Long, channelId: Long): ChannelEpgMapping? = null
    override suspend fun getChannelMappingConfidences(providerId: Long, channelIds: List<Long>) = emptyMap<Long, Float>()
    override suspend fun getOverrideCandidates(providerId: Long, query: String, limit: Int) = emptyList<EpgOverrideCandidate>()
    override suspend fun applyManualOverride(providerId: Long, channelId: Long, epgSourceId: Long, xmltvChannelId: String) =
        Result.success(Unit)
    override suspend fun clearManualOverride(providerId: Long, channelId: Long) = Result.success(Unit)
    override suspend fun getResolvedProgramsForChannels(
        providerId: Long,
        channelIds: List<Long>,
        startTime: Long,
        endTime: Long,
    ) = emptyMap<String, List<Program>>()
}
