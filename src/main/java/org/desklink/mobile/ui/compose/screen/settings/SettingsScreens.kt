/*
 * SPDX-FileCopyrightText: 2023 Albert Vaca Cintora <albertvaka@gmail.com>
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.screen.settings

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon as AndroidIcon
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.apache.commons.io.IOUtils
import org.desklink.mobile.BackgroundService
import org.desklink.mobile.BuildConfig
import org.desklink.mobile.R
import org.desklink.mobile.backends.relay.RelayLinkProvider
import org.desklink.mobile.backends.relay.RelaySettings
import org.desklink.mobile.helpers.CreateFileParams
import org.desklink.mobile.helpers.CreateFileResultContract
import org.desklink.mobile.helpers.DeviceHelper
import org.desklink.mobile.helpers.DeviceHelper.filterInvalidCharactersFromDeviceName
import org.desklink.mobile.helpers.NotificationHelper
import org.desklink.mobile.plugins.Plugin
import org.desklink.mobile.plugins.clipboard.ClipboardTileService
import org.desklink.mobile.plugins.mousereceiver.MouseReceiverService
import org.desklink.mobile.plugins.terminal.QuickCommands
import org.desklink.mobile.ui.CustomDevicesActivity
import org.desklink.mobile.ui.ThemeUtil
import org.desklink.mobile.ui.TrustedNetworksActivity
import org.desklink.mobile.ui.compose.MonoFamily
import org.desklink.mobile.ui.compose.components.GroupRow
import org.desklink.mobile.ui.compose.components.LaptopGlyph
import org.desklink.mobile.ui.compose.components.LargeTitle
import org.desklink.mobile.ui.compose.components.PillButton
import org.desklink.mobile.ui.compose.components.PillStyle
import org.desklink.mobile.ui.compose.components.RowGroup
import org.desklink.mobile.ui.compose.components.ScreenHeader
import org.desklink.mobile.ui.compose.components.SectionLabel
import org.desklink.mobile.ui.compose.tokens
import java.io.InputStreamReader

const val KEY_CONNECT_ON_STARTUP = "connect_on_startup"
const val KEY_BLUETOOTH_ENABLED = "bluetooth_enabled"
const val KEY_APP_THEME = "theme_pref"

/** The selected computer's settings, as the "<desk>" section shows them. */
data class DeskSettings(
    val id: String,
    val name: String,
    val clipboardOn: Boolean,
    val notificationsOn: Boolean,
    /** "3C:91:0B:7E", the start of its certificate fingerprint. */
    val key: String,
    /** Plugins this computer uses that wait for a permission, as (name, open its dialog). */
    val pluginPermissions: List<Pair<String, () -> Unit>> = emptyList(),
    /** What the desk itself reports (`desklink.settings`); null from an older desk or before it arrives. */
    val remote: org.desklink.mobile.plugins.desksettings.DeskSettingsState? = null,
    /** This phone's media is shown and controlled on the desk. */
    val phoneMediaOn: Boolean = false,
)

class SettingsActions(
    val onBack: () -> Unit,
    val onDeskClipboard: (Boolean) -> Unit,
    val onDeskNotifications: (Boolean) -> Unit,
    /** A desk-side switch (`desklink.settings.request {set: {key: value}}`). */
    val onDeskSetting: (String, Boolean) -> Unit,
    val onPhoneMedia: (Boolean) -> Unit,
    val onQuickCommands: () -> Unit,
    val onMore: () -> Unit,
    val onUnpair: () -> Unit,
    val onEncryptionInfo: () -> Unit,
    /** The theme or wallpaper colours changed: the activity is recreated to apply them. */
    val onRecreate: () -> Unit,
    val onMessage: (String) -> Unit,
)

@Composable
private fun SettingsColumn(title: String, onBack: () -> Unit, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val t = tokens
    Column(Modifier.fillMaxSize().background(t.bg).statusBarsPadding()) {
        ScreenHeader(onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            LargeTitle(title, subtitle = subtitle)
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                content()
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars).padding(bottom = 10.dp))
            }
        }
    }
}

/** Bumps on every resume: permissions and system settings change while the person is away. */
@Composable
private fun rememberResumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    return tick
}

// ---- settings ------------------------------------------------------------------------------------------------

