package com.streamvault.data.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Per-key single flight with coalescing: at most one run per key at a time, and a caller that
 * arrives while a run is in progress waits and then shares the next run with every other caller
 * that arrived meanwhile with the same arguments. Every caller gets the result of a run that
 * started after it called, so no one sees a result older than their request. A failed run is
 * never shared; the callers waiting behind it start their own.
 *
 * Keys are kept for the process lifetime, so use it for small key sets such as provider ids.
 */
class CoalescingRunner<K, A, R : Any> {
    private class Slot<A, R : Any> {
        val runMutex = Mutex()
        var requested = 0L
        var completedUpTo = 0L
        var lastArgs: A? = null
        var lastResult: R? = null
    }

    private val stateMutex = Mutex()
    private val slots = HashMap<K, Slot<A, R>>()

    suspend fun run(key: K, args: A, block: suspend (A) -> R): R {
        val (slot, ticket) = stateMutex.withLock {
            val slot = slots.getOrPut(key) { Slot() }
            slot.requested += 1
            slot to slot.requested
        }
        return slot.runMutex.withLock {
            val shared = stateMutex.withLock {
                slot.lastResult?.takeIf { slot.completedUpTo >= ticket && slot.lastArgs == args }
            }
            if (shared != null) return@withLock shared
            // Every request up to here is answered by this run, which starts after all of them.
            val upTo = stateMutex.withLock { slot.requested }
            val result = block(args)
            stateMutex.withLock {
                slot.completedUpTo = upTo
                slot.lastArgs = args
                slot.lastResult = result
            }
            result
        }
    }
}
