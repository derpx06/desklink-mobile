/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.compose.screen.settings

import android.content.Context
import androidx.annotation.StringRes
import org.desklink.mobile.R

/** A setting as the search sees it: the page it lives on, its title, and the line under it. */
data class SettingsEntry(val page: SettingsPage, val title: String, val summary: String? = null)

/**
 * What the Settings search looks through: every row of every page (title and summary), plus the pages themselves.
 * SettingsSearchTest checks this list against the rows SettingsTab.kt really builds, so a row added to a page but not
 * here fails there instead of being unfindable.
 */
object SettingsIndex {
    class Row(val page: SettingsPage, @StringRes val title: Int, @StringRes val summary: Int? = null)

    val rows: List<Row> = listOf(
        Row(SettingsPage.CONNECTION, R.string.settings_phone_name),
        Row(SettingsPage.CONNECTION, R.string.settings_connect_on_startup, R.string.settings_connect_on_startup_summary),
        Row(SettingsPage.CONNECTION, R.string.settings_keep_awake, R.string.settings_keep_awake_summary),
        Row(SettingsPage.CONNECTION, R.string.home_pair_another),
        Row(SettingsPage.NOTIFICATIONS, R.string.settings_phone_notifications, R.string.settings_desk_notifications_summary),
        Row(SettingsPage.NOTIFICATIONS, R.string.sg_choose_apps, R.string.sg_choose_apps_summary),
        Row(SettingsPage.NOTIFICATIONS, R.string.settings_desk_shows_notifications, R.string.settings_desk_shows_notifications_summary),
        Row(SettingsPage.NOTIFICATIONS, R.string.settings_private_notifications, R.string.settings_private_notifications_summary),
        Row(SettingsPage.NOTIFICATIONS, R.string.settings_desk_notifications_here, R.string.settings_desk_notifications_here_summary),
        Row(SettingsPage.NOTIFICATIONS, R.string.settings_command_alerts, R.string.settings_command_alerts_summary),
        Row(SettingsPage.NOTIFICATIONS, R.string.settings_activity_on, R.string.settings_activity_on_summary),
        Row(SettingsPage.NOTIFICATIONS, R.string.settings_dnd_share),
        Row(SettingsPage.NOTIFICATIONS, R.string.select_ringtone),
        Row(SettingsPage.NOTIFICATIONS, R.string.setting_persistent_notification_oreo, R.string.setting_persistent_notification_description),
        Row(SettingsPage.CLIPBOARD, R.string.settings_phone_clipboard),
        Row(SettingsPage.CLIPBOARD, R.string.settings_clipboard_offer, R.string.settings_clipboard_offer_summary),
        Row(SettingsPage.CLIPBOARD, R.string.settings_desk_clipboard, R.string.settings_desk_clipboard_summary),
        Row(SettingsPage.CLIPBOARD, R.string.home_send_clipboard),
        Row(SettingsPage.CALLS, R.string.settings_calls_on, R.string.settings_calls_on_summary),
        Row(SettingsPage.CALLS, R.string.settings_show_caller, R.string.settings_show_caller_summary),
        Row(SettingsPage.SOUND, R.string.settings_desk_sound, R.string.settings_desk_sound_summary),
        Row(SettingsPage.SOUND, R.string.settings_desk_may_play, R.string.settings_desk_may_play_summary),
        Row(SettingsPage.SOUND, R.string.settings_mic_consent, R.string.settings_mic_consent_summary),
        Row(SettingsPage.SOUND, R.string.settings_phone_media, R.string.settings_phone_media_summary),
        Row(SettingsPage.CONTROL, R.string.settings_glide),
        Row(SettingsPage.CONTROL, R.string.mousepad_sensitivity_settings_title),
        Row(SettingsPage.CONTROL, R.string.mousepad_scroll_direction_title, R.string.settings_scroll_reverse_summary),
        Row(SettingsPage.FILES, R.string.settings_bucket_with, R.string.more_bucket_summary),
        Row(SettingsPage.FILES, R.string.settings_downloads_folder),
        Row(SettingsPage.FILES, R.string.sg_files_default_folder),
        Row(SettingsPage.FILES, R.string.settings_gallery_send),
        Row(SettingsPage.FILES, R.string.settings_gallery_screenshots),
        Row(SettingsPage.FILES, R.string.settings_gallery_camera),
        Row(SettingsPage.FILES, R.string.settings_contacts_share),
        Row(SettingsPage.PRIVACY, R.string.settings_session_password),
        Row(SettingsPage.PRIVACY, R.string.settings_session_gate),
        Row(SettingsPage.PRIVACY, R.string.settings_unlock_nearby),
        Row(SettingsPage.PRIVACY, R.string.device_encryption, R.string.settings_paired_key),
        Row(SettingsPage.PRIVACY, R.string.privacy_policy, R.string.privacy_policy_summary),
        Row(SettingsPage.PRIVACY, R.string.settings_unpair_row),
        Row(SettingsPage.APPEARANCE, R.string.settings_theme),
        Row(SettingsPage.APPEARANCE, R.string.setting_dynamic_colors, R.string.settings_dynamic_colors_summary),
        Row(SettingsPage.ADVANCED, R.string.settings_phone_relay),
        Row(SettingsPage.ADVANCED, R.string.settings_desk_relay, R.string.settings_reach_anywhere_summary),
        Row(SettingsPage.ADVANCED, R.string.setting_advanced_network, R.string.setting_advanced_network_summary),
        Row(SettingsPage.ADVANCED, R.string.trusted_networks, R.string.trusted_networks_desc),
        Row(SettingsPage.ADVANCED, R.string.custom_device_list, R.string.custom_devices_settings_summary),
        Row(SettingsPage.ADVANCED, R.string.settings_permissions),
        Row(SettingsPage.ADVANCED, R.string.surfaces_add_tiles, R.string.surfaces_add_tiles_summary),
        Row(SettingsPage.ADVANCED, R.string.settings_export_logs, R.string.settings_export_logs_text),
        Row(SettingsPage.ADVANCED, R.string.about),
    )

    /** Rows and pages with their words in [deskName]'s terms (a string with no "%s" simply ignores the name). */
    fun entries(context: Context, deskName: String, pages: List<SettingsPage>): List<SettingsEntry> {
        // A line that wants a number ("%d devices added manually") has no words to search yet: it is left out.
        fun text(id: Int) = runCatching { context.getString(id, deskName) }.getOrDefault("")
        return pages.flatMap { page ->
            listOf(SettingsEntry(page, text(page.title), text(page.summary))) +
                rows.filter { it.page == page }.map { SettingsEntry(page, text(it.title), it.summary?.let(::text)?.takeIf(String::isNotEmpty)) }
        }
    }
}

object SettingsSearch {
    /** Entries whose title or line contains every word of [query] (case-insensitive), in the index order. */
    fun search(entries: List<SettingsEntry>, query: String): List<SettingsEntry> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return entries.filter { e ->
            val haystack = (e.title + " " + e.summary.orEmpty()).lowercase()
            words.all { it in haystack }
        }
    }
}
