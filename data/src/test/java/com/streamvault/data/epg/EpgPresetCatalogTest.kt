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
    fun `catalog_addressesAreFixedHttpsFilesOnKnownHosts_notAnOnlineList`() {
        EpgPresetCatalog.all.flatMap { it.sources }.forEach { source ->
            val uri = URI(source.url)
            assertThat(uri.scheme).isEqualTo("https")
            assertThat(uri.host).isIn(setOf("epgshare01.online", "www.open-epg.com"))
            assertThat(uri.path).matches(".*\\.xml(\\.gz)?")
            assertThat(uri.query).isNull()
        }
    }

    @Test
    fun `bein_readsTheQatarFileFirst_becauseItMatchesProviderNamesWithCorrectTimes`() {
        // validation-precision.md: BEIN1 and AE1 alone map 20 of 188 beIN sports channels, with Qatar 165.
        val bein = EpgPresetCatalog.byId(EpgPresetCatalog.BEIN)!!
        assertThat(bein.sources.map { it.key }).containsExactly("QATAR1", "AE1").inOrder()
        assertThat(bein.sources.first().url).isEqualTo("https://www.open-epg.com/files/qatar1.xml")
    }

    @Test
    fun `byId_returnsThePreset_orNullForUnknownIds`() {
        assertThat(EpgPresetCatalog.byId(EpgPresetCatalog.EGYPT)?.id).isEqualTo(EpgPresetCatalog.EGYPT)
        assertThat(EpgPresetCatalog.byId("nope")).isNull()
    }
}
