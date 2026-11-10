/*
 * SPDX-FileCopyrightText: 2023 Albert Vaca Cintora <albertvaka@gmail.com>
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.screen.settings

import org.desklink.mobile.ui.compose.components.DeskTextField
import org.desklink.mobile.ui.compose.components.DialogAction
import org.desklink.mobile.ui.compose.components.DeskDialog
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
import org.desklink.mobile.ui.compose.components.dismissKeyboardOnScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import org.desklink.mobile.plugins.mousereceiver.AccessibilitySetupActivity
import org.desklink.mobile.ui.CustomDevicesActivity
import org.desklink.mobile.ui.ThemeUtil
import org.desklink.mobile.ui.TrustedNetworksActivity
import org.desklink.mobile.ui.compose.MonoFamily
import org.desklink.mobile.ui.compose.components.GroupRow
import org.desklink.mobile.ui.compose.components.LaptopGlyph
import org.desklink.mobile.ui.compose.components.LargeTitle
import org.desklink.mobile.ui.compose.components.RowGroup
import org.desklink.mobile.ui.compose.components.ScreenHeader
import org.desklink.mobile.ui.compose.components.SectionLabel
import org.desklink.mobile.ui.compose.tokens
import java.io.InputStreamReader

const val KEY_CONNECT_ON_STARTUP = "connect_on_startup"
const val KEY_APP_THEME = "theme_pref"

/** The selected computer's settings, as the "<desk>" section shows them. */
data class DeskSettings(
    val id: String,
    val name: String,
    /** This phone's clipboard plugin (phone-owned; the desk's own switch is in [remote]). */
    val clipboardOn: Boolean,
    /** This phone's notifications plugin (phone-owned). */
    val notificationsOn: Boolean,
    /** "3C:91:0B:7E", the start of its certificate fingerprint. */
    val key: String,
    /** Plugins this computer uses that wait for a permission, as (name, open its dialog). */
    val pluginPermissions: List<Pair<String, () -> Unit>> = emptyList(),
    /** What the desk itself reports (`desklink.settings`); null from an older desk or before it arrives. */
    val remote: org.desklink.mobile.plugins.desksettings.DeskSettingsState? = null,
    /** This phone's media is shown and controlled on the desk. */
    val phoneMediaOn: Boolean = false,
    /** Desk-side switches the desk has not confirmed yet. */
    val pending: Map<String, Boolean> = emptyMap(),
    /** The feature link is up: only then are desk-owned rows live. */
    val ready: Boolean = true,
    /** "Calls on <desk>" (phone-owned, off by default) and "Show caller on <desk>". */
    val callsOn: Boolean = false,
    val showCaller: Boolean = false,
    /** The desk's notifications shown on this phone (phone-owned, off by default). */
    val deskNotificationsOn: Boolean = false,
    /** F4: "Let <desk> control this phone" (glide), off by default. */
    val glideOn: Boolean = false,
    val accessibilityOn: Boolean = false,
    /** F1: "Play <desk>'s sound here" and its volume (phone settings). */
    val deskSoundOn: Boolean = false,
    val deskSoundVolume: Int = 100,
    /** F1: the desk may start its sound here unasked (on by default). */
    val deskMayPlay: Boolean = true,
    /** The microphone (or sound) consent given to this computer is remembered: it may start them without asking. */
    val micConsent: Boolean = false,
    /** F5: "Share contacts with <desk>" (phone-owned, off by default), and whether Android lets DeskLink read them. */
    val contactsOn: Boolean = false,
    val contactsReadable: Boolean = true,
    /** W13: "Show ongoing items on <desk>" (phone-owned, off by default). */
    val activityOn: Boolean = false,
)

@androidx.compose.runtime.Stable
class SettingsActions(
    /** This phone's own clipboard and notifications plugins (phone settings). */
    val onPhoneClipboard: (Boolean) -> Unit,
    val onPhoneNotifications: (Boolean) -> Unit,
    /** A desk-side switch (`desklink.settings.request {set: {key: value}}`). */
    val onDeskSetting: (String, Boolean) -> Unit,
    val onPhoneMedia: (Boolean) -> Unit,
    val onCalls: (Boolean) -> Unit = {},
    val onShowCaller: (Boolean) -> Unit = {},
    val onDeskNotifications: (Boolean) -> Unit = {},
    val onGlide: (Boolean) -> Unit = {},
    val onDeskSound: (Boolean) -> Unit = {},
    val onDeskSoundVolume: (Int) -> Unit = {},
    val onDeskMayPlay: (Boolean) -> Unit = {},
    val onMicConsent: (Boolean) -> Unit = {},
    val onContacts: (Boolean) -> Unit = {},
    val onActivity: (Boolean) -> Unit = {},
    val onPairAnother: () -> Unit = {},
    val onSendClipboard: () -> Unit = {},
    val onChooseNotificationApps: () -> Unit = {},
    val onAddTiles: () -> Unit = {},
    val onOpenBucket: () -> Unit = {},
    val onUnpair: () -> Unit,
    val onEncryptionInfo: () -> Unit,
    /** The theme or wallpaper colours changed: the activity is recreated to apply them. */
    val onRecreate: () -> Unit,
    val onMessage: (String) -> Unit,
)

