package com.streamvault.app

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Calls [onForeground] when the first activity starts, i.e. the app comes to the foreground.
 * Rotations and activity switches stop and restart activities, so the caller rate-limits.
 */
internal class ForegroundSyncTrigger(
    private val onForeground: () -> Unit
) : Application.ActivityLifecycleCallbacks {
    private var startedActivities = 0

    override fun onActivityStarted(activity: Activity) {
        if (startedActivities++ == 0) onForeground()
    }

    override fun onActivityStopped(activity: Activity) {
        if (startedActivities > 0) startedActivities--
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
