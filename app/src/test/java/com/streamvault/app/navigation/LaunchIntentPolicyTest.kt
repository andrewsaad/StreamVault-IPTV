package com.streamvault.app.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchIntentPolicyTest {
    @Test
    fun freshLaunchHandlesIntent() {
        assertTrue(shouldHandleLaunchIntent(hasSavedInstanceState = false))
    }

    @Test
    fun recreatedActivityDoesNotReplayLaunchIntent() {
        assertFalse(shouldHandleLaunchIntent(hasSavedInstanceState = true))
    }
}
