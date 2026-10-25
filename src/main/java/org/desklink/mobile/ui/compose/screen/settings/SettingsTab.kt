/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.compose.screen.settings

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.apache.commons.io.IOUtils
import org.desklink.mobile.BackgroundService
import org.desklink.mobile.BuildConfig
import org.desklink.mobile.R
import org.desklink.mobile.backends.relay.RelaySettings
import org.desklink.mobile.helpers.CreateFileParams
import org.desklink.mobile.helpers.CreateFileResultContract
import org.desklink.mobile.helpers.DeviceHelper
import org.desklink.mobile.helpers.DeviceHelper.filterInvalidCharactersFromDeviceName
import org.desklink.mobile.helpers.NotificationHelper
import org.desklink.mobile.plugins.desksettings.DeskSettingsPlugin
import org.desklink.mobile.plugins.mousereceiver.AccessibilitySetupActivity
import org.desklink.mobile.ui.CustomDevicesActivity
import org.desklink.mobile.ui.ThemeUtil
import org.desklink.mobile.ui.TrustedNetworksActivity
import org.desklink.mobile.ui.compose.MonoFamily
import org.desklink.mobile.ui.compose.components.GroupRow
import org.desklink.mobile.ui.compose.components.LaptopGlyph
import org.desklink.mobile.ui.compose.components.PillButton
import org.desklink.mobile.ui.compose.components.PillStyle
import org.desklink.mobile.ui.compose.components.RowGroup
import org.desklink.mobile.ui.compose.components.SectionLabel
import org.desklink.mobile.ui.compose.tokens
import java.io.InputStreamReader

/** The ten groups, in the desk's order, and the two pages under Advanced. */
enum class SettingsPage(val route: String, @StringRes val title: Int, @StringRes val summary: Int, @DrawableRes val icon: Int) {
    CONNECTION("connection", R.string.settings_category_connection, R.string.sg_connection_summary, R.drawable.sym_public),
    NOTIFICATIONS("notifications", R.string.settings_category_notifications, R.string.sg_notifications_summary, R.drawable.sym_notifications),
    CLIPBOARD("clipboard", R.string.sg_clipboard, R.string.sg_clipboard_summary, R.drawable.sym_content_paste),
    CALLS("calls", R.string.sg_calls, R.string.sg_calls_summary, R.drawable.sym_call),
    SOUND("sound", R.string.sg_sound, R.string.sg_sound_summary, R.drawable.sym_volume_up),
    CONTROL("control", R.string.sg_control, R.string.sg_control_summary, R.drawable.sym_mouse),
    FILES("files", R.string.sg_files, R.string.sg_files_summary, R.drawable.sym_folder_zip),
    PRIVACY("privacy", R.string.sg_privacy, R.string.sg_privacy_summary, R.drawable.sym_fingerprint),
    APPEARANCE("appearance", R.string.sg_appearance, R.string.sg_appearance_summary, R.drawable.sym_palette),
    ADVANCED("advanced", R.string.sg_advanced, R.string.sg_advanced_summary, R.drawable.sym_tune),
    ;

    companion object {
        const val NETWORK = "advanced/network"
        const val PERMISSIONS = "advanced/permissions"
        const val ABOUT = "advanced/about"
        fun of(route: String?): SettingsPage? = entries.firstOrNull { it.route == route }

        /** The pages that exist whether or not a computer is paired. */
        val WITHOUT_COMPUTER = listOf(CONNECTION, APPEARANCE, ADVANCED)

        /** The group a route belongs to ("advanced/network" is under Advanced). */
        fun groupOf(route: String): SettingsPage? = of(route.substringBefore('/'))
    }
}

/**
 * The Settings tab: a list of ten groups (the desk's own order), each its own page on the tab's back
 * stack. [route] is the page showing (null: the list). Only options people change are here; the rest
 * are fixed defaults.
 */
@Composable
fun SettingsTab(
    desk: DeskSettings?,
    actions: SettingsActions,
    route: String?,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    waitingPermissions: Int,
    about: @Composable () -> Unit,
) {
    val group = route?.let(SettingsPage::groupOf)
    when {
        route == null -> SettingsList(desk, onOpen, waitingPermissions)
        route == SettingsPage.NETWORK -> NetworkScreen(onBack)
        route == SettingsPage.PERMISSIONS -> SettingsColumn(stringResource(R.string.settings_permissions), onBack) { PermissionsSection(desk) }
        route == SettingsPage.ABOUT -> about()
        group != null -> SettingsGroupPage(group, desk, actions, onOpen, onBack)
        else -> SettingsList(desk, onOpen, waitingPermissions)
    }
}

