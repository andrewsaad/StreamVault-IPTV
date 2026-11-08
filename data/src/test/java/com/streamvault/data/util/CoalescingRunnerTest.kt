package com.streamvault.data.util

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** F13: one resolve pass at a time per provider; callers that arrive during a pass share the next one. */
@OptIn(ExperimentalCoroutinesApi::class)
class CoalescingRunnerTest {

    private val runner = CoalescingRunner<Long, String, Int>()
    private var runs = 0
    private var running = 0
    private var maxRunning = 0
    private var gate: CompletableDeferred<Unit>? = null

    private suspend fun pass(args: String): Int {
        running++
        maxRunning = maxOf(maxRunning, running)
        try {
            gate?.await()
            return ++runs
        } finally {
            running--
        }
    }

    @Test
    fun callersArrivingDuringAPass_shareOneFollowingPass() = runTest {
        gate = CompletableDeferred()
        val first = async { runner.run(1L, "a", ::pass) }
        runCurrent()
        val waiting = (1..3).map { async { runner.run(1L, "a", ::pass) } }
        runCurrent()
        gate!!.complete(Unit)

        assertThat(first.await()).isEqualTo(1)
        assertThat(waiting.awaitAll()).containsExactly(2, 2, 2)
        assertThat(runs).isEqualTo(2)
        assertThat(maxRunning).isEqualTo(1)
    }

    @Test
    fun aCallerArrivingAfterAPassFinished_getsAFreshPass() = runTest {
        assertThat(runner.run(1L, "a", ::pass)).isEqualTo(1)
        assertThat(runner.run(1L, "a", ::pass)).isEqualTo(2)
        assertThat(runs).isEqualTo(2)
    }

    @Test
    fun callersWithOtherArguments_neverShareAPass() = runTest {
        gate = CompletableDeferred()
        val first = async { runner.run(1L, "a", ::pass) }
        runCurrent()
        val sameArgs = async { runner.run(1L, "a", ::pass) }
        val otherArgs = async { runner.run(1L, "b", ::pass) }
        runCurrent()
        gate!!.complete(Unit)

        listOf(first, sameArgs, otherArgs).awaitAll()
        assertThat(runs).isEqualTo(3)
        assertThat(maxRunning).isEqualTo(1)
    }

    @Test
    fun aFailedPass_isNotSharedAndWaitingCallersRunTheirOwn() = runTest {
        var fail = true
        val failing: suspend (String) -> Int = { args ->
            if (fail) {
                fail = false
                error("boom")
            }
            pass(args)
        }

        val failed = runCatching { runner.run(1L, "a", failing) }

        assertThat(failed.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(runner.run(1L, "a", failing)).isEqualTo(1)
    }

    @Test
    fun differentKeys_runSideBySide() = runTest {
        gate = CompletableDeferred()
        val one = async { runner.run(1L, "a", ::pass) }
        val two = async { runner.run(2L, "a", ::pass) }
        runCurrent()

        assertThat(maxRunning).isEqualTo(2)
        gate!!.complete(Unit)
        listOf(one, two).awaitAll()
    }
}