@Composable
fun SettingsScreen(desk: DeskSettings?, actions: SettingsActions) {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    @Suppress("UNUSED_VARIABLE") val resumed = rememberResumeTick()
    var revision by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_EXPRESSION") revision
    var editingName by rememberSaveable { mutableStateOf(false) }
    var choosingTheme by rememberSaveable { mutableStateOf(false) }
    var confirmUnpair by rememberSaveable { mutableStateOf(false) }
    val t = tokens

    SettingsColumn(stringResource(R.string.settings), actions.onBack) {
        SectionLabel(stringResource(R.string.settings_category_general))
        RowGroup {
            GroupRow(
                stringResource(R.string.settings_phone_name),
                icon = R.drawable.sym_badge,
                subtitle = DeviceHelper.getDeviceName(context),
                monoSubtitle = true,
                onClick = { editingName = true },
            ) { Icon(painterResource(R.drawable.sym_edit), contentDescription = null, tint = t.textDim, modifier = Modifier.size(20.dp)) }
            val themeValues = stringArrayResource(R.array.theme_list_values)
            val themeNames = stringArrayResource(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) R.array.theme_list_v28 else R.array.theme_list)
            val theme = prefs.getString(KEY_APP_THEME, ThemeUtil.DEFAULT_MODE) ?: ThemeUtil.DEFAULT_MODE
            GroupRow(
                stringResource(R.string.settings_theme),
                icon = R.drawable.sym_dark_mode,
                subtitle = themeNames.getOrNull(themeValues.indexOf(theme)),
                onClick = { choosingTheme = true },
            ) { Icon(painterResource(R.drawable.sym_chevron_right), contentDescription = null, tint = t.textDim, modifier = Modifier.size(20.dp)) }
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
            GroupRow(
                stringResource(R.string.settings_connect_on_startup),
                icon = R.drawable.sym_restart_alt,
                subtitle = stringResource(R.string.settings_connect_on_startup_summary),
                checked = prefs.getBoolean(KEY_CONNECT_ON_STARTUP, true),
                onClick = { prefs.edit { putBoolean(KEY_CONNECT_ON_STARTUP, !prefs.getBoolean(KEY_CONNECT_ON_STARTUP, true)) }; revision++ },
            )
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
                    revision++
                },
            )
        }

        PermissionsSection(desk)

        if (desk != null) {
            val remote = desk.remote
            SectionLabel(desk.name)
            RowGroup {
                // On only when both ends are on; a switch here turns both.
                val clipboard = deskSwitch(desk.clipboardOn, remote?.clipboard)
                GroupRow(
                    stringResource(R.string.home_clipboard_sync),
                    icon = R.drawable.sym_content_paste,
                    subtitle = stringResource(clipboardSummary()),
                    checked = clipboard,
                    onClick = { actions.onDeskClipboard(!clipboard) },
                )
                val notifications = deskSwitch(desk.notificationsOn, remote?.notifications)
                GroupRow(
                    stringResource(R.string.settings_desk_notifications),
                    icon = R.drawable.sym_notifications,
                    subtitle = stringResource(R.string.settings_desk_notifications_summary),
                    checked = notifications,
                    onClick = { actions.onDeskNotifications(!notifications) },
                )
                GroupRow(
                    stringResource(R.string.settings_phone_media, desk.name),
                    icon = R.drawable.sym_music_note,
                    subtitle = stringResource(R.string.settings_phone_media_summary),
                    checked = desk.phoneMediaOn,
                    onClick = { actions.onPhoneMedia(!desk.phoneMediaOn) },
                )
            }
            if (remote != null) {
                RowGroup {
                    GroupRow(
                        stringResource(R.string.settings_keep_awake, desk.name),
                        icon = R.drawable.sym_coffee,
                        subtitle = stringResource(R.string.settings_keep_awake_summary),
                        checked = remote.keepAwake,
                        onClick = { actions.onDeskSetting(org.desklink.mobile.plugins.desksettings.DeskSettingsPlugin.KEEP_AWAKE, !remote.keepAwake) },
                    )
                    GroupRow(
                        stringResource(R.string.settings_command_alerts),
                        icon = R.drawable.sym_notifications_active,
                        subtitle = stringResource(R.string.settings_command_alerts_summary),
                        checked = remote.commandAlerts,
                        onClick = { actions.onDeskSetting(org.desklink.mobile.plugins.desksettings.DeskSettingsPlugin.COMMAND_ALERTS, !remote.commandAlerts) },
                    )
                    // Turned on at the desk only: it lets this phone unlock it.
                    GroupRow(
                        stringResource(R.string.settings_unlock_nearby),
                        icon = R.drawable.sym_lock_open,
                        subtitle = stringResource(if (remote.unlockNearby) R.string.settings_unlock_nearby_on else R.string.settings_unlock_nearby_off, desk.name),
                    )
                }
            }
            RowGroup {
                // Without a relay address the switch has nothing to turn on: say so, and wait for one.
                val configured = RelaySettings.configured(context)
                val relayOn = configured && prefs.getBoolean(RelaySettings.KEY_ENABLED, true)
                GroupRow(
                    stringResource(R.string.settings_reach_anywhere),
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
                        // The desk follows when it has a relay set up too.
                        if (remote?.relayConfigured == true) actions.onDeskSetting(org.desklink.mobile.plugins.desksettings.DeskSettingsPlugin.RELAY, !relayOn)
                        revision++
                    },
                )
                val commands = QuickCommands.load(QuickCommands.prefs(context), desk.id)
                GroupRow(
                    stringResource(R.string.settings_quick_commands),
                    icon = R.drawable.sym_terminal,
                    subtitle = commands.joinToString(" · ").ifEmpty { stringResource(R.string.settings_quick_commands_none) },
                    monoSubtitle = commands.isNotEmpty(),
                    onClick = actions.onQuickCommands,
                ) { Chevron() }
            }
        }

        RowGroup {
            GroupRow(stringResource(R.string.settings_more), icon = R.drawable.sym_tune, subtitle = stringResource(R.string.settings_more_summary), onClick = actions.onMore) { Chevron() }
        }

        if (desk != null) {
            RowGroup {
                Row(
                    Modifier.fillMaxWidth().background(t.surface).clickable(onClick = actions.onEncryptionInfo).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    LaptopGlyph(t.textDim)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(desk.name, fontFamily = MonoFamily, fontSize = 15.sp)
                        Text(stringResource(R.string.settings_paired_key, desk.key), fontSize = 13.sp, color = t.textDim)
                    }
                }
                GroupRow(
                    stringResource(R.string.device_menu_unpair),
                    icon = R.drawable.sym_link_off,
                    titleColor = t.red,
                    iconTint = t.red,
                    onClick = { confirmUnpair = true },
                )
            }
        }
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
                revision++
            }
        }
    }
    if (choosingTheme) {
        val values = stringArrayResource(R.array.theme_list_values)
        val names = stringArrayResource(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) R.array.theme_list_v28 else R.array.theme_list)
        ChoiceDialog(
            title = stringResource(R.string.settings_theme),
            options = names.toList(),
            selected = values.indexOf(prefs.getString(KEY_APP_THEME, ThemeUtil.DEFAULT_MODE)),
            onDismiss = { choosingTheme = false },
        ) { index ->
            choosingTheme = false
            prefs.edit { putString(KEY_APP_THEME, values[index]) }
            ThemeUtil.applyTheme(values[index])
        }
    }
    if (confirmUnpair && desk != null) {
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

/** A switch both ends hold: on only when both are (an older desk reports nothing: the phone decides). */
fun deskSwitch(phone: Boolean, desk: Boolean?): Boolean = phone && desk != false

/** Android 10+ keeps background apps off the clipboard: the phone's side needs a tap. */
fun clipboardSummary(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) R.string.home_clipboard_on_q else R.string.home_clipboard_on

@Composable
private fun Chevron() = Icon(painterResource(R.drawable.sym_chevron_right), contentDescription = null, tint = tokens.textDim, modifier = Modifier.size(20.dp))

// ---- permissions ------------------------------------------------------------------------------------------------

private class PermissionRow(
    @DrawableRes val icon: Int,
    val title: String,
    val summary: String,
    val granted: Boolean,
    /** Android cannot tell whether it is done (OEM settings): an "Open" button, never counted as waiting. */
    val unknown: Boolean = false,
    val allow: () -> Unit,
)

/** OEMs whose battery managers freeze or kill a foreground service with the screen off. */
fun needsOemBackgroundSetting(manufacturer: String): Boolean =
    manufacturer.lowercase() in setOf("oppo", "realme", "oneplus", "xiaomi", "redmi", "poco", "vivo", "iqoo", "huawei", "honor", "meizu", "asus", "tecno", "infinix")

@Composable
private fun PermissionsSection(desk: DeskSettings?) {
    val context = LocalContext.current
    val t = tokens
    val tick = rememberResumeTick()
    var asked by remember { mutableIntStateOf(0) }
    // Android 10 also wants a folder chosen by hand before received files can be written.
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) org.desklink.mobile.plugins.share.ShareSettingsFragment.saveStorageLocationPreference(context, uri)
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        asked++
        if (result.values.any { it }) {
            // Plugins that waited for the permission (Share, Find my phone…) load now.
            CoroutineScope(Dispatchers.IO).launch {
                org.desklink.mobile.DeskLinkApplication.getInstance().devices.values.forEach(org.desklink.mobile.Device::reloadPluginsFromSettings)
            }
        }
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && result[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true) folder.launch(null)
    }
    val deskName = desk?.name ?: stringResource(R.string.settings_your_computer)
    val tapToGrant = stringResource(R.string.device_tap_permission)
    val rows = remember(tick, asked, desk) { permissionRows(context, deskName, request::launch) } +
        desk?.pluginPermissions.orEmpty().map { (name, open) -> PermissionRow(R.drawable.sym_info, name, tapToGrant, false, allow = open) }
    val waiting = rows.count { !it.granted && !it.unknown }
    SectionLabel(stringResource(R.string.settings_permissions)) {
        if (waiting > 0) Text(pluralsString(R.plurals.settings_permissions_waiting, waiting), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = t.amber)
    }
    RowGroup {
        rows.forEach { row ->
            GroupRow(row.title, icon = row.icon, subtitle = row.summary) {
                if (row.granted) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(painterResource(R.drawable.sym_check_circle), contentDescription = null, tint = t.mint, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.settings_allowed), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = t.mintText)
                    }
                } else {
                    PillButton(stringResource(if (row.unknown) R.string.settings_open else R.string.settings_allow), row.allow, style = PillStyle.Tonal, height = 34.dp, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun pluralsString(id: Int, count: Int) = androidx.compose.ui.res.pluralStringResource(id, count, count)

/** The permissions DeskLink uses here, each with the system screen that grants it. */
private fun permissionRows(context: Context, desk: String, request: (Array<String>) -> Unit): List<PermissionRow> {
    fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    fun open(intent: Intent) = {
        runCatching { context.startActivity(intent) }.onFailure {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        }
        Unit
    }
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    return buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(PermissionRow(R.drawable.sym_notifications_active, context.getString(R.string.settings_perm_notifications), context.getString(R.string.settings_perm_notifications_summary), granted(Manifest.permission.POST_NOTIFICATIONS)) {
                request(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            })
        }
        add(PermissionRow(
            R.drawable.sym_notifications, context.getString(R.string.settings_perm_notification_access), context.getString(R.string.settings_perm_notification_access_summary, desk),
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName),
            allow = open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)),
        ))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && prefs.getBoolean(KEY_BLUETOOTH_ENABLED, false)) {
            val nearby = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            add(PermissionRow(R.drawable.sym_bluetooth_searching, context.getString(R.string.settings_perm_nearby), context.getString(R.string.settings_perm_nearby_summary, desk), nearby.all(::granted)) { request(nearby) })
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            add(PermissionRow(R.drawable.sym_photo_library, context.getString(R.string.settings_perm_files), context.getString(R.string.settings_perm_files_summary, desk), granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                request(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
            })
        }
        val power = context.getSystemService(PowerManager::class.java)
        add(PermissionRow(
            R.drawable.sym_battery_full, context.getString(R.string.settings_perm_battery), context.getString(R.string.settings_perm_battery_summary),
            power?.isIgnoringBatteryOptimizations(context.packageName) == true,
            allow = open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())),
        ))
        if (needsOemBackgroundSetting(Build.MANUFACTURER)) {
            add(PermissionRow(
                R.drawable.sym_restart_alt, context.getString(R.string.settings_perm_oem), context.getString(R.string.settings_perm_oem_summary, desk),
                granted = false,
                unknown = true,
                allow = {
                    // App info (Battery usage → Allow background activity) underneath, and on
                    // ColorOS its auto-launch list on top; back from that lands on app info.
                    runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))) }
                    runCatching {
                        context.startActivity(Intent().setClassName("com.oplus.battery", "com.oplus.startupapp.view.StartupAppListActivity"))
                    }
                },
            ))
        }
        add(PermissionRow(
            R.drawable.sym_accessibility_new, context.getString(R.string.settings_perm_accessibility), context.getString(R.string.settings_perm_accessibility_summary, desk),
            MouseReceiverService.instance != null,
            allow = open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
        ))
    }
}

