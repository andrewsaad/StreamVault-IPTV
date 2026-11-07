package com.streamvault.data.epg

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * File-free DataStore for JVM tests. The file-backed one cannot replace its file on a Windows
 * host while it is open, which is a host limitation, not app behavior on Android.
 */
internal class InMemoryPreferencesDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    private val mutex = Mutex()

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        mutex.withLock { transform(state.value).also { state.value = it } }
}

/** A preset store for one simulated device. */
internal fun testPresetStore(clock: () -> Long): EpgPresetStateStore =
    EpgPresetStateStore(InMemoryPreferencesDataStore(), clock)
