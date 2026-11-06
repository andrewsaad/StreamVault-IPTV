package com.streamvault.feature.settings

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Element

/**
 * Guards FR-021 / SC-009: every EPG preset string must exist in the default resources and in
 * every locale folder of this module, with the same format placeholders, and every locale the
 * app advertises must have a matching values folder here.
 */
class SettingsStringsLocaleParityTest {

    private val resDir = File("src/main/res")
    private val localesConfig = File("../../app/src/main/res/xml/locales_config.xml")

    private val epgPresetKeys = listOf(
        "settings_epg_presets_title",
        "settings_epg_presets_description",
        "epg_preset_bein_name",
        "epg_preset_bein_coverage",
        "epg_preset_egypt_name",
        "epg_preset_egypt_coverage",
        "epg_preset_us_name",
        "epg_preset_us_coverage",
        "epg_preset_status_off",
        "epg_preset_status_updated",
        "epg_preset_status_refreshing",
        "epg_preset_status_error",
        "epg_preset_refresh_now",
        "epg_preset_edit_address",
        "epg_preset_terms_note"
    )

    @Test
    fun everyAdvertisedLocaleHasValuesFolder() {
        val missing = advertisedLocales()
            .filter { it != "en" }
            .map { folderFor(it) }
            .filterNot { File(resDir, "$it/strings.xml").isFile }
        assertWithMessage("Locale folders missing strings.xml in feature/settings")
            .that(missing).isEmpty()
    }

    @Test
    fun epgPresetKeysExistInDefaultResources() {
        val defaults = readStrings(File(resDir, "values/strings.xml"))
        val missing = epgPresetKeys.filterNot { defaults[it]?.isNotBlank() == true }
        assertWithMessage("Keys missing from values/strings.xml").that(missing).isEmpty()
    }

    @Test
    fun epgPresetKeysExistInEveryLocaleWithMatchingPlaceholders() {
        val defaults = readStrings(File(resDir, "values/strings.xml"))
        val problems = mutableListOf<String>()
        localeFolders().forEach { folder ->
            val strings = readStrings(File(folder, "strings.xml"))
            epgPresetKeys.forEach { key ->
                val value = strings[key]
                if (value.isNullOrBlank()) {
                    problems += "${folder.name}: missing $key"
                    return@forEach
                }
                val expected = defaults[key]?.let(::placeholders)
                if (expected != null && placeholders(value) != expected) {
                    problems += "${folder.name}: placeholder mismatch in $key " +
                        "(expected $expected, found ${placeholders(value)})"
                }
            }
        }
        assertWithMessage("EPG preset locale parity problems").that(problems).isEmpty()
    }

    private fun advertisedLocales(): List<String> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(localesConfig)
        val nodes = doc.getElementsByTagName("locale")
        return (0 until nodes.length).map {
            (nodes.item(it) as Element)
                .getAttributeNS("http://schemas.android.com/apk/res/android", "name")
        }
    }

    private fun folderFor(locale: String): String {
        val parts = locale.split('-')
        return if (parts.size == 2) "values-${parts[0]}-r${parts[1]}" else "values-$locale"
    }

    private fun localeFolders(): List<File> =
        resDir.listFiles { f -> f.isDirectory && f.name.startsWith("values-") }
            .orEmpty()
            .filter { File(it, "strings.xml").isFile }
            .sortedBy { it.name }

    private fun readStrings(file: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate {
            val element = nodes.item(it) as Element
            element.getAttribute("name") to element.textContent
        }
    }

    private fun placeholders(value: String): List<String> =
        PLACEHOLDER.findAll(value).map { it.value }.sorted().toList()

    private companion object {
        val PLACEHOLDER = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdfxXoc]""")
    }
}