@Composable
private fun SettingsList(desk: DeskSettings?, onOpen: (String) -> Unit, waitingPermissions: Int) {
    val context = LocalContext.current
    val t = tokens
    val pages = if (desk == null) SettingsPage.WITHOUT_COMPUTER else SettingsPage.entries
    SettingsColumn(stringResource(R.string.settings), null) {
        if (desk != null) {
            RowGroup {
                Row(
                    Modifier.fillMaxWidth().background(t.surface).clickable { onOpen(SettingsPage.CONNECTION.route) }.heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    LaptopGlyph(t.textDim)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(desk.name, fontFamily = MonoFamily, fontSize = 15.sp)
                        Text(stringResource(R.string.settings_paired_key, desk.key), fontSize = 13.sp, color = t.textDim)
                    }
                    Chevron()
                }
            }
        }
        RowGroup {
            pages.forEach { page ->
                val waiting = page == SettingsPage.ADVANCED && waitingPermissions > 0
                GroupRow(
                    stringResource(page.title),
                    icon = page.icon,
                    subtitle = if (waiting) androidx.compose.ui.res.pluralStringResource(R.plurals.settings_permissions_waiting, waitingPermissions, waitingPermissions) else stringResource(page.summary, desk?.name ?: stringResource(R.string.settings_your_computer)),
                    onClick = { onOpen(page.route) },
                ) { Chevron() }
            }
        }
        @Suppress("UNUSED_EXPRESSION") context
    }
}

@Composable
private fun SettingsGroupPage(page: SettingsPage, desk: DeskSettings?, actions: SettingsActions, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    @Suppress("UNUSED_VARIABLE") val resumed = rememberResumeTick()
    var revision by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_EXPRESSION") revision
    SettingsColumn(stringResource(page.title), onBack) {
        when (page) {
            SettingsPage.CONNECTION -> ConnectionGroup(desk, actions, prefs) { revision++ }
            SettingsPage.NOTIFICATIONS -> NotificationsGroup(desk, actions, prefs) { revision++ }
            SettingsPage.CLIPBOARD -> ClipboardGroup(desk, actions, prefs) { revision++ }
            SettingsPage.CALLS -> CallsGroup(desk, actions)
            SettingsPage.SOUND -> SoundGroup(desk, actions)
            SettingsPage.CONTROL -> ControlGroup(desk, actions, prefs) { revision++ }
            SettingsPage.FILES -> FilesGroup(desk, actions, prefs) { revision++ }
            SettingsPage.PRIVACY -> PrivacyGroup(desk, actions, prefs) { revision++ }
            SettingsPage.APPEARANCE -> AppearanceGroup(actions, prefs) { revision++ }
            SettingsPage.ADVANCED -> AdvancedGroup(desk, actions, prefs, onOpen) { revision++ }
        }
    }
}

// ---- shared pieces ------------------------------------------------------------------------------------------

/** A desk-owned switch: asks the desk, shows "Saving…" until its report, "Waiting for <desk>…" without the link. */
@Composable
private fun DeskSwitch(desk: DeskSettings, actions: SettingsActions, key: String, title: String, icon: Int, summary: String, reported: Boolean) {
    val remote = desk.remote
    val live = desk.ready && remote != null
    val pending = key in desk.pending
    val on = DeskSettingsPlugin.shown(key, reported, desk.pending)
    GroupRow(
        title,
        icon = icon,
        subtitle = when {
            !live -> stringResource(R.string.settings_waiting_for, desk.name)
            pending -> stringResource(R.string.settings_saving)
            else -> summary
        },
        checked = on,
        enabled = live && !pending,
        onClick = { actions.onDeskSetting(key, !on) },
    )
}

