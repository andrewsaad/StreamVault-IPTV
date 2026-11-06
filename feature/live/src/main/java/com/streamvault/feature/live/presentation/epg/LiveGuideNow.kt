package com.streamvault.feature.live.presentation.epg

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.delay

// The local holds a stable State object, so providing it never invalidates the subtree; only
// composables that actually read the value recompose on each tick.
private val LocalLiveGuideNowState = staticCompositionLocalOf<State<Long>> {
    object : State<Long> {
        override val value: Long get() = 0L
    }
}

@Composable
fun rememberLiveGuideNowState(): State<Long> {
    val state = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            state.longValue = System.currentTimeMillis()
            delay(30_000L)
        }
    }
    return state
}

@Composable
fun rememberLiveGuideNow(): Long {
    val state by rememberLiveGuideNowState()
    return state
}

@Composable
fun LiveGuideNowProvider(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLiveGuideNowState provides rememberLiveGuideNowState()) {
        content()
    }
}

/** Reads the current guide time; the calling composable recomposes on every tick. */
@Composable
fun currentLiveGuideNow(): Long = LocalLiveGuideNowState.current.value

/** Returns the tick state without reading it, so callers can read lazily or via derivedStateOf. */
@Composable
fun currentLiveGuideNowState(): State<Long> = LocalLiveGuideNowState.current