@Composable
internal fun SettingsColumn(title: String, onBack: (() -> Unit)?, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val t = tokens
    Column(Modifier.fillMaxSize().background(t.bg).statusBarsPadding()) {
        // A tab's first page has no back arrow: the bar below is how you leave it.
        // A pushed page: back arrow and title on one row (the design's sub-page bar).
        if (onBack != null) ScreenHeader(onBack, title = title) else Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxSize().dismissKeyboardOnScroll().verticalScroll(rememberScrollState())) {
            if (onBack == null) LargeTitle(title, subtitle = subtitle)
            else if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = t.textDim, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 14.dp))
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                content()
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars).padding(bottom = 10.dp))
            }
        }
    }
}

/** Bumps on every resume: permissions and system settings change while the person is away. */
@Composable
internal fun rememberResumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    return tick
}

// ---- settings ------------------------------------------------------------------------------------------------

/** The desk's sound here: 0–100 %, applied as the slider moves. */
@Composable
internal fun DeskSoundVolume(volume: Int, onChange: (Int) -> Unit) {
    var value by remember(volume) { mutableFloatStateOf(volume.toFloat()) }
    val label = stringResource(R.string.settings_desk_sound_volume)
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, color = tokens.textDim)
        androidx.compose.material3.Slider(
            value = value,
            onValueChange = { value = it; onChange(it.toInt()) },
            valueRange = 0f..100f,
            modifier = Modifier.weight(1f).padding(start = 12.dp).semantics { contentDescription = label },
        )
    }
}

/** Brands whose adb `pm grant` needs a developer-options switch first. */
private fun needsOemSwitch(): Boolean =
    Build.MANUFACTURER.lowercase() in setOf("oppo", "realme", "oneplus", "xiaomi", "redmi", "poco")

@androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
@Composable
internal fun ClipboardAutoSyncRows(deskName: String, prefs: android.content.SharedPreferences, onMessage: (String) -> Unit, changed: () -> Unit) {
    val context = LocalContext.current
    val listener = org.desklink.mobile.plugins.clipboard.ClipboardListener
    val resumed = rememberResumeTick()
    // Release builds do not declare the permission, so they have no such switch (Play rejects READ_LOGS).
    if (!listener.workaroundDeclared(context)) return
    if (!listener.readLogsGranted(context)) {
        val command = listener.grantCommand(context)
        val copied = stringResource(R.string.settings_clipboard_auto_copied)
        GroupRow(
            stringResource(R.string.settings_clipboard_auto),
            icon = R.drawable.sym_refresh,
            // ColorOS/OxygenOS and MIUI refuse `pm grant` from adb until a developer switch is on.
            subtitle = listOfNotNull(
                stringResource(R.string.settings_clipboard_auto_grant, command),
                stringResource(R.string.settings_clipboard_auto_grant_oem).takeIf { needsOemSwitch() },
            ).joinToString("\n"),
            onClick = {
                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("adb", command))
                onMessage(copied)
            },
        )
        return
    }
    val on = listener.autoSyncActive(context)
    GroupRow(
        stringResource(R.string.settings_clipboard_auto),
        icon = R.drawable.sym_refresh,
        subtitle = stringResource(if (on) R.string.settings_clipboard_auto_on else R.string.settings_clipboard_auto_off, deskName),
        checked = on,
        onClick = {
            // The one place that may start the watch (and so the one place Android may ask about logs).
            listener.instance(context).setAutoSync(!on)
            changed()
        },
    )
    if (on && !android.provider.Settings.canDrawOverlays(context)) {
        GroupRow(
            stringResource(R.string.settings_clipboard_overlay),
            icon = R.drawable.sym_open_in_new,
            subtitle = stringResource(R.string.settings_clipboard_overlay_summary),
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}")),
                    )
                }
            },
        ) { Chevron() }
    }
}