// ---- more ----------------------------------------------------------------------------------------------------------

/** One extra a plugin offers on the selected computer; null [open] when that plugin is off. */
class MoreExtra(@DrawableRes val icon: Int, val title: String, val summary: String, val open: (() -> Unit)?)

@Composable
fun MoreScreen(
    extras: List<MoreExtra>,
    onFeatures: (() -> Unit)?,
    onNetwork: () -> Unit,
    onAbout: () -> Unit,
    onBack: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    var revision by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_EXPRESSION") revision
    rememberResumeTick()
    val btRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) {
            prefs.edit { putBoolean(KEY_BLUETOOTH_ENABLED, true) }
            revision++
        }
    }
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
    val disabled = stringResource(R.string.settings_more_turn_on)
    // Back from the trusted networks: discovery re-reads them at once.
    val trustedNetworks = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        BackgroundService.instance?.onNetworkChange(null)
    }
    val customDevices = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { revision++ }

    SettingsColumn(stringResource(R.string.settings_more), onBack) {
        SectionLabel(stringResource(R.string.settings_more_extras))
        RowGroup {
            extras.forEach { extra ->
                GroupRow(extra.title, icon = extra.icon, subtitle = if (extra.open == null) disabled else extra.summary, enabled = extra.open != null, onClick = extra.open ?: {}) { if (extra.open != null) Chevron() }
            }
            GroupRow(
                stringResource(R.string.settings_clipboard_tile),
                icon = R.drawable.sym_content_paste,
                subtitle = stringResource(R.string.settings_clipboard_tile_summary),
                onClick = { addClipboardTile(context, onMessage) },
            )
            onFeatures?.let { GroupRow(stringResource(R.string.device_features), icon = R.drawable.sym_tune, subtitle = stringResource(R.string.settings_features_summary), onClick = it) { Chevron() } }
        }

        SectionLabel(stringResource(R.string.settings_category_connection))
        RowGroup {
            GroupRow(stringResource(R.string.trusted_networks), subtitle = stringResource(R.string.trusted_networks_desc), onClick = { trustedNetworks.launch(Intent(context, TrustedNetworksActivity::class.java)) })
            GroupRow(
                stringResource(R.string.custom_device_list),
                subtitle = stringResource(R.string.custom_devices_settings_summary, CustomDevicesActivity.getCustomDeviceList(context).size),
                onClick = { customDevices.launch(Intent(context, CustomDevicesActivity::class.java)) },
            )
            val bluetooth = prefs.getBoolean(KEY_BLUETOOTH_ENABLED, false)
            GroupRow(stringResource(R.string.setting_bluetooth), checked = bluetooth, onClick = {
                val permissions = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
                if (!bluetooth && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    !permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
                ) {
                    btRequest.launch(permissions)
                } else {
                    prefs.edit { putBoolean(KEY_BLUETOOTH_ENABLED, !bluetooth) }
                    revision++
                }
            })
            GroupRow(stringResource(R.string.setting_advanced_network), subtitle = stringResource(R.string.setting_advanced_network_summary), onClick = onNetwork) { Chevron() }
        }

        SectionLabel(stringResource(R.string.settings_category_notifications))
        RowGroup {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                GroupRow(
                    stringResource(R.string.setting_persistent_notification_oreo),
                    subtitle = stringResource(R.string.setting_persistent_notification_description),
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                .putExtra(Settings.EXTRA_CHANNEL_ID, NotificationHelper.Channels.PERSISTENT),
                        )
                    },
                ) { Icon(painterResource(R.drawable.sym_open_in_new), contentDescription = null, tint = tokens.textDim, modifier = Modifier.size(20.dp)) }
            } else {
                GroupRow(stringResource(R.string.setting_persistent_notification), checked = NotificationHelper.isPersistentNotificationEnabled(context), onClick = {
                    val on = !NotificationHelper.isPersistentNotificationEnabled(context)
                    NotificationHelper.setPersistentNotificationEnabled(context, on)
                    BackgroundService.instance?.changePersistentNotificationVisibility(on)
                    revision++
                })
            }
        }

        SectionLabel(stringResource(R.string.settings_category_help))
        RowGroup {
            GroupRow(stringResource(R.string.settings_export_logs), subtitle = stringResource(R.string.settings_export_logs_text), onClick = { exportLogs.launch(CreateFileParams("text/plain", "desklink-log.txt")) })
            val privacyUrl = stringResource(R.string.privacy_policy_url)
            GroupRow(stringResource(R.string.privacy_policy), subtitle = stringResource(R.string.privacy_policy_summary), onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, privacyUrl.toUri()))
            })
            GroupRow(stringResource(R.string.about), onClick = onAbout) { Chevron() }
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
                        revision++
                    })
                }
            }
        }
    }
}

