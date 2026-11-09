package com.streamvault.feature.settings.presentation

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ChannelHealthPauseReason
import com.streamvault.domain.model.ChannelHealthRecord
import com.streamvault.domain.model.ChannelHealthScanState
import com.streamvault.domain.model.ChannelHealthStatus
import com.streamvault.domain.model.ChannelHealthSuggestion
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.ChannelHealthRepository
import com.streamvault.feature.settings.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelCleanupViewModelTest {

    private class FakeChannelHealthRepository : ChannelHealthRepository {
        val scanState = MutableStateFlow(ChannelHealthScanState())
        val suggestions = MutableStateFlow<List<ChannelHealthSuggestion>>(emptyList())
        var startResult: Result<Unit> = Result.Success(Unit)
        val startCalls = mutableListOf<Pair<Long, Set<Long>?>>()
        val cancelCalls = mutableListOf<Long>()
        val hideCalls = mutableListOf<Pair<Long, Set<Long>>>()

        override fun observeScanState(providerId: Long): Flow<ChannelHealthScanState> = scanState
        override fun observeSuggestions(providerId: Long): Flow<List<ChannelHealthSuggestion>> = suggestions

        override suspend fun startScan(providerId: Long, categoryIds: Set<Long>?): Result<Unit> {
            startCalls += providerId to categoryIds
            return startResult
        }

        override suspend fun cancelScan(providerId: Long) {
            cancelCalls += providerId
        }

        override suspend fun hideChannels(providerId: Long, channelIds: Set<Long>) {
            hideCalls += providerId to channelIds
        }

        override suspend fun clearResults(providerId: Long) = Unit
    }

    private val repository = FakeChannelHealthRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun suggestion(
        id: Long,
        name: String,
        category: String?,
        status: ChannelHealthStatus = ChannelHealthStatus.DEAD,
        failedDays: Int = 2,
    ) = ChannelHealthSuggestion(
        channel = Channel(id = id, name = name, categoryName = category),
        record = ChannelHealthRecord(channelId = id, status = status, checkedAt = 1_000L, failedDays = failedDays),
    )

    private fun viewModel(vararg items: ChannelHealthSuggestion): ChannelCleanupViewModel {
        repository.suggestions.value = items.toList()
        return ChannelCleanupViewModel(repository).also { it.bind(PROVIDER) }
    }

    private val sample = arrayOf(
        suggestion(1, "News One", "News"),
        suggestion(2, "News Two", "News", ChannelHealthStatus.EMPTY, failedDays = 3),
        suggestion(3, "Loose", null),
        suggestion(4, "Sport One", "Sport"),
        suggestion(5, "Blank Cat", "  "),
    )

    // -- Grouping ------------------------------------------------------

    @Test
    fun `suggestions are grouped by category with the uncategorized group last`() {
        val state = viewModel(*sample).uiState.value

        assertThat(state.groups.map { it.categoryName }).containsExactly("News", "Sport", null).inOrder()
        assertThat(state.groups[0].rows.map { it.channelId }).containsExactly(1L, 2L).inOrder()
        assertThat(state.groups[2].rows.map { it.channelId }).containsExactly(3L, 5L).inOrder()
    }

    @Test
    fun `a row carries the channel name, status and days failed`() {
        val row = viewModel(*sample).uiState.value.groups[0].rows[1]

        assertThat(row).isEqualTo(ChannelCleanupRow(2L, "News Two", ChannelHealthStatus.EMPTY, 3))
    }

    @Test
    fun `without suggestions there are no groups`() {
        assertThat(viewModel().uiState.value.groups).isEmpty()
    }

    @Test
    fun `status labels map to localized resources`() {
        assertThat(channelCleanupStatusLabelRes(ChannelHealthStatus.EMPTY))
            .isEqualTo(R.string.channel_cleanup_status_empty)
        assertThat(channelCleanupStatusLabelRes(ChannelHealthStatus.DEAD))
            .isEqualTo(R.string.channel_cleanup_status_dead)
    }

    // -- Selection -----------------------------------------------------

    @Test
    fun `nothing is selected by default`() {
        val state = viewModel(*sample).uiState.value

        assertThat(state.selectedIds).isEmpty()
        assertThat(state.selectedCount).isEqualTo(0)
        assertThat(state.allSelected).isFalse()
    }

    @Test
    fun `select all selects every suggestion and selecting again clears it`() {
        val vm = viewModel(*sample)

        vm.toggleAll()
        assertThat(vm.uiState.value.selectedIds).containsExactly(1L, 2L, 3L, 4L, 5L)
        assertThat(vm.uiState.value.allSelected).isTrue()

        vm.toggleAll()
        assertThat(vm.uiState.value.selectedIds).isEmpty()
    }

    @Test
    fun `select category selects only that category and toggles back`() {
        val vm = viewModel(*sample)
        val news = vm.uiState.value.groups.first { it.categoryName == "News" }

        vm.toggleCategory(news)
        assertThat(vm.uiState.value.selectedIds).containsExactly(1L, 2L)
        assertThat(vm.uiState.value.isGroupSelected(news)).isTrue()
        assertThat(vm.uiState.value.allSelected).isFalse()

        vm.toggleCategory(news)
        assertThat(vm.uiState.value.selectedIds).isEmpty()
    }

    @Test
    fun `select category completes a partly selected category`() {
        val vm = viewModel(*sample)
        val news = vm.uiState.value.groups.first { it.categoryName == "News" }

        vm.toggleChannel(1L)
        vm.toggleCategory(news)

        assertThat(vm.uiState.value.selectedIds).containsExactly(1L, 2L)
    }

    @Test
    fun `a single channel toggles on and off`() {
        val vm = viewModel(*sample)

        vm.toggleChannel(4L)
        assertThat(vm.uiState.value.selectedIds).containsExactly(4L)
        vm.toggleChannel(4L)
        assertThat(vm.uiState.value.selectedIds).isEmpty()
    }

    @Test
    fun `channels that stop being suggestions leave the selection`() {
        val vm = viewModel(*sample)
        vm.toggleAll()

        repository.suggestions.value = repository.suggestions.value.filter { it.channel.id != 3L }

        assertThat(vm.uiState.value.selectedIds).containsExactly(1L, 2L, 4L, 5L)
    }

    @Test
    fun `binding another provider clears the selection`() {
        val vm = viewModel(*sample)
        vm.toggleAll()

        vm.bind(PROVIDER + 1)

        assertThat(vm.uiState.value.selectedIds).isEmpty()
    }

    // -- Hide ----------------------------------------------------------

    @Test
    fun `hide needs a selection before it asks for confirmation`() {
        val vm = viewModel(*sample)

        vm.requestHide()
        assertThat(vm.uiState.value.showConfirm).isFalse()

        vm.toggleChannel(1L)
        vm.requestHide()
        assertThat(vm.uiState.value.showConfirm).isTrue()

        vm.dismissConfirm()
        assertThat(vm.uiState.value.showConfirm).isFalse()
        assertThat(repository.hideCalls).isEmpty()
    }

    @Test
    fun `confirming hides exactly the selected channels and clears the selection`() {
        val vm = viewModel(*sample)
        vm.toggleChannel(2L)
        vm.toggleChannel(4L)
        vm.requestHide()

        vm.confirmHide()

        assertThat(repository.hideCalls).containsExactly(PROVIDER to setOf(2L, 4L))
        assertThat(vm.uiState.value.selectedIds).isEmpty()
        assertThat(vm.uiState.value.showConfirm).isFalse()
    }

    @Test
    fun `confirming without a selection hides nothing`() {
        val vm = viewModel(*sample)

        vm.confirmHide()

        assertThat(repository.hideCalls).isEmpty()
    }

    // -- Scan ----------------------------------------------------------

    @Test
    fun `scan state maps to idle, running and both pause reasons`() {
        val vm = viewModel()
        assertThat(vm.uiState.value.scan).isEqualTo(ChannelCleanupScanUi.Idle(null))

        repository.scanState.value = ChannelHealthScanState(lastFinishedAt = 5_000L)
        assertThat(vm.uiState.value.scan).isEqualTo(ChannelCleanupScanUi.Idle(5_000L))

        repository.scanState.value = ChannelHealthScanState(running = true, scanned = 12, total = 300)
        assertThat(vm.uiState.value.scan).isEqualTo(ChannelCleanupScanUi.Running(12, 300))

        repository.scanState.value = ChannelHealthScanState(
            running = true, scanned = 12, total = 300, paused = ChannelHealthPauseReason.PLAYBACK_ACTIVE,
        )
        assertThat(vm.uiState.value.scan)
            .isEqualTo(ChannelCleanupScanUi.Paused(ChannelHealthPauseReason.PLAYBACK_ACTIVE, 12, 300))

        repository.scanState.value = ChannelHealthScanState(
            running = true, scanned = 13, total = 300, paused = ChannelHealthPauseReason.PROVIDER_BACKOFF,
        )
        assertThat(vm.uiState.value.scan)
            .isEqualTo(ChannelCleanupScanUi.Paused(ChannelHealthPauseReason.PROVIDER_BACKOFF, 13, 300))
    }

    @Test
    fun `pause reasons have their own localized text`() {
        assertThat(channelCleanupPausedLabelRes(ChannelHealthPauseReason.PLAYBACK_ACTIVE))
            .isEqualTo(R.string.channel_cleanup_paused_playback)
        assertThat(channelCleanupPausedLabelRes(ChannelHealthPauseReason.PROVIDER_BACKOFF))
            .isEqualTo(R.string.channel_cleanup_paused_backoff)
    }

    @Test
    fun `start scan scans the whole provider and stop cancels it`() {
        val vm = viewModel()

        vm.startScan()
        vm.stopScan()

        assertThat(repository.startCalls).containsExactly(PROVIDER to null)
        assertThat(repository.cancelCalls).containsExactly(PROVIDER)
        assertThat(vm.uiState.value.errorRes).isNull()
    }

    @Test
    fun `any start failure shows the localized message, never the repository text`() {
        repository.startResult = Result.Error("Only Xtream Codes providers can be scanned (type=M3U)")
        val vm = viewModel()

        vm.startScan()

        assertThat(vm.uiState.value.errorRes).isEqualTo(R.string.channel_cleanup_error_unsupported)
        assertThat(channelCleanupStartErrorRes(Result.Error("boom", RuntimeException())))
            .isEqualTo(R.string.channel_cleanup_error_unsupported)
        assertThat(channelCleanupStartErrorRes(Result.Success(Unit))).isNull()
    }

    @Test
    fun `a later successful scan clears the error`() {
        repository.startResult = Result.Error("nope")
        val vm = viewModel()
        vm.startScan()

        repository.startResult = Result.Success(Unit)
        vm.startScan()

        assertThat(vm.uiState.value.errorRes).isNull()
    }

    @Test
    fun `actions before a provider is bound do nothing`() {
        val vm = ChannelCleanupViewModel(repository)

        vm.startScan()
        vm.stopScan()
        vm.confirmHide()

        assertThat(repository.startCalls).isEmpty()
        assertThat(repository.cancelCalls).isEmpty()
        assertThat(repository.hideCalls).isEmpty()
    }

    private companion object {
        const val PROVIDER = 7L
    }
}
