package com.streamvault.app.plugins

import android.content.Intent
import android.content.pm.PackageManager
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.entity.PluginProviderOwnershipEntity
import com.streamvault.feature.system.api.StreamVaultPluginComponent
import org.junit.Test

class PluginPackageBroadcastPolicyTest {

    @Test
    fun `orphan check query includes disabled components and discovery does not`() {
        val orphanCheck = pluginServiceQueryFlags(includeDisabledComponents = true)
        val discovery = pluginServiceQueryFlags(includeDisabledComponents = false)

        assertThat(orphanCheck and PackageManager.MATCH_DISABLED_COMPONENTS)
            .isEqualTo(PackageManager.MATCH_DISABLED_COMPONENTS)
        assertThat(orphanCheck and PackageManager.GET_META_DATA).isEqualTo(PackageManager.GET_META_DATA)
        assertThat(discovery and PackageManager.MATCH_DISABLED_COMPONENTS).isEqualTo(0)
        assertThat(discovery and PackageManager.GET_META_DATA).isEqualTo(PackageManager.GET_META_DATA)
    }

    @Test
    fun `removal that is part of an update does not reconcile`() {
        assertThat(shouldReconcileForPackageBroadcast(Intent.ACTION_PACKAGE_REMOVED, replacing = true)).isFalse()
    }

    @Test
    fun `real uninstall and other package changes reconcile`() {
        assertThat(shouldReconcileForPackageBroadcast(Intent.ACTION_PACKAGE_REMOVED, replacing = false)).isTrue()
        assertThat(shouldReconcileForPackageBroadcast(Intent.ACTION_PACKAGE_ADDED, replacing = true)).isTrue()
        assertThat(shouldReconcileForPackageBroadcast(Intent.ACTION_PACKAGE_REPLACED, replacing = true)).isTrue()
        assertThat(shouldReconcileForPackageBroadcast(Intent.ACTION_PACKAGE_CHANGED, replacing = false)).isTrue()
        assertThat(shouldReconcileForPackageBroadcast(null, replacing = false)).isTrue()
    }

    @Test
    fun `provider of a disabled but installed plugin is not orphaned`() {
        // The orphan check is fed the components found with MATCH_DISABLED_COMPONENTS, so a disabled
        // plugin service is in the installed set and keeps its provider.
        val disabledButInstalled = StreamVaultPluginComponent("com.example.plugin", "PluginService")
        val ownership = PluginProviderOwnershipEntity(
            packageName = disabledButInstalled.packageName,
            serviceClassName = disabledButInstalled.serviceClassName,
            manifestId = "plugin",
            providerId = 7L
        )

        assertThat(orphanedPluginOwnerships(listOf(ownership), setOf(disabledButInstalled))).isEmpty()
        assertThat(orphanedPluginOwnerships(listOf(ownership), emptySet())).containsExactly(ownership)
    }
}
