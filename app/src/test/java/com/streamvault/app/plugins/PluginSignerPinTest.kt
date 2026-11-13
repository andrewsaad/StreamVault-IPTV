package com.streamvault.app.plugins

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PluginSignerPinTest {

    private val pluginKey = PluginSignerPin.sha256Hex("plugin-author-key".toByteArray())
    private val rotatedKey = PluginSignerPin.sha256Hex("plugin-author-rotated-key".toByteArray())
    private val impostorKey = PluginSignerPin.sha256Hex("impostor-key".toByteArray())

    @Test
    fun `same signer as the pin is trusted`() {
        assertThat(PluginSignerPin.decide(setOf(pluginKey), PluginSigners(setOf(pluginKey))))
            .isEqualTo(PluginSignerDecision.TRUSTED)
    }

    @Test
    fun `replacement app under the same package name is not trusted`() {
        assertThat(PluginSignerPin.decide(setOf(pluginKey), PluginSigners(setOf(impostorKey))))
            .isEqualTo(PluginSignerDecision.UNTRUSTED)
    }

    @Test
    fun `key rotation that names the pinned key in its lineage is trusted`() {
        val rotated = PluginSigners(current = setOf(rotatedKey), past = setOf(pluginKey, rotatedKey))

        assertThat(PluginSignerPin.decide(setOf(pluginKey), rotated)).isEqualTo(PluginSignerDecision.TRUSTED)
    }

    @Test
    fun `enabled plugin without a pin is pinned on first sight`() {
        assertThat(PluginSignerPin.decide(emptySet(), PluginSigners(setOf(pluginKey))))
            .isEqualTo(PluginSignerDecision.PIN_CURRENT)
    }

    @Test
    fun `unreadable signer fails closed`() {
        assertThat(PluginSignerPin.decide(setOf(pluginKey), null)).isEqualTo(PluginSignerDecision.UNTRUSTED)
        assertThat(PluginSignerPin.decide(emptySet(), null)).isEqualTo(PluginSignerDecision.UNTRUSTED)
        assertThat(PluginSignerPin.decide(setOf(pluginKey), PluginSigners(emptySet())))
            .isEqualTo(PluginSignerDecision.UNTRUSTED)
    }

    @Test
    fun `every pinned signer must still be present`() {
        assertThat(PluginSignerPin.decide(setOf(pluginKey, rotatedKey), PluginSigners(setOf(pluginKey))))
            .isEqualTo(PluginSignerDecision.UNTRUSTED)
    }

    @Test
    fun `pinning saves the current signers only`() {
        val saved = mutableListOf<String>()
        val signers = PluginSigners(current = setOf(rotatedKey), past = setOf(pluginKey, rotatedKey))

        assertThat(PluginSignerPin.pin(signers) { saved += it; true }).isTrue()
        assertThat(saved).containsExactly(PluginSignerPin.encode(setOf(rotatedKey)))
    }

    @Test
    fun `pinning fails when the signer is unreadable or the save fails`() {
        val saved = mutableListOf<String>()

        assertThat(PluginSignerPin.pin(null) { saved += it; true }).isFalse()
        assertThat(PluginSignerPin.pin(PluginSigners(emptySet())) { saved += it; true }).isFalse()
        assertThat(saved).isEmpty()
        assertThat(PluginSignerPin.pin(PluginSigners(setOf(pluginKey))) { false }).isFalse()
    }

    @Test
    fun `pin round-trips through its stored form`() {
        val stored = PluginSignerPin.encode(setOf(rotatedKey, pluginKey))

        assertThat(PluginSignerPin.decode(stored)).containsExactly(pluginKey, rotatedKey)
        assertThat(PluginSignerPin.decode(null)).isEmpty()
        assertThat(PluginSignerPin.decode("")).isEmpty()
    }
}