/** A feature is on but Android has not granted what it needs: say so where the feature is, with one button. */
@Composable
private fun AllowRow(title: String, summary: String, onAllow: () -> Unit) {
    GroupRow(title, icon = R.drawable.sym_info, subtitle = summary) {
        PillButton(stringResource(R.string.settings_allow), onAllow, style = PillStyle.Tonal, height = 34.dp, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun NoComputer() {
    Text(stringResource(R.string.sg_needs_computer), fontSize = 14.sp, lineHeight = 20.sp, color = tokens.textDim, modifier = Modifier.padding(horizontal = 6.dp))
}

private fun granted(context: android.content.Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

// ---- groups ---------------------------------------------------------------------------------------------------

@Composable
private fun ConnectionGroup(desk: DeskSettings?, actions: SettingsActions, prefs: android.content.SharedPreferences, changed: () -> Unit) {
    val context = LocalContext.current
    val t = tokens
    var editingName by rememberSaveable { mutableStateOf(false) }
    RowGroup {
        GroupRow(
            stringResource(R.string.settings_phone_name),
            icon = R.drawable.sym_badge,
            subtitle = DeviceHelper.getDeviceName(context),
            monoSubtitle = true,
            onClick = { editingName = true },
        ) { Icon(painterResource(R.drawable.sym_edit), contentDescription = null, tint = t.textDim, modifier = Modifier.size(20.dp)) }
        GroupRow(
            stringResource(R.string.settings_connect_on_startup),
            icon = R.drawable.sym_restart_alt,
            subtitle = stringResource(R.string.settings_connect_on_startup_summary),
            checked = prefs.getBoolean(KEY_CONNECT_ON_STARTUP, true),
            onClick = { prefs.edit { putBoolean(KEY_CONNECT_ON_STARTUP, !prefs.getBoolean(KEY_CONNECT_ON_STARTUP, true)) }; changed() },
        )
    }
    RowGroup {
        desk?.let {
            Row(
                Modifier.fillMaxWidth().background(t.surface).clickable(onClick = actions.onEncryptionInfo).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                LaptopGlyph(t.textDim)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(it.name, fontFamily = MonoFamily, fontSize = 15.sp)
                    Text(stringResource(R.string.settings_paired_key, it.key), fontSize = 13.sp, color = t.textDim)
                }
            }
        }
        GroupRow(stringResource(R.string.home_pair_another), icon = R.drawable.sym_add, onClick = actions.onPairAnother) { Chevron() }
    }
    val invalidName = stringResource(R.string.invalid_device_name)
    if (editingName) {
        TextDialog(
            title = stringResource(R.string.device_rename_title),
            initial = DeviceHelper.getDeviceName(context),
            confirm = stringResource(R.string.device_rename_confirm),
            filter = { filterInvalidCharactersFromDeviceName(it).take(DeviceHelper.MAX_DEVICE_NAME_LENGTH) },
            onDismiss = { editingName = false },
        ) { name ->
            editingName = false
            if (name.isBlank()) {
                actions.onMessage(invalidName)
            } else {
                // MainActivity listens for this key and announces the new name.
                prefs.edit { putString(DeviceHelper.KEY_DEVICE_NAME_PREFERENCE, name) }
                changed()
            }
        }
    }
}

@Composable
private fun NotificationsGroup(desk: DeskSettings?, actions: SettingsActions, prefs: android.content.SharedPreferences, changed: () -> Unit) {
    val context = LocalContext.current
    if (desk == null) return NoComputer()
    val keys = DeskSettingsPlugin
    val ringKey = stringResource(R.string.findmyphone_preference_key_ringtone)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = androidx.core.content.IntentCompat.getParcelableExtra(result.data ?: return@rememberLauncherForActivityResult, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            if (uri != null) {
                prefs.edit { putString(ringKey, uri.toString()) }
                changed()
            }
        }
    }
    val ringtone = prefs.getString(ringKey, null)?.let(Uri::parse) ?: Settings.System.DEFAULT_RINGTONE_URI
    val ringtoneTitle = runCatching { RingtoneManager.getRingtone(context, ringtone)?.getTitle(context) }.getOrNull().orEmpty()
    SectionLabel(stringResource(R.string.sg_notifications_phone, desk.name))
    RowGroup {
        GroupRow(
            stringResource(R.string.settings_phone_notifications, desk.name),
            icon = R.drawable.sym_notifications,
            subtitle = stringResource(R.string.settings_desk_notifications_summary),
            checked = desk.notificationsOn,
            onClick = { actions.onPhoneNotifications(!desk.notificationsOn) },
        )
        if (desk.notificationsOn) {
            GroupRow(
                stringResource(R.string.sg_choose_apps),
                icon = R.drawable.sym_tune,
                subtitle = stringResource(R.string.sg_choose_apps_summary),
                onClick = actions.onChooseNotificationApps,
            ) { Chevron() }
            // The computer's own switch for showing this phone's notifications (it was only reachable on the computer).
            DeskSwitch(desk, actions, DeskSettingsPlugin.NOTIFICATIONS, stringResource(R.string.settings_desk_shows_notifications, desk.name), R.drawable.sym_notifications_active, stringResource(R.string.settings_desk_shows_notifications_summary, desk.name), desk.remote?.notifications == true)
            val defaultPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            val privateKey = org.desklink.mobile.plugins.notifications.NotificationPrivacy.key(desk.id)
            var showPrivate by remember { mutableStateOf(defaultPrefs.getBoolean(privateKey, false)) }
            GroupRow(
                stringResource(R.string.settings_private_notifications, desk.name),
                icon = R.drawable.sym_lock,
                subtitle = stringResource(R.string.settings_private_notifications_summary),
                checked = showPrivate,
                onClick = { showPrivate = !showPrivate; defaultPrefs.edit { putBoolean(privateKey, showPrivate) } },
            )
            if (!org.desklink.mobile.plugins.notifications.NotificationReceiver.hasReadNotificationsPermission(context)) {
                AllowRow(stringResource(R.string.settings_perm_notification_access), stringResource(R.string.settings_perm_notification_access_summary, desk.name)) {
                    runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                }
            }
        }
    }
    SectionLabel(stringResource(R.string.sg_notifications_desk, desk.name))
    RowGroup {
        GroupRow(
            stringResource(R.string.settings_desk_notifications_here, desk.name),
            icon = R.drawable.sym_notifications_active,
            subtitle = stringResource(R.string.settings_desk_notifications_here_summary, desk.name),
            checked = desk.deskNotificationsOn,
            onClick = { actions.onDeskNotifications(!desk.deskNotificationsOn) },
        )
        if (desk.deskNotificationsOn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(context, Manifest.permission.POST_NOTIFICATIONS)) {
            AllowRow(stringResource(R.string.settings_perm_notifications), stringResource(R.string.settings_perm_notifications_summary)) {
                runCatching { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
            }
        }
        DeskSwitch(desk, actions, keys.COMMAND_ALERTS, stringResource(R.string.settings_command_alerts), R.drawable.sym_notifications_active, stringResource(R.string.settings_command_alerts_summary), desk.remote?.commandAlerts == true)
        // Where the ring sound lives: the desk "rings" this phone through Notifications.
        GroupRow(
            stringResource(R.string.select_ringtone),
            icon = R.drawable.sym_vibration,
            subtitle = ringtoneTitle,
            onClick = {
                val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, Settings.System.DEFAULT_NOTIFICATION_URI)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, ringtone)
                runCatching { picker.launch(intent) }
            },
        ) { Chevron() }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        RowGroup {
            GroupRow(
                stringResource(R.string.setting_persistent_notification_oreo),
                icon = R.drawable.sym_open_in_new,
                subtitle = stringResource(R.string.setting_persistent_notification_description),
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .putExtra(Settings.EXTRA_CHANNEL_ID, NotificationHelper.Channels.PERSISTENT),
                    )
                },
            )
        }
    }
}

