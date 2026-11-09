package com.streamvault.feature.settings

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Element

/**
 * Guards FR-021 / SC-009: every EPG preset and channel clean-up string must exist in the default resources and in
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
        "epg_preset_terms_note",
        "epg_preset_error_unknown",
        "epg_preset_error_not_enabled",
        "epg_preset_error_refresh_too_soon",
        "epg_preset_error_source_unavailable",
        "epg_preset_error_invalid_address",
        "epg_source_error_empty_payload",
        "epg_source_refresh_skipped",
        "epg_source_refreshed",
        "epg_error_http_status",
        "settings_epg_timezone_refresh_failed",
        "epg_timezone_updated",
        "epg_error_load_assignments_failed",
        "epg_error_add_source_failed",
        "epg_error_update_timezone_failed",
        "epg_error_delete_source_failed",
        "epg_error_update_source_failed",
        "epg_error_refresh_source_failed",
        "epg_error_assign_source_failed",
        "epg_error_remove_source_failed",
        "epg_error_reorder_source_failed"
    )

    private val channelCleanupKeys = listOf(
        "settings_channel_cleanup_title",
        "settings_channel_cleanup_description",
        "channel_cleanup_scan_note",
        "channel_cleanup_scan_button",
        "channel_cleanup_stop_button",
        "channel_cleanup_last_finished",
        "channel_cleanup_never_scanned",
        "channel_cleanup_progress",
        "channel_cleanup_paused_playback",
        "channel_cleanup_paused_backoff",
        "channel_cleanup_gave_up",
        "channel_cleanup_suggestions_title",
        "channel_cleanup_empty",
        "channel_cleanup_select_all",
        "channel_cleanup_select_category",
        "channel_cleanup_uncategorized",
        "channel_cleanup_status_empty",
        "channel_cleanup_status_dead",
        "channel_cleanup_failed_days",
        "channel_cleanup_row_summary",
        "channel_cleanup_hide_selected",
        "channel_cleanup_confirm_title",
        "channel_cleanup_confirm_message",
        "channel_cleanup_confirm_button",
        "channel_cleanup_error_unsupported",
        "channel_cleanup_close"
    )

    private val userMessageKeys = listOf(
        "settings_history_clear_failed",
        "settings_category_unhidden",
        "settings_xtream_text_decoding_changed",
        "settings_provider_activate_not_found",
        "settings_provider_activate_failed",
        "settings_provider_connected",
        "settings_combined_activate_not_found",
        "settings_combined_activate_needs_playlist",
        "settings_combined_activate_needs_enabled",
        "settings_combined_activated",
        "settings_combined_activate_failed",
        "settings_combined_remove_last_active",
        "settings_combined_disable_last_active"
    )

    /** Actions and diagnostics that used to show English text: Drive, backup, update, combined source, provider, guide. */
    private val actionMessageKeys = listOf(
        "settings_drive_sign_in_failed",
        "settings_drive_signed_in_as",
        "settings_drive_signed_in",
        "settings_drive_signed_out",
        "settings_drive_sign_out_failed",
        "settings_drive_sign_in_required",
        "settings_drive_push_failed",
        "settings_drive_push_done",
        "settings_drive_pull_failed",
        "settings_drive_no_backups",
        "settings_drive_manage_failed",
        "settings_drive_backup_deleted",
        "settings_drive_delete_failed",
        "settings_drive_import_failed",
        "settings_backup_export_failed",
        "settings_backup_exported",
        "settings_backup_import_failed",
        "settings_backup_select_section",
        "settings_backup_imported",
        "settings_update_check_failed",
        "settings_update_download_failed",
        "settings_update_install_failed",
        "settings_combined_created",
        "settings_combined_deleted",
        "settings_combined_renamed",
        "settings_combined_playlist_added",
        "settings_combined_playlist_removed",
        "settings_combined_order_updated",
        "settings_combined_playlist_enabled",
        "settings_combined_playlist_disabled",
        "settings_combined_update_failed",
        "settings_provider_setting_save_failed",
        "settings_provider_m3u_vod_enabled",
        "settings_provider_m3u_vod_disabled",
        "settings_provider_guide_source_save_failed",
        "settings_provider_guide_source_updated",
        "settings_provider_logo_source_save_failed",
        "settings_provider_logo_source_updated",
        "settings_provider_delete_preparing",
        "settings_provider_delete_cleanup_pending",
        "settings_provider_deleted",
        "settings_provider_delete_failed",
        "settings_epg_preset_failed",
        "settings_provider_expiry_unknown",
        "settings_provider_expiry_none",
        "settings_provider_expiry_expired",
        "settings_provider_expiry_active_until",
        "settings_speed_test_error_network_service",
        "settings_speed_test_error_no_network",
        "settings_speed_test_error_inspect_network",
        "settings_speed_test_error_generic",
        "epg_error_generic",
        "settings_restore_sync_progress",
        "settings_restore_sync_success",
        "settings_restore_sync_failures"
    )

    private val localizedKeys = epgPresetKeys + channelCleanupKeys + userMessageKeys + actionMessageKeys

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
        val missing = localizedKeys.filterNot { defaults[it]?.isNotBlank() == true }
        assertWithMessage("Keys missing from values/strings.xml").that(missing).isEmpty()
    }

    @Test
    fun epgPresetKeysExistInEveryLocaleWithMatchingPlaceholders() {
        val defaults = readStrings(File(resDir, "values/strings.xml"))
        val problems = mutableListOf<String>()
        localeFolders().forEach { folder ->
            val strings = readStrings(File(folder, "strings.xml"))
            localizedKeys.forEach { key ->
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