/** Android 13+ adds the tile in one tap; before that the person drags it in from the editor. */
private fun addClipboardTile(context: Context, onMessage: (String) -> Unit) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.getSystemService(StatusBarManager::class.java)?.requestAddTileService(
            ComponentName(context, ClipboardTileService::class.java),
            context.getString(R.string.send_clipboard),
            AndroidIcon.createWithResource(context, R.drawable.ic_baseline_content_paste_24),
            context.mainExecutor,
        ) { }
    } else {
        onMessage(context.getString(R.string.settings_clipboard_tile_manual))
    }
}

// ---- advanced network -------------------------------------------------------------------------------------------------

@Composable
fun NetworkScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    var revision by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_EXPRESSION") revision
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    SettingsColumn(stringResource(R.string.setting_advanced_network), onBack) {
        SectionLabel(stringResource(R.string.settings_category_away))
        RowGroup {
            GroupRow(
                stringResource(R.string.setting_relay_url),
                subtitle = prefs.getString(RelaySettings.KEY_URL, null)?.trim()?.takeIf(String::isNotEmpty)
                    ?: BuildConfig.DEFAULT_RELAY_URL.takeIf(String::isNotEmpty)?.let { stringResource(R.string.setting_relay_url_default, it) }
                    ?: stringResource(R.string.settings_relay_not_set_up),
                monoSubtitle = true,
                onClick = { editing = RelaySettings.KEY_URL },
            )
            GroupRow(stringResource(R.string.setting_relay_use_turn), checked = prefs.getBoolean(RelaySettings.KEY_USE_TURN, true), onClick = {
                prefs.edit { putBoolean(RelaySettings.KEY_USE_TURN, !prefs.getBoolean(RelaySettings.KEY_USE_TURN, true)) }
                revision++
            })
        }
        SectionLabel(stringResource(R.string.setting_webrtc_transport))
        RowGroup {
            GroupRow(
                stringResource(R.string.setting_webrtc_stun_servers),
                subtitle = prefs.getString(DeviceHelper.KEY_WEBRTC_STUN_SERVERS_PREFERENCE, null)?.takeIf(String::isNotBlank)
                    ?: stringResource(R.string.setting_webrtc_stun_servers_description),
                onClick = { editing = DeviceHelper.KEY_WEBRTC_STUN_SERVERS_PREFERENCE },
            )
            GroupRow(
                stringResource(R.string.setting_webrtc_turn_servers),
                subtitle = prefs.getString(DeviceHelper.KEY_WEBRTC_TURN_SERVERS_PREFERENCE, null)?.takeIf(String::isNotBlank)
                    ?: stringResource(R.string.setting_webrtc_turn_servers_description),
                onClick = { editing = DeviceHelper.KEY_WEBRTC_TURN_SERVERS_PREFERENCE },
            )
        }
    }
    editing?.let { key ->
        TextDialog(
            title = stringResource(
                when (key) {
                    RelaySettings.KEY_URL -> R.string.setting_relay_url
                    DeviceHelper.KEY_WEBRTC_STUN_SERVERS_PREFERENCE -> R.string.setting_webrtc_stun_servers
                    else -> R.string.setting_webrtc_turn_servers
                },
            ),
            initial = prefs.getString(key, "").orEmpty(),
            confirm = stringResource(android.R.string.ok),
            onDismiss = { editing = null },
        ) { value ->
            editing = null
            prefs.edit {
                putString(key, value)
                // A relay address entered is a relay wanted: "Reach from anywhere" turns on with it.
                if (key == RelaySettings.KEY_URL && value.isNotBlank()) putBoolean(RelaySettings.KEY_ENABLED, true)
            }
            if (key == RelaySettings.KEY_URL) relaySettingChanged()
            revision++
        }
    }
}