@Composable
private fun ClipboardGroup(desk: DeskSettings?, actions: SettingsActions, prefs: android.content.SharedPreferences, changed: () -> Unit) {
    if (desk == null) return NoComputer()
    RowGroup {
        GroupRow(
            stringResource(R.string.settings_phone_clipboard),
            icon = R.drawable.sym_content_paste,
            subtitle = clipboardSummary(desk.name),
            checked = desk.clipboardOn,
            onClick = { actions.onPhoneClipboard(!desk.clipboardOn) },
        )
        if (desk.clipboardOn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ClipboardAutoSyncRows(desk.name, prefs, actions.onMessage, changed)
        }
        DeskSwitch(desk, actions, DeskSettingsPlugin.CLIPBOARD, stringResource(R.string.settings_desk_clipboard, desk.name), R.drawable.sym_content_paste, stringResource(R.string.settings_desk_clipboard_summary, desk.name), desk.remote?.clipboard == true)
        if (desk.clipboardOn) {
            GroupRow(stringResource(R.string.home_send_clipboard, desk.name), icon = R.drawable.sym_upload, onClick = actions.onSendClipboard) { Chevron() }
        }
    }
}

@Composable
private fun CallsGroup(desk: DeskSettings?, actions: SettingsActions) {
    val context = LocalContext.current
    if (desk == null) return NoComputer()
    RowGroup {
        GroupRow(
            stringResource(R.string.settings_calls_on, desk.name),
            icon = R.drawable.sym_call,
            subtitle = stringResource(R.string.settings_calls_on_summary, desk.name),
            checked = desk.callsOn,
            onClick = { actions.onCalls(!desk.callsOn) },
        )
        if (desk.callsOn) {
            GroupRow(
                stringResource(R.string.settings_show_caller, desk.name),
                icon = R.drawable.sym_person,
                subtitle = stringResource(R.string.settings_show_caller_summary),
                checked = desk.showCaller,
                onClick = { actions.onShowCaller(!desk.showCaller) },
            )
            if (!granted(context, Manifest.permission.READ_PHONE_STATE) || !granted(context, Manifest.permission.CALL_PHONE)) {
                AllowRow(stringResource(R.string.sg_calls_permission), stringResource(R.string.sg_calls_permission_summary, desk.name)) {
                    actions.onCalls(true)
                }
            }
        }
    }
}