/** Exactly when the clipboard syncs: Android 10+ keeps background apps off it unless automatic sync is on. */
@Composable
fun clipboardSummary(deskName: String): String {
    val context = LocalContext.current
    return when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> stringResource(R.string.home_clipboard_on)
        org.desklink.mobile.plugins.clipboard.ClipboardListener.autoSyncActive(context) -> stringResource(R.string.home_clipboard_on_auto, deskName)
        else -> stringResource(R.string.home_clipboard_on_q, deskName)
    }
}

@Composable
internal fun Chevron() = Icon(painterResource(R.drawable.sym_chevron_right), contentDescription = null, tint = tokens.textDim, modifier = Modifier.size(20.dp))

// ---- permissions ------------------------------------------------------------------------------------------------

/**
 * What the person's choices need: opt-in permissions (accessibility, notification access, calls, contacts) count only when a feature that uses them is on.
 * Camera and microphone are asked in context (the scan screen, "Phone as microphone"), so only the first-run setup lists them.
 */
internal data class PermissionNeeds(
    val accessibility: Boolean = true,
    val notificationAccess: Boolean = true,
    val calls: Boolean = true,
    val contacts: Boolean = true,
    val camera: Boolean = true,
    val microphone: Boolean = true,
) {
    companion object {
        /** Before pairing nothing is turned on yet: everything a feature can use, except accessibility, which is asked when the desk first controls the phone. */
        val setup = PermissionNeeds(accessibility = false)

        fun of(context: Context): PermissionNeeds {
            val devices = runCatching { org.desklink.mobile.DeskLinkApplication.getInstance().devices.values.filter { it.isPaired } }.getOrDefault(emptyList())
            fun any(check: (org.desklink.mobile.Device) -> Boolean) = devices.any { runCatching { check(it) }.getOrDefault(false) }
            fun plugin(cls: Class<out org.desklink.mobile.plugins.Plugin>) = org.desklink.mobile.plugins.Plugin.getPluginKey(cls)
            return PermissionNeeds(
                accessibility = any { org.desklink.mobile.plugins.inputdevicesreceiver.Glide.allowed(context, it.deviceId) },
                notificationAccess = any {
                    it.isPluginEnabled(plugin(org.desklink.mobile.plugins.notifications.NotificationsPlugin::class.java)) ||
                        it.isPluginEnabled(plugin(org.desklink.mobile.plugins.mprisreceiver.MprisReceiverPlugin::class.java))
                },
                calls = any { it.isPluginEnabled(plugin(org.desklink.mobile.plugins.telephony.TelephonyPlugin::class.java)) },
                contacts = any {
                    it.isPluginEnabled(plugin(org.desklink.mobile.plugins.contacts.ContactsPlugin::class.java)) ||
                        // The application context: an activity's own would check the disk for this preferences file again, on the main thread.
                        org.desklink.mobile.plugins.telephony.TelephonyPlugin.showCaller(context.applicationContext, it.deviceId)
                },
                camera = false,
                microphone = false,
            )
        }
    }
}

internal class PermissionRow(
    @DrawableRes val icon: Int,
    val title: String,
    val summary: String,
    val granted: Boolean,
    /** Android cannot tell whether it is done (OEM settings): an "Open" button, never counted as waiting. */
    val unknown: Boolean = false,
    /** False when nothing the person turned on uses it and it is not granted: the post-pairing checklist leaves it out. */
    val relevant: Boolean = true,
    /** Stable name of the row ("camera", "battery"…), for ordering and for remembering which rows were missing. */
    val id: String = title,
    /** The runtime permissions [allow] asks for; empty for a special access, which opens a system page instead. */
    val permissions: List<String> = emptyList(),
    /** Android will not ask for [permissions] any more: [allow] opens the app's permission page. */
    val blocked: Boolean = false,
    val allow: () -> Unit,
)

/** OEMs whose battery managers freeze or kill a foreground service with the screen off. */
fun needsOemBackgroundSetting(manufacturer: String): Boolean =
    manufacturer.lowercase() in setOf("oppo", "realme", "oneplus", "xiaomi", "redmi", "poco", "vivo", "iqoo", "huawei", "honor", "meizu", "asus", "tecno", "infinix")

/** What a permission button does: asks (or explains first) and, once answered, refreshes [asked] so rows read the new state. */
internal class PermissionAsker(val ask: (Array<String>) -> Unit, val asked: Int)

