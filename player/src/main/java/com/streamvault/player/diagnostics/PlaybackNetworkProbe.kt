package com.streamvault.player.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build

/**
 * Describes the active network for the playback journal. Called only from the journal's
 * writer thread, never from playback callbacks, because these are binder calls.
 * Location-sensitive Wi-Fi fields (SSID/BSSID) are never read.
 */
internal class PlaybackNetworkProbe(context: Context) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    fun describe(): String {
        val manager = connectivityManager ?: return "net=unknown"
        val capabilities = manager.activeNetwork?.let(manager::getNetworkCapabilities) ?: return "net=none"
        val downKbps = capabilities.linkDownstreamBandwidthKbps
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val wifi = wifiInfo(capabilities)
                if (wifi == null) "net=wifi downKbps=$downKbps"
                else "net=wifi rssi=${wifi.rssi} linkMbps=${wifi.linkSpeed} downKbps=$downKbps"
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "net=ethernet downKbps=$downKbps"
            else -> "net=other downKbps=$downKbps"
        }
    }

    private fun wifiInfo(capabilities: NetworkCapabilities): WifiInfo? {
        val fromCapabilities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            capabilities.transportInfo as? WifiInfo
        } else null
        @Suppress("DEPRECATION")
        return fromCapabilities ?: wifiManager?.connectionInfo
    }
}