// ---- quick commands -------------------------------------------------------------------------------------------------------

@Composable
fun QuickCommandsScreen(deviceId: String, deskName: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { QuickCommands.prefs(context) }
    val commands = remember(deviceId) { mutableStateListOf<String>().apply { addAll(QuickCommands.load(prefs, deviceId)) } }
    var draft by rememberSaveable { mutableStateOf("") }
    val t = tokens
    fun save() = QuickCommands.save(prefs, deviceId, commands)
    SettingsColumn(stringResource(R.string.settings_quick_commands), onBack, subtitle = stringResource(R.string.settings_quick_commands_subtitle, deskName)) {
        if (commands.isNotEmpty()) {
            RowGroup {
                commands.toList().forEachIndexed { index, command ->
                    Row(
                        Modifier.fillMaxWidth().background(t.surface).heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(command, fontFamily = MonoFamily, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { commands.removeAt(index); save() }, modifier = Modifier.size(48.dp)) {
                            Icon(painterResource(R.drawable.sym_delete), contentDescription = stringResource(R.string.settings_quick_commands_remove, command), tint = t.textDim)
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.replace("\n", "").take(QuickCommands.MAX_LENGTH) },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.settings_quick_commands_hint)) },
                textStyle = TextStyle(fontFamily = MonoFamily, fontSize = 14.sp),
                modifier = Modifier.weight(1f),
            )
            PillButton(stringResource(R.string.settings_quick_commands_add), {
                if (draft.isNotBlank()) {
                    commands.add(draft.trim())
                    val cleaned = QuickCommands.clean(commands)
                    commands.clear(); commands.addAll(cleaned)
                    save()
                    draft = ""
                }
            }, height = 48.dp, enabled = draft.isNotBlank())
        }
        PillButton(stringResource(R.string.settings_quick_commands_reset), {
            commands.clear(); commands.addAll(QuickCommands.DEFAULTS); save()
        }, style = PillStyle.Outlined, height = 44.dp)
    }
}

