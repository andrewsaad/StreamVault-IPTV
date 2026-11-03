package com.streamvault.feature.playback.player

import androidx.annotation.StringRes
import com.streamvault.feature.playback.R
import com.streamvault.player.diagnostics.BufferingHint

/** Suggestion shown under the buffering spinner for each likely cause. */
@StringRes
internal fun bufferingHintText(hint: BufferingHint): Int = when (hint) {
    BufferingHint.ACCOUNT_REFUSED -> R.string.player_buffering_hint_account_refused
    BufferingHint.CHANNEL_OFFLINE -> R.string.player_buffering_hint_channel_offline
    BufferingHint.SERVER_BUSY -> R.string.player_buffering_hint_server_busy
    BufferingHint.WEAK_NETWORK -> R.string.player_buffering_hint_weak_network
    BufferingHint.DEVICE_DECODE -> R.string.player_buffering_hint_device_decode
    BufferingHint.PROVIDER_SLOW -> R.string.player_buffering_hint_provider_slow
}
