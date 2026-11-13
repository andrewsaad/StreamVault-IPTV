package com.streamvault.player.playback

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import com.streamvault.player.R
import java.util.Locale

internal fun liveStartupTimeoutMessage(context: Context): String =
    appLocaleString(context, R.string.player_error_live_startup_timeout)

internal fun liveTimeshiftStillBufferingMessage(context: Context): String =
    appLocaleString(context, R.string.player_live_timeshift_still_buffering)

private fun appLocaleString(context: Context, @StringRes resId: Int): String {
    // App language selection sets the process locale. The injected application context
    // can still carry the device's configuration rather than the UI's localized wrapper.
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocale(Locale.getDefault())
    return context.createConfigurationContext(configuration).getString(resId)
}
