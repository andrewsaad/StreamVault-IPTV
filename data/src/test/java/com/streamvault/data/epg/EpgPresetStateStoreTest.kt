package com.streamvault.data.epg

import com.google.common.truth.Truth.assertThat
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
            lastAttemptAtBySourceId = mapOf(7L to 123L, 9L to 456L),
        )

        val decodedSources = decodePresetSources(encodePresetSources(state.sources))
        val decodedAttempts = decodePresetAttempts(encodePresetAttempts(state.lastAttemptAtBySourceId))

        assertThat(decodedSources).isEqualTo(state.sources)
        assertThat(decodedAttempts).isEqualTo(state.lastAttemptAtBySourceId)
    }

    @Test
    fun `codec_ignoresMalformedLines`() {
        val sources = decodePresetSources("AE1\t7\t1\t1,2\n\tbad\nBEIN1\tx\t1\t\nUS2\t5\t0\t3,zz\n")
        assertThat(sources.keys).containsExactly("AE1", "US2")
        assertThat(sources.getValue("US2").assignedProviderIds).containsExactly(3L)

        val attempts = decodePresetAttempts("7\t100\nbad\n8\t-1\n9\t\n")
        assertThat(attempts).containsExactly(7L, 100L)
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
}
