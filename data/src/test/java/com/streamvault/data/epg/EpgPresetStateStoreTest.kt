package com.streamvault.data.epg

import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class EpgPresetStateStoreTest {

    private var now = 1_000_000L

    private fun store() = testPresetStore { now }

    @Test
    fun `codec_roundTrip_keepsEveryField`() {
        val state = EpgPresetState(
            enabledPresetIds = setOf("bein", "egypt"),
            sources = mapOf(
                "AE1" to EpgPresetSourceRecord(sourceId = 7L, owned = true, assignedProviderIds = setOf(1L, 2L)),
                "BEIN1" to EpgPresetSourceRecord(sourceId = 9L, owned = false, assignedProviderIds = emptySet()),
            ),
            lastAttemptAtByKey = mapOf("AE1" to 123L, "BEIN1" to 456L, "OLDKEY" to 789L),
        )

        val decodedSources = decodePresetSources(encodePresetSources(state.sources))
        val decodedAttempts = decodePresetAttemptsByKey(encodePresetAttemptsByKey(state.lastAttemptAtByKey))

        assertThat(decodedSources).isEqualTo(state.sources)
        assertThat(decodedAttempts).isEqualTo(state.lastAttemptAtByKey)
    }

    @Test
    fun `codec_keepsHandledProviders_andOlderLinesFallBackToAssigned`() {
        val record = EpgPresetSourceRecord(7L, owned = true, assignedProviderIds = setOf(2L), handledProviderIds = setOf(1L, 2L))

        assertThat(decodePresetSources(encodePresetSources(mapOf("AE1" to record))).getValue("AE1")).isEqualTo(record)
        assertThat(decodePresetSources("AE1\t7\t1\t1,2").getValue("AE1").handledProviderIds).containsExactly(1L, 2L)
    }

    @Test
    fun `codec_ignoresMalformedLines`() {
        val sources = decodePresetSources("AE1\t7\t1\t1,2\n\tbad\nBEIN1\tx\t1\t\nUS2\t5\t0\t3,zz\n")
        assertThat(sources.keys).containsExactly("AE1", "US2")
        assertThat(sources.getValue("US2").assignedProviderIds).containsExactly(3L)

        val attempts = decodePresetAttempts("7\t100\nbad\n8\t-1\n9\t\n")
        assertThat(attempts).containsExactly(7L, 100L)

        val attemptsByKey = decodePresetAttemptsByKey("AE1\t100\nbad\nUS2\t-1\nUS3\t\n\t5\n")
        assertThat(attemptsByKey).containsExactly("AE1", 100L)
    }

    @Test
    fun `codec_keepsTheBuiltInAddress_andOlderLinesHaveNone`() {
        val record = EpgPresetSourceRecord(7L, owned = true, assignedProviderIds = setOf(1L), defaultUrl = "https://x.example/ae1.xml.gz")

        assertThat(decodePresetSources(encodePresetSources(mapOf("AE1" to record))).getValue("AE1")).isEqualTo(record)
        assertThat(decodePresetSources("AE1\t7\t1\t1\t1").getValue("AE1").defaultUrl).isNull()
    }

    @Test
    fun `codec_keepsEditedAddresses_andDropsLinesWithoutOne`() {
        val addresses = mapOf("AE1" to "https://mirror.example/ae1.xml", "US2" to "https://x.example/us2.xml?a=1&b=2")

        assertThat(decodePresetAddresses(encodePresetAddresses(addresses))).isEqualTo(addresses)
        assertThat(decodePresetAddresses("AE1\t\n\thttps://x.example/a.xml\nbad\nUS2\thttps://x.example/u.xml"))
            .containsExactly("US2", "https://x.example/u.xml")
    }

    @Test
    fun `editedAddresses_surviveInTheStore_whenThePresetIsOff`() = runTest {
        val store = store()
        store.update { it.copy(addresses = mapOf("AE1" to "https://mirror.example/ae1.xml")) }

        assertThat(store.current().enabledPresetIds).isEmpty()
        assertThat(store.current().addresses).containsExactly("AE1", "https://mirror.example/ae1.xml")
    }

    @Test
    fun `newStore_isEmpty_soEveryPresetIsOff`() = runTest {
        assertThat(store().current()).isEqualTo(EpgPresetState())
    }

    @Test
    fun `update_isVisibleOnTheStateFlow`() = runTest {
        val store = store()
        store.update { it.copy(enabledPresetIds = setOf("us")) }
        assertThat(store.current().enabledPresetIds).containsExactly("us")
    }

    @Test
    fun `gate_allowsSourcesNotOwnedByAPreset_withoutRecording`() = runTest {
        val store = store()
        assertThat(store.tryBeginRefreshAttempt(42L)).isTrue()
        assertThat(store.tryBeginRefreshAttempt(42L)).isTrue()
        assertThat(store.current().lastAttemptAtBySourceId).isEmpty()
    }

    @Test
    fun `gate_ownedSource_refusesInsideTheGapAndAllowsAfterIt`() = runTest {
        val store = store()
        store.update {
            it.copy(sources = mapOf("AE1" to EpgPresetSourceRecord(7L, owned = true, assignedProviderIds = emptySet())))
        }

        assertThat(store.tryBeginRefreshAttempt(7L)).isTrue()
        now += EpgPresetStateStore.MIN_ATTEMPT_GAP_MILLIS - 1
        assertThat(store.tryBeginRefreshAttempt(7L)).isFalse()
        now += 1
        assertThat(store.tryBeginRefreshAttempt(7L)).isTrue()
        assertThat(store.current().lastAttemptAtBySourceId).containsExactly(7L, now)
    }

    @Test
    fun `gate_attemptsSurviveTheRowBeingDeletedAndRecreated`() = runTest {
        val store = store()
        store.update { it.copy(sources = mapOf("AE1" to EpgPresetSourceRecord(7L, owned = true, assignedProviderIds = emptySet()))) }
        assertThat(store.tryBeginRefreshAttempt(7L)).isTrue()

        // Turned off, then on again: the row is deleted and recreated under a new id (F18).
        store.update { it.copy(sources = emptyMap()) }
        store.update { it.copy(sources = mapOf("AE1" to EpgPresetSourceRecord(99L, owned = true, assignedProviderIds = emptySet()))) }

        now += 60L * 60L * 1000L
        assertThat(store.tryBeginRefreshAttempt(99L)).isFalse()
        now += EpgPresetStateStore.MIN_ATTEMPT_GAP_MILLIS
        assertThat(store.tryBeginRefreshAttempt(99L)).isTrue()
    }

    @Test
    fun `gate_clearingAnAttempt_forgetsItByFileKey`() = runTest {
        val store = store()
        store.update { it.copy(sources = mapOf("AE1" to EpgPresetSourceRecord(7L, owned = true, assignedProviderIds = emptySet()))) }
        store.tryBeginRefreshAttempt(7L)

        store.clearRefreshAttempt(7L)

        assertThat(store.current().lastAttemptAtByKey).isEmpty()
        assertThat(store.tryBeginRefreshAttempt(7L)).isTrue()
    }

    @Test
    fun `attemptsSavedByRowIdBeforeF18_moveToTheFileKeyOfTheirRow_andDeadRowsAreDropped`() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[stringPreferencesKey("sources")] = "AE1\t7\t1\t1\t1\t"
                // Row 7 is in use; row 8 belongs to nothing any more.
                this[stringPreferencesKey("attempts")] = "7\t${now - 1000L}\n8\t${now - 2000L}"
            }
        }
        val store = EpgPresetStateStore(dataStore, { now })

        assertThat(store.current().lastAttemptAtByKey).containsExactly("AE1", now - 1000L)
        // Still inside the gap, so the migrated attempt counts.
        assertThat(store.tryBeginRefreshAttempt(7L)).isFalse()
        // The next write leaves only the new format behind.
        store.update { it.copy(enabledPresetIds = setOf("egypt")) }
        assertThat(store.current().lastAttemptAtByKey).containsExactly("AE1", now - 1000L)
        assertThat(dataStore.data.first()[stringPreferencesKey("attempts")]).isNull()
    }
}