@Composable
private fun SoundGroup(desk: DeskSettings?, actions: SettingsActions) {
    if (desk == null) return NoComputer()
    RowGroup {
        GroupRow(
            stringResource(R.string.settings_desk_sound, desk.name),
            icon = R.drawable.sym_volume_up,
            subtitle = stringResource(R.string.settings_desk_sound_summary, desk.name),
            checked = desk.deskSoundOn,
            onClick = { actions.onDeskSound(!desk.deskSoundOn) },
        )
        GroupRow(
            stringResource(R.string.settings_desk_may_play, desk.name),
            icon = R.drawable.sym_volume_up,
            subtitle = stringResource(R.string.settings_desk_may_play_summary, desk.name),
            checked = desk.deskMayPlay,
            onClick = { actions.onDeskMayPlay(!desk.deskMayPlay) },
        )
        if (desk.deskSoundOn || desk.deskMayPlay) DeskSoundVolume(desk.deskSoundVolume, actions.onDeskSoundVolume)
        // Allowed once with "remember": turned off here, the computer has to ask again.
        if (desk.micConsent) {
            GroupRow(
                stringResource(R.string.settings_mic_consent, desk.name),
                icon = R.drawable.sym_mic,
                subtitle = stringResource(R.string.settings_mic_consent_summary, desk.name),
                checked = true,
                onClick = { actions.onMicConsent(false) },
            )
        }
        GroupRow(
            stringResource(R.string.settings_phone_media, desk.name),
            icon = R.drawable.sym_music_note,
            subtitle = stringResource(R.string.settings_phone_media_summary),
            checked = desk.phoneMediaOn,
            onClick = { actions.onPhoneMedia(!desk.phoneMediaOn) },
        )
    }
}

@Composable
private fun ControlGroup(desk: DeskSettings?, actions: SettingsActions, prefs: android.content.SharedPreferences, changed: () -> Unit) {
    val context = LocalContext.current
    if (desk == null) return NoComputer()
    var choosingSpeed by rememberSaveable { mutableStateOf(false) }
    val speedKey = stringResource(R.string.mousepad_sensitivity_key)
    val scrollKey = stringResource(R.string.mousepad_scroll_direction)
    val speedValues = stringArrayResource(R.array.mousepad_sensitivity_values)
    val speedNames = stringArrayResource(R.array.mousepad_sensitivity_entries)
    val speed = prefs.getString(speedKey, stringResource(R.string.mousepad_default_sensitivity))
    SectionLabel(stringResource(R.string.sg_control_phone, desk.name))
    RowGroup {
        GroupRow(
            stringResource(R.string.settings_glide, desk.name),
            icon = R.drawable.sym_mouse,
            subtitle = when {
                !desk.glideOn -> stringResource(R.string.settings_glide_off, desk.name)
                !desk.accessibilityOn -> stringResource(R.string.settings_glide_accessibility, desk.name)
                else -> stringResource(R.string.settings_glide_on)
            },
            checked = desk.glideOn,
            onClick = {
                actions.onGlide(!desk.glideOn)
                // The accessibility service is asked for only now, with the steps past "Restricted setting".
                if (!desk.glideOn && !desk.accessibilityOn) AccessibilitySetupActivity.open(context)
            },
        )
        GroupRow(
            stringResource(R.string.settings_type_on_phone, desk.name),
            icon = R.drawable.sym_keyboard,
            subtitle = stringResource(
                when {
                    !desk.typeOnPhoneOn -> R.string.settings_type_on_phone_off
                    !desk.accessibilityOn -> R.string.settings_type_on_phone_service
                    else -> R.string.settings_type_on_phone_on
                },
                desk.name,
            ),
            checked = desk.typeOnPhoneOn,
            onClick = {
                actions.onTypeOnPhone(!desk.typeOnPhoneOn)
                if (!desk.typeOnPhoneOn && !desk.accessibilityOn) AccessibilitySetupActivity.open(context)
            },
        )
        if ((desk.glideOn || desk.typeOnPhoneOn) && !desk.accessibilityOn) {
            AllowRow(stringResource(R.string.settings_perm_accessibility), stringResource(R.string.settings_perm_accessibility_summary, desk.name)) { AccessibilitySetupActivity.open(context) }
        }
    }
    SectionLabel(stringResource(R.string.sg_control_desk_screen, desk.name))
    RowGroup {
        GroupRow(
            stringResource(R.string.mousepad_sensitivity_settings_title),
            icon = R.drawable.sym_mouse,
            subtitle = speedNames.getOrNull(speedValues.indexOf(speed)),
            onClick = { choosingSpeed = true },
        ) { Chevron() }
        GroupRow(
            stringResource(R.string.mousepad_scroll_direction_title),
            icon = R.drawable.sym_swap_vert,
            checked = prefs.getBoolean(scrollKey, false),
            onClick = { prefs.edit { putBoolean(scrollKey, !prefs.getBoolean(scrollKey, false)) }; changed() },
        )
    }
    SectionLabel(desk.name)
    RowGroup {
        DeskSwitch(desk, actions, DeskSettingsPlugin.KEEP_AWAKE, stringResource(R.string.settings_keep_awake, desk.name), R.drawable.sym_coffee, stringResource(R.string.settings_keep_awake_summary), desk.remote?.keepAwake == true)
    }
    if (choosingSpeed) {
        ChoiceDialog(
            title = stringResource(R.string.mousepad_sensitivity_settings_title),
            options = speedNames.toList(),
            selected = speedValues.indexOf(speed),
            onDismiss = { choosingSpeed = false },
        ) { index ->
            choosingSpeed = false
            prefs.edit { putString(speedKey, speedValues[index]) }
            changed()
        }
    }
}

