package com.streamvault.data.epg

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.streamvault.data.util.UrlSecurityPolicy
import com.streamvault.domain.model.EpgPresetCatalog
import java.net.URI
import org.junit.Test

class EpgPresetCatalogTest {

    @Test
    fun `catalog_holdsTheFirstReleasePresetsInPriorityOrder`() {
        assertThat(EpgPresetCatalog.all.map { it.id })
            .containsExactly(EpgPresetCatalog.BEIN, EpgPresetCatalog.EGYPT, EpgPresetCatalog.US)
            .inOrder()
    }

    @Test
    fun `catalog_idsAreUnique`() {
        val ids = EpgPresetCatalog.all.map { it.id }
        assertThat(ids).containsNoDuplicates()
    }

    @Test
    fun `catalog_everyPresetHasAtLeastOneSource_andSourceKeysAreUniqueWithinAPreset`() {
        EpgPresetCatalog.all.forEach { preset ->
            assertThat(preset.sources).isNotEmpty()
            assertThat(preset.sources.map { it.key }).containsNoDuplicates()
        }
    }

    @Test
    fun `catalog_aSharedKeyAlwaysMeansTheSameAddress`() {
        // Presets share one source row per address, so one key must never map to two addresses.
        val urlsByKey = EpgPresetCatalog.all
            .flatMap { it.sources }
            .groupBy({ it.key }, { it.url })
            .mapValues { (_, urls) -> urls.toSet() }
        urlsByKey.forEach { (key, urls) -> assertWithMessage(key).that(urls).hasSize(1) }
        val keysByUrl = EpgPresetCatalog.all.flatMap { it.sources }.groupBy({ it.url }, { it.key })
        keysByUrl.forEach { (url, keys) -> assertWithMessage(url).that(keys.toSet()).hasSize(1) }
    }

    @Test
    fun `catalog_everyAddressPassesTheEpgUrlPolicy`() {
        EpgPresetCatalog.all.flatMap { it.sources }.forEach { source ->
            assertWithMessage(source.url).that(UrlSecurityPolicy.validateOptionalEpgUrl(source.url)).isNull()
        }
    }

    @Test
    fun `catalog_addressesAreFixedHttpsFilesOnOneHost_notAnOnlineList`() {
        EpgPresetCatalog.all.flatMap { it.sources }.forEach { source ->
            val uri = URI(source.url)
            assertThat(uri.scheme).isEqualTo("https")
            assertThat(uri.host).isEqualTo("epgshare01.online")
            assertThat(uri.path).endsWith(".xml.gz")
            assertThat(uri.query).isNull()
        }
    }

    @Test
    fun `byId_returnsThePreset_orNullForUnknownIds`() {
        assertThat(EpgPresetCatalog.byId(EpgPresetCatalog.EGYPT)?.id).isEqualTo(EpgPresetCatalog.EGYPT)
        assertThat(EpgPresetCatalog.byId("nope")).isNull()
    }
}
