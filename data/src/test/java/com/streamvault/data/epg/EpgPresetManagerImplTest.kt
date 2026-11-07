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
import com.streamvault.domain.repository.EpgSourceRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
        assertThat(store.current()).isEqualTo(EpgPresetState())
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

    override suspend fun updateSource(source: EpgSource): Result<Unit> {
        rows.value = rows.value.map { if (it.id == source.id) source else it }
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

    override suspend fun assignSourceToProvider(providerId: Long, epgSourceId: Long, priority: Int): Result<Unit> {
        assignments.getOrPut(providerId) { mutableMapOf() }[epgSourceId] = priority
        return Result.success(Unit)
    }

    override suspend fun unassignSourceFromProvider(providerId: Long, epgSourceId: Long) {
        assignments[providerId]?.remove(epgSourceId)
    }

    override suspend fun updateAssignmentPriority(providerId: Long, epgSourceId: Long, priority: Int) = Unit
    override suspend fun swapAssignmentPriorities(
        providerId: Long,
        epgSourceId1: Long,
        newPriority1: Int,
        epgSourceId2: Long,
        newPriority2: Int,
    ) = Unit

    override suspend fun refreshSource(sourceId: Long): Result<Unit> {
        if (!gate.tryBeginRefreshAttempt(sourceId)) return Result.success(Unit)
        refreshCalls += sourceId
        refreshBlocker?.await()
        if (loseRefreshOutcome) return Result.success(Unit)
        val error = failRefreshWith
        rows.value = rows.value.map {
            when {
                it.id != sourceId -> it
                error != null -> it.copy(lastError = error)
                else -> it.copy(lastSuccessAt = currentTime(), lastRefreshAt = currentTime(), lastError = null)
            }
        }
        return if (error == null) Result.success(Unit) else Result.error(error)
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