@Composable
private fun FilesGroup(desk: DeskSettings?, actions: SettingsActions, prefs: android.content.SharedPreferences, changed: () -> Unit) {
    val context = LocalContext.current
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            org.desklink.mobile.plugins.share.ShareDestination.saveStorageLocationPreference(context, uri)
            changed()
        }
    }
    val custom = org.desklink.mobile.plugins.share.ShareDestination.isCustomDestinationEnabled(context)
    val where = if (custom) prefs.getString("share_destination_folder_uri", null)?.let { Uri.parse(it).path } else null
    RowGroup {
        if (desk != null) {
            GroupRow(stringResource(R.string.more_bucket), icon = R.drawable.sym_inventory, subtitle = stringResource(R.string.more_bucket_summary), onClick = actions.onOpenBucket) { Chevron() }
        }
        GroupRow(
            stringResource(R.string.share_destination_folder_preference),
            icon = R.drawable.sym_folder_zip,
            subtitle = where ?: org.desklink.mobile.plugins.share.ShareDestination.getDefaultDestinationDirectory().absolutePath,
            monoSubtitle = true,
            onClick = { runCatching { folder.launch(null) } },
        ) { Chevron() }
        if (custom) {
            GroupRow(stringResource(R.string.sg_files_default_folder), icon = R.drawable.sym_restart_alt, onClick = {
                prefs.edit { putBoolean("share_destination_custom", false) }
                changed()
            })
        }
        if (desk != null) {
            GroupRow(
                stringResource(R.string.settings_contacts_share, desk.name),
                icon = R.drawable.sym_badge,
                subtitle = when {
                    !desk.contactsOn -> stringResource(R.string.settings_contacts_share_off, desk.name)
                    !desk.contactsReadable -> stringResource(R.string.settings_contacts_share_permission, desk.name)
                    else -> stringResource(R.string.settings_contacts_share_on, desk.name)
                },
                checked = desk.contactsOn,
                onClick = { actions.onContacts(!desk.contactsOn) },
            )
            if (desk.contactsOn && !desk.contactsReadable) {
                AllowRow(stringResource(R.string.sg_contacts_permission), stringResource(R.string.settings_contacts_share_permission, desk.name)) { actions.onContacts(true) }
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && !granted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { changed() }
            AllowRow(stringResource(R.string.settings_perm_files), stringResource(R.string.settings_perm_files_summary, desk?.name ?: stringResource(R.string.settings_your_computer))) {
                request.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }
}

@Composable
private fun PrivacyGroup(desk: DeskSettings?, actions: SettingsActions, prefs: android.content.SharedPreferences, changed: () -> Unit) {
    val context = LocalContext.current
    val waiting = if (desk == null) "" else stringResource(R.string.settings_waiting_for, desk.name)
    val remote = desk?.remote
    val live = desk != null && desk.ready && remote != null
    RowGroup {
        // Phone-only: a fingerprint or screen-lock check before Terminals and the desk screen.
        val canCheck = org.desklink.mobile.ui.Biometrics.available(context, org.desklink.mobile.ui.Biometrics.STRONG_OR_CREDENTIAL)
        GroupRow(
            stringResource(R.string.settings_session_gate),
            icon = R.drawable.sym_fingerprint,
            subtitle = stringResource(if (canCheck) R.string.settings_session_gate_summary else R.string.settings_session_gate_no_lock),
            checked = canCheck && prefs.getBoolean(org.desklink.mobile.ui.SessionGate.KEY_PHONE_GATE, false),
            enabled = canCheck,
            onClick = {
                prefs.edit { putBoolean(org.desklink.mobile.ui.SessionGate.KEY_PHONE_GATE, !prefs.getBoolean(org.desklink.mobile.ui.SessionGate.KEY_PHONE_GATE, false)) }
                changed()
            },
        )
        if (desk != null) {
            // Read-only: set at the desk.
            GroupRow(
                stringResource(R.string.settings_unlock_nearby),
                icon = R.drawable.sym_lock_open,
                subtitle = when {
                    !live -> waiting
                    remote?.unlockNearby == true -> stringResource(R.string.settings_unlock_nearby_on, desk.name)
                    else -> stringResource(R.string.settings_unlock_nearby_off, desk.name)
                },
                enabled = live,
            )
            remote?.sessionPasswordSet?.let { set ->
                GroupRow(
                    stringResource(R.string.settings_session_password),
                    icon = R.drawable.sym_fingerprint,
                    subtitle = if (!live) waiting else stringResource(if (set) R.string.settings_session_password_set else R.string.settings_session_password_unset, desk.name),
                    enabled = live,
                )
            }
            GroupRow(
                stringResource(R.string.device_encryption),
                icon = R.drawable.sym_lock,
                subtitle = stringResource(R.string.settings_paired_key, desk.key),
                onClick = actions.onEncryptionInfo,
            ) { Chevron() }
        }
        // Unpair lives here, as on the computer (Privacy & security).
        UnpairRow(desk, actions)
        val privacyUrl = stringResource(R.string.privacy_policy_url)
        GroupRow(stringResource(R.string.privacy_policy), icon = R.drawable.sym_open_in_new, subtitle = stringResource(R.string.privacy_policy_summary), onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, privacyUrl.toUri())) }
        })
    }
}