/** The launchers behind every "Allow" button. Nothing is asked until [PermissionAsker.ask] is called from a tap. */
@Composable
internal fun rememberPermissionAsker(): PermissionAsker {
    val context = LocalContext.current
    var asked by remember { mutableIntStateOf(0) }
    // Android 10 also wants a folder chosen by hand before received files can be written.
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) org.desklink.mobile.plugins.share.ShareDestination.saveStorageLocationPreference(context, uri)
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        asked++
        org.desklink.mobile.helpers.PermissionRecovery.onResult(context, result.values.any { it })
        if (result.values.any { it }) {
            // Plugins that waited for the permission (Share, Find my phone…) load now.
            CoroutineScope(Dispatchers.IO + org.desklink.mobile.helpers.Guarded.handler("background")).launch {
                org.desklink.mobile.DeskLinkApplication.getInstance().devices.values.forEach(org.desklink.mobile.Device::reloadPluginsFromSettings)
            }
        }
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && result[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true) folder.launch(null)
    }
    return PermissionAsker({ permissions -> org.desklink.mobile.helpers.PermissionRecovery.requestOrExplain(context, permissions, request::launch) }, asked)
}

@Composable
internal fun PermissionsSection(desk: DeskSettings?) {
    val context = LocalContext.current
    val t = tokens
    val tick = rememberResumeTick()
    val asker = rememberPermissionAsker()
    val asked = asker.asked
    val deskName = desk?.name ?: stringResource(R.string.settings_your_computer)
    val tapToGrant = stringResource(R.string.device_tap_permission)
    val ask = asker.ask
    val rows = remember(tick, asked, desk) { permissionRows(context, deskName, ask) } +
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
                    org.desklink.mobile.ui.compose.SecondaryButton(stringResource(row.actionLabel()), row.allow, tonal = true, height = 48.dp, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}

/** How many permissions a feature needs that are still missing (the amber dot on the Settings tab). */
fun waitingPermissions(context: Context, deskName: String, extra: Int = 0): Int =
    permissionRows(context, deskName, request = { }).count { !it.granted && !it.unknown } + extra

@Composable
private fun pluralsString(id: Int, count: Int) = androidx.compose.ui.res.pluralStringResource(id, count, count)

/** What a row's button says: Open for a page Android cannot report on, Open settings once Android stops asking, else Allow. */
@androidx.annotation.StringRes
internal fun PermissionRow.actionLabel(): Int = when {
    unknown -> R.string.settings_open
    blocked -> R.string.perm_open_settings
    else -> R.string.settings_allow
}

/** The permissions DeskLink uses here, each with the system screen that grants it. [sdk] is the running release (a parameter so tests can pick one). */
internal fun permissionRows(
    context: Context,
    desk: String,
    request: (Array<String>) -> Unit,
    needs: PermissionNeeds = PermissionNeeds.of(context),
    sdk: Int = Build.VERSION.SDK_INT,
): List<PermissionRow> {
    fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    /** A row that asks Android for [permissions] in one dialog. */
    fun runtime(id: String, @DrawableRes icon: Int, title: String, summary: String, vararg permissions: String) = PermissionRow(
        icon, title, summary, permissions.all(::granted),
        id = id,
        permissions = permissions.toList(),
        blocked = org.desklink.mobile.helpers.PermissionRecovery.blocked(context, permissions.toList()).isNotEmpty(),
    ) { request(arrayOf(*permissions)) }
    fun open(intent: Intent) = {
        runCatching { context.startActivity(intent) }.onFailure {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        }
        Unit
    }
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    return buildList {
        if (sdk >= Build.VERSION_CODES.TIRAMISU) {
            add(runtime("notifications", R.drawable.sym_notifications_active, context.getString(R.string.settings_perm_notifications), context.getString(R.string.settings_perm_notifications_summary), Manifest.permission.POST_NOTIFICATIONS))
        }
        if (needs.camera) {
            add(runtime("camera", R.drawable.sym_qr_code_scanner, context.getString(R.string.perm_camera), context.getString(R.string.perm_camera_summary), Manifest.permission.CAMERA))
        }
        add(PermissionRow(
            R.drawable.sym_notifications, context.getString(R.string.settings_perm_notification_access), context.getString(R.string.settings_perm_notification_access_summary, desk),
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName),
            // Not counted as waiting while nothing the person turned on needs it.
            unknown = !needs.notificationAccess && !NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName),
            allow = open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)),
            relevant = needs.notificationAccess || NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName),
            id = "notification_access",
        ))
        if (needs.calls) {
            val calls = buildList {
                add(Manifest.permission.READ_PHONE_STATE)
                if (sdk >= Build.VERSION_CODES.O) add(Manifest.permission.ANSWER_PHONE_CALLS)
                add(Manifest.permission.CALL_PHONE)
            }.toTypedArray()
            add(runtime("calls", R.drawable.sym_call, context.getString(R.string.settings_perm_calls), context.getString(R.string.settings_perm_calls_summary, desk), *calls))
        }
        if (needs.contacts) {
            add(runtime("contacts", R.drawable.sym_person, context.getString(R.string.settings_perm_contacts), context.getString(R.string.settings_perm_contacts_summary, desk), Manifest.permission.READ_CONTACTS))
        }
        if (needs.microphone) {
            add(runtime("microphone", R.drawable.sym_mic, context.getString(R.string.perm_microphone), context.getString(R.string.perm_microphone_summary, desk), Manifest.permission.RECORD_AUDIO))
        }
        if (sdk < Build.VERSION_CODES.R) {
            add(runtime("files", R.drawable.sym_photo_library, context.getString(R.string.settings_perm_files), context.getString(R.string.settings_perm_files_summary, desk), Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
        val power = context.getSystemService(PowerManager::class.java)
        add(PermissionRow(
            R.drawable.sym_battery_full, context.getString(R.string.settings_perm_battery), context.getString(R.string.settings_perm_battery_summary),
            power?.isIgnoringBatteryOptimizations(context.packageName) == true,
            allow = open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())),
            id = "battery",
        ))
        if (needsOemBackgroundSetting(Build.MANUFACTURER)) {
            add(PermissionRow(
                R.drawable.sym_restart_alt, context.getString(R.string.settings_perm_oem), context.getString(R.string.settings_perm_oem_summary, desk),
                granted = false,
                unknown = true,
                id = "oem",
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
            AccessibilitySetupActivity.running(),
            unknown = !needs.accessibility && !AccessibilitySetupActivity.running(),
            allow = { AccessibilitySetupActivity.open(context) },
            relevant = needs.accessibility || AccessibilitySetupActivity.running(),
            id = "accessibility",
        ))
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
            GroupRow(stringResource(R.string.settings_use_relay_fallback), subtitle = stringResource(R.string.settings_use_relay_fallback_summary), checked = prefs.getBoolean(RelaySettings.KEY_USE_TURN, true), onClick = {
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

// ---- shared --------------------------------------------------------------------------------------------------------------------

/** Applies a relay setting once it has been saved. */
internal fun relaySettingChanged() {
    android.os.Handler(android.os.Looper.getMainLooper()).post {
        BackgroundService.instance?.onRelaySettingsChanged()
    }
}

@Composable
internal fun relayStatusText(): String? {
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
internal fun TextDialog(
    title: String,
    initial: String,
    confirm: String,
    filter: (String) -> String = { it },
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    DeskDialog(
        onDismissRequest = onDismiss,
        title = title,
        text = { DeskTextField(text, { text = filter(it) }, Modifier.fillMaxWidth(), label = title, singleLine = true) },
        confirm = DialogAction(confirm) { onConfirm(text.trim()) },
        dismiss = DialogAction(stringResource(R.string.cancel)) { onDismiss() },
    )
}

@Composable
internal fun ChoiceDialog(title: String, options: List<String>, selected: Int, onDismiss: () -> Unit, onChoose: (Int) -> Unit) {
    DeskDialog(
        onDismissRequest = onDismiss,
        title = title,
        // The dialog scrolls its body: four options at a large font size are taller than a phone held sideways.
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
        dismiss = DialogAction(stringResource(R.string.cancel)) { onDismiss() },
    )
}

/** For [FragmentActivity] callers that show a plugin's permission dialog. */
fun FragmentActivity.showPluginPermission(plugin: Plugin, optional: Boolean) {
    // Accessibility needs the steps past Android 13's "Restricted setting", not a bare settings link.
    if (!optional && (plugin is org.desklink.mobile.plugins.mousereceiver.MouseReceiverPlugin ||
            plugin is org.desklink.mobile.plugins.lockdevice.LockDevicePlugin)
    ) return AccessibilitySetupActivity.open(this)
    val dialog = if (optional) plugin.optionalPermissionExplanationDialog else plugin.permissionExplanationDialog
    dialog.show(supportFragmentManager, null)
}
