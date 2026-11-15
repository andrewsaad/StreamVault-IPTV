package com.streamvault.data.health

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ChannelHealthScanState
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ScanProgressRunnerTest {
    private val running = ChannelHealthScanState(running = true, scanned = 5, total = 10)

    @Test
    fun `a refused foreground start falls back to a plain run and the scan still succeeds`() = runTest {
        val promoted = mutableListOf<Pair<Int, Int>>()
        var collected = false
        val states = flow<ChannelHealthScanState> { collected = true; emit(running) }

        val ok = runScanWithProgress(
            promote = { scanned, total -> promoted += scanned to total; false },
            states = states,
            now = { 0L },
            scan = { true },
        )

        assertThat(ok).isTrue()
        assertThat(promoted).containsExactly(0 to 0)
        assertThat(collected).isFalse()
    }

    @Test
    fun `a throwing progress collector does not abort the scan`() = runTest {
        val states: Flow<ChannelHealthScanState> = flow { throw IOException("display broke") }
        var scanned = false

        val ok = runScanWithProgress(
            promote = { _, _ -> true },
            states = states,
            now = { 0L },
            scan = { scanned = true; true },
        )

        assertThat(scanned).isTrue()
        assertThat(ok).isTrue()
    }

    @Test
    fun `a scan result of false is passed through`() = runTest {
        val ok = runScanWithProgress({ _, _ -> true }, flowOf(running), { 0L }, { false })
        assertThat(ok).isFalse()
    }

    @Test
    fun `the progress coroutine is cancelled when the scan ends`() = runTest {
        val collecting = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val states = flow<ChannelHealthScanState> {
            collecting.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }

        val ok = runScanWithProgress(
            promote = { _, _ -> true },
            states = states,
            now = { 0L },
            scan = { collecting.await(); true },
        )

        assertThat(ok).isTrue()
        assertThat(cancelled.isCompleted).isTrue()
    }
}
