package com.streamvault.player

/**
 * Decides whether a connectivity callback is a real network change worth resetting the quality
 * consent and decode-pressure state for. Android fires onCapabilitiesChanged for routine events
 * (DHCP lease renewal, signal strength), which must not discard the viewer's quality choice.
 */
internal class QualityNetworkChangeFilter {
    data class Signature(val transportMask: Int, val metered: Boolean, val validated: Boolean)

    private var networkId: Any? = null
    private var signature: Signature? = null

    fun onAvailable(id: Any): Boolean {
        if (id == networkId) return false
        networkId = id
        signature = null
        return true
    }

    fun onCapabilitiesChanged(id: Any, newSignature: Signature): Boolean {
        if (id != networkId) {
            networkId = id
            signature = newSignature
            return true
        }
        val previous = signature
        signature = newSignature
        // The first capabilities report right after onAvailable only fills in the baseline.
        return previous != null && previous != newSignature
    }

    fun onLost(id: Any): Boolean {
        if (id == networkId) {
            networkId = null
            signature = null
        }
        return true
    }
}