// ---- shared --------------------------------------------------------------------------------------------------------------------

/** Applies a relay setting once it has been saved. */
private fun relaySettingChanged() {
    android.os.Handler(android.os.Looper.getMainLooper()).post {
        BackgroundService.instance?.onRelaySettingsChanged()
    }
}

@Composable
private fun relayStatusText(): String? {
    val status = RelayLinkProvider.statusLive.value
    return when (status) {
        RelayLinkProvider.Status.CONNECTING -> stringResource(R.string.relay_status_connecting)
        RelayLinkProvider.Status.ONLINE -> stringResource(R.string.setting_relay_connected)
        RelayLinkProvider.Status.UNAVAILABLE -> stringResource(R.string.relay_status_unavailable)
        RelayLinkProvider.Status.UPDATE_REQUIRED -> stringResource(R.string.relay_status_update_required)
        else -> null
    }
}

@Composable
private fun TextDialog(
    title: String,
    initial: String,
    confirm: String,
    filter: (String) -> String = { it },
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = filter(it) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ChoiceDialog(title: String, options: List<String>, selected: Int, onDismiss: () -> Unit, onChoose: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup()) {
                options.forEachIndexed { index, option ->
                    ListItem(
                        headlineContent = { Text(option) },
                        leadingContent = { RadioButton(selected = index == selected, onClick = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.selectable(selected = index == selected, role = Role.RadioButton) { onChoose(index) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** For [FragmentActivity] callers that show a plugin's permission dialog. */
fun FragmentActivity.showPluginPermission(plugin: Plugin, optional: Boolean) {
    val dialog = if (optional) plugin.optionalPermissionExplanationDialog else plugin.permissionExplanationDialog
    dialog.show(supportFragmentManager, null)
}
