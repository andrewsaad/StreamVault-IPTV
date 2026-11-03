package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.player.diagnostics.BufferingHint
import org.junit.Test

class BufferingHintTextTest {
    @Test
    fun `every hint has its own suggestion text`() {
        val texts = BufferingHint.entries.map(::bufferingHintText)

        assertThat(texts).containsNoDuplicates()
        assertThat(texts).hasSize(BufferingHint.entries.size)
    }
}
