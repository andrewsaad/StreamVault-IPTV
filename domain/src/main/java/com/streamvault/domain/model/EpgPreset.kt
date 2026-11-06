package com.streamvault.domain.model

/** One guide file a preset downloads. [key] is stable across app versions; [url] may be edited by the user. */
data class EpgPresetSource(
    val key: String,
    val url: String,
)

/**
 * A built-in, opt-in guide preset. Display text is resolved by the UI from [id], so this
 * model carries no user-facing strings. Every preset is off until the user turns it on.
 */
data class EpgPreset(
    val id: String,
    val sources: List<EpgPresetSource>,
)

/** First-release presets. Addresses are public per-country XMLTV files; terms of use are unclear. */
object EpgPresetCatalog {
    const val BEIN = "bein"
    const val EGYPT = "egypt"
    const val US = "us"

    private const val BASE = "https://epgshare01.online/epgshare01/epg_ripper_"

    private fun source(code: String) = EpgPresetSource(key = code, url = "$BASE$code.xml.gz")

    val all: List<EpgPreset> = listOf(
        EpgPreset(BEIN, listOf(source("BEIN1"), source("AE1"))),
        EpgPreset(EGYPT, listOf(source("AE1"))),
        EpgPreset(US, listOf(source("US2"), source("US_SPORTS1"))),
    )

    fun byId(id: String): EpgPreset? = all.firstOrNull { it.id == id }
}