@Composable
private fun UnpairRow(desk: DeskSettings?, actions: SettingsActions) {
    val t = tokens
    var confirmUnpair by rememberSaveable { mutableStateOf(false) }
    if (desk == null) return
    GroupRow(
        stringResource(R.string.device_menu_unpair),
        icon = R.drawable.sym_link_off,
        titleColor = t.red,
        iconTint = t.red,
        onClick = { confirmUnpair = true },
    )
    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text(stringResource(R.string.device_unpair_title, desk.name)) },
            text = { Text(stringResource(R.string.device_unpair_message)) },
            confirmButton = {
                TextButton(
                    onClick = { confirmUnpair = false; actions.onUnpair() },
                    colors = ButtonDefaults.textButtonColors(contentColor = t.red),
                ) { Text(stringResource(R.string.device_menu_unpair)) }
            },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun AppearanceGroup(actions: SettingsActions, prefs: android.content.SharedPreferences, changed: () -> Unit) {
    var choosingTheme by rememberSaveable { mutableStateOf(false) }
    val themeValues = stringArrayResource(R.array.theme_list_values)
    val themeNames = stringArrayResource(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) R.array.theme_list_v28 else R.array.theme_list)
    val theme = prefs.getString(KEY_APP_THEME, ThemeUtil.DEFAULT_MODE) ?: ThemeUtil.DEFAULT_MODE
    RowGroup {
        GroupRow(stringResource(R.string.settings_theme), icon = R.drawable.sym_dark_mode, subtitle = themeNames.getOrNull(themeValues.indexOf(theme)), onClick = { choosingTheme = true }) { Chevron() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            GroupRow(
                stringResource(R.string.setting_dynamic_colors),
                icon = R.drawable.sym_palette,
                subtitle = stringResource(R.string.settings_dynamic_colors_summary),
                checked = prefs.getBoolean(ThemeUtil.KEY_DYNAMIC_COLORS, false),
                onClick = {
                    prefs.edit { putBoolean(ThemeUtil.KEY_DYNAMIC_COLORS, !prefs.getBoolean(ThemeUtil.KEY_DYNAMIC_COLORS, false)) }
                    actions.onRecreate()
                },
            )
        }
    }
    if (choosingTheme) {
        ChoiceDialog(
            title = stringResource(R.string.settings_theme),
            options = themeNames.toList(),
            selected = themeValues.indexOf(theme),
            onDismiss = { choosingTheme = false },
        ) { index ->
            choosingTheme = false
            prefs.edit { putString(KEY_APP_THEME, themeValues[index]) }
            ThemeUtil.applyTheme(themeValues[index])
            changed()
        }
    }
}

