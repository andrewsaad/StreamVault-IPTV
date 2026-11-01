package com.streamvault.surround

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class StereoUpmixerTest {

    @Test
    fun copiesLeftRightToFrontAndSurroundsAndSumsCentre() {
        val out = FloatArray(12)
        StereoUpmixer.upmix(floatArrayOf(0.5f, -0.25f, 1f, 1f), 2, out)
        // FL FR FC LFE SL SR per frame
        assertThat(out.toList()).containsExactly(
            0.5f, -0.25f, 0.125f, 0f, 0.5f, -0.25f,
            1f, 1f, 1f, 0f, 1f, 1f
        ).inOrder()
    }

    @Test
    fun rejectsShortBuffers() {
        assertThrows(IllegalArgumentException::class.java) { StereoUpmixer.upmix(FloatArray(2), 2, FloatArray(12)) }
        assertThrows(IllegalArgumentException::class.java) { StereoUpmixer.upmix(FloatArray(4), 2, FloatArray(11)) }
    }
}
