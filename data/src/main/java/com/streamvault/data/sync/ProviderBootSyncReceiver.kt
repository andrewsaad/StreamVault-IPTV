package com.streamvault.data.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Queues one delayed provider check after power-on or an app update. */
class ProviderBootSyncReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> ProviderSyncWorker.enqueueBootSync(context)
        }
    }
}