@Composable
private fun AdvancedGroup(desk: DeskSettings?, actions: SettingsActions, prefs: android.content.SharedPreferences, onOpen: (String) -> Unit, changed: () -> Unit) {
    val context = LocalContext.current
    val exportLogs = rememberLauncherForActivityResult(CreateFileResultContract()) { uri: Uri? ->
        val output = uri?.let { context.contentResolver.openOutputStream(it) } ?: return@rememberLauncherForActivityResult
        CoroutineScope(Dispatchers.IO).launch {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d"))
            val reader = InputStreamReader(process.inputStream)
            output.use {
                it.write("DeskLink ${BuildConfig.VERSION_NAME}\n".toByteArray(Charsets.UTF_8))
                it.write("Android ${Build.VERSION.RELEASE} (${Build.MANUFACTURER} ${Build.MODEL})\n".toByteArray(Charsets.UTF_8))
                IOUtils.copy(reader, it, Charsets.UTF_8)
            }
        }
    }
    // Back from the trusted networks: discovery re-reads them at once.
    val trustedNetworks = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        BackgroundService.instance?.onNetworkChange(null)
    }
    val customDevices = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { changed() }
    val waiting = waitingPermissions(context, desk?.name ?: stringResource(R.string.settings_your_computer), desk?.pluginPermissions?.size ?: 0)
    val configured = RelaySettings.configured(context)
    val relayOn = configured && prefs.getBoolean(RelaySettings.KEY_ENABLED, true)
    SectionLabel(stringResource(R.string.settings_category_away))
    RowGroup {
        // Without a relay address the switch has nothing to turn on: say so, and wait for one.
        GroupRow(
            stringResource(R.string.settings_phone_relay),
            icon = R.drawable.sym_public,
            subtitle = if (!configured) {
                stringResource(R.string.settings_relay_not_set_up)
            } else {
                listOfNotNull(stringResource(R.string.settings_reach_anywhere_summary), relayStatusText().takeIf { relayOn }).joinToString(" · ")
            },
            checked = relayOn,
            enabled = configured,
            onClick = {
                prefs.edit { putBoolean(RelaySettings.KEY_ENABLED, !relayOn) }
                relaySettingChanged()
                changed()
            },
        )
        if (desk != null && desk.remote?.relayConfigured == true) {
            DeskSwitch(desk, actions, DeskSettingsPlugin.RELAY, stringResource(R.string.settings_desk_relay, desk.name), R.drawable.sym_public, stringResource(R.string.settings_reach_anywhere_summary), desk.remote.relay)
        }
        GroupRow(stringResource(R.string.setting_advanced_network), icon = R.drawable.sym_tune, subtitle = stringResource(R.string.setting_advanced_network_summary), onClick = { onOpen(SettingsPage.NETWORK) }) { Chevron() }
    }
    SectionLabel(stringResource(R.string.settings_category_connection))
    RowGroup {
        GroupRow(stringResource(R.string.trusted_networks), icon = R.drawable.sym_public, subtitle = stringResource(R.string.trusted_networks_desc), onClick = { trustedNetworks.launch(Intent(context, TrustedNetworksActivity::class.java)) })
        GroupRow(
            stringResource(R.string.custom_device_list),
            icon = R.drawable.sym_add,
            subtitle = stringResource(R.string.custom_devices_settings_summary, CustomDevicesActivity.getCustomDeviceList(context).size),
            onClick = { customDevices.launch(Intent(context, CustomDevicesActivity::class.java)) },
        )
    }
    SectionLabel(stringResource(R.string.settings_permissions))
    RowGroup {
        GroupRow(
            stringResource(R.string.settings_permissions),
            icon = R.drawable.sym_accessibility_new,
            subtitle = if (waiting > 0) androidx.compose.ui.res.pluralStringResource(R.plurals.settings_permissions_waiting, waiting, waiting) else stringResource(R.string.sg_permissions_all_set),
            onClick = { onOpen(SettingsPage.PERMISSIONS) },
        ) { Chevron() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            GroupRow(stringResource(R.string.surfaces_add_tiles), icon = R.drawable.sym_tune, subtitle = stringResource(R.string.surfaces_add_tiles_summary), onClick = actions.onAddTiles) { Chevron() }
        }
    }
    SectionLabel(stringResource(R.string.settings_category_help))
    RowGroup {
        GroupRow(stringResource(R.string.settings_export_logs), icon = R.drawable.sym_upload_file, subtitle = stringResource(R.string.settings_export_logs_text), onClick = { exportLogs.launch(CreateFileParams("text/plain", "desklink-log.txt")) })
        GroupRow(stringResource(R.string.about), icon = R.drawable.sym_info, onClick = { onOpen(SettingsPage.ABOUT) }) { Chevron() }
    }
    if (BuildConfig.DEBUG) {
        SectionLabel(stringResource(R.string.settings_category_debug))
        RowGroup {
            for ((key, title, summary) in listOf(
                Triple(RelaySettings.KEY_FORCE_RELAY, R.string.setting_relay_force_relay, R.string.setting_relay_force_relay_description),
                Triple(RelaySettings.KEY_FORCE_TURN, R.string.setting_relay_force_turn, R.string.setting_relay_force_turn_description),
            )) {
                GroupRow(stringResource(title), subtitle = stringResource(summary), checked = prefs.getBoolean(key, false), onClick = {
                    prefs.edit { putBoolean(key, !prefs.getBoolean(key, false)) }
                    changed()
                })
            }
        }
    }
}
