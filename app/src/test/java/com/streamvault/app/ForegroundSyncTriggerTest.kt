package com.streamvault.app

import android.app.Activity
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ForegroundSyncTriggerTest {

    @Test
    fun `fires only when the first activity starts`() {
        var calls = 0
        val trigger = ForegroundSyncTrigger { calls++ }
        val first = Robolectric.buildActivity(Activity::class.java).get()
        val second = Robolectric.buildActivity(Activity::class.java).get()

        trigger.onActivityStarted(first)
        trigger.onActivityStarted(second)
        trigger.onActivityStopped(first)
        assertThat(calls).isEqualTo(1)

        trigger.onActivityStopped(second)
        trigger.onActivityStarted(first)
        assertThat(calls).isEqualTo(2)
    }

    @Test
    fun `an unmatched stop does not go negative`() {
        var calls = 0
        val trigger = ForegroundSyncTrigger { calls++ }
        val activity = Robolectric.buildActivity(Activity::class.java).get()

        trigger.onActivityStopped(activity)
        trigger.onActivityStarted(activity)
        assertThat(calls).isEqualTo(1)
    }
}
