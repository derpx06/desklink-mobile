/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.compose.screen.settings

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.runtime.collectAsState
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
import kotlinx.coroutines.withContext
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
import org.desklink.mobile.ui.compose.SecondaryButton
import org.desklink.mobile.ui.compose.components.DeskSearchField
import org.desklink.mobile.ui.compose.components.HeroCard
import org.desklink.mobile.ui.compose.components.IconTile
import org.desklink.mobile.ui.compose.components.StatusDot
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
    /** Opens the computers sheet; null hides the "Switch" button. */
    onSwitchComputer: (() -> Unit)? = null,
) {
    val group = route?.let(SettingsPage::groupOf)
    when {
        route == null -> SettingsList(desk, actions, onOpen, waitingPermissions, onSwitchComputer)
        route == SettingsPage.NETWORK -> NetworkScreen(onBack)
        route == SettingsPage.PERMISSIONS -> SettingsColumn(stringResource(R.string.settings_permissions), onBack) { PermissionsSection(desk) }
        route == SettingsPage.ABOUT -> about()
        group != null -> SettingsGroupPage(group, desk, actions, onOpen, onBack)
        else -> SettingsList(desk, actions, onOpen, waitingPermissions, onSwitchComputer)
    }
}

/**
 * The list: the computer card, a search over every setting, four quick switches, then the ten groups (Advanced carries a
 * badge while permissions wait). Searching replaces the groups with matching rows, each under its page; a result opens
 * that page. Nothing here is a new setting: every row is still on its page.
 */
@Composable
private fun SettingsList(desk: DeskSettings?, actions: SettingsActions, onOpen: (String) -> Unit, waitingPermissions: Int, onSwitchComputer: (() -> Unit)?) {
    val context = LocalContext.current
    val t = tokens
    val pages = if (desk == null) SettingsPage.WITHOUT_COMPUTER else SettingsPage.entries
    val name = desk?.name ?: stringResource(R.string.settings_your_computer)
    var query by rememberSaveable { mutableStateOf("") }
    val entries = remember(name, pages) { SettingsIndex.entries(context, name, pages) }
    val results = remember(entries, query) { SettingsSearch.search(entries, query) }
    SettingsColumn(stringResource(R.string.settings), null) {
        if (desk != null) ComputerCard(desk, { onOpen(SettingsPage.CONNECTION.route) }, onSwitchComputer)
        DeskSearchField(query, { query = it }, stringResource(R.string.settings_search), stringResource(R.string.palette_clear))
        if (query.isNotBlank()) {
            if (results.isEmpty()) {
                Text(stringResource(R.string.settings_search_none, query.trim()), fontSize = 14.sp, lineHeight = 20.sp, color = t.textDim, modifier = Modifier.padding(horizontal = 6.dp).semantics { liveRegion = LiveRegionMode.Polite })
            }
            results.groupBy { it.page }.forEach { (page, found) ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel(stringResource(page.title))
                    RowGroup {
                        found.forEach { e -> GroupRow(e.title, icon = page.icon, subtitle = e.summary, onClick = { onOpen(page.route) }) { Chevron() } }
                    }
                }
            }
            return@SettingsColumn
        }
        if (desk != null) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel(stringResource(R.string.settings_quick_switches))
                RowGroup {
                    GroupRow(
                        stringResource(R.string.settings_phone_clipboard), icon = R.drawable.sym_content_paste,
                        subtitle = if (desk.clipboardOn) clipboardSummary(desk.name) else stringResource(R.string.home_clipboard_off),
                        checked = desk.clipboardOn, onClick = { actions.onPhoneClipboard(!desk.clipboardOn) },
                    )
                    GroupRow(
                        stringResource(R.string.settings_phone_notifications, desk.name), icon = R.drawable.sym_notifications,
                        subtitle = stringResource(R.string.settings_desk_notifications_summary),
                        checked = desk.notificationsOn, onClick = { actions.onPhoneNotifications(!desk.notificationsOn) },
                    )
                    GroupRow(
                        stringResource(R.string.settings_calls_on, desk.name), icon = R.drawable.sym_call,
                        subtitle = stringResource(R.string.settings_calls_on_summary, desk.name),
                        checked = desk.callsOn, onClick = { actions.onCalls(!desk.callsOn) },
                    )
                    GroupRow(
                        stringResource(R.string.settings_desk_sound, desk.name), icon = R.drawable.sym_volume_up,
                        subtitle = stringResource(R.string.settings_desk_sound_summary, desk.name),
                        checked = desk.deskSoundOn, onClick = { actions.onDeskSound(!desk.deskSoundOn) },
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel(stringResource(R.string.settings_all))
            RowGroup {
                pages.forEach { page ->
                    val waiting = page == SettingsPage.ADVANCED && waitingPermissions > 0
                    PageRow(
                        page,
                        if (waiting) androidx.compose.ui.res.pluralStringResource(R.plurals.settings_permissions_waiting, waitingPermissions, waitingPermissions) else stringResource(page.summary, name),
                        badge = if (waiting) waitingPermissions else 0,
                        onClick = { onOpen(page.route) },
                    )
                }
            }
        }
        Text(
            stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), fontSize = 12.sp, color = t.textDim,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** The computer: a tile, its name and key, how it is reached right now, and Switch (the computers sheet on Home). Opens its page. */
@Composable
private fun ComputerCard(desk: DeskSettings, onOpen: () -> Unit, onSwitch: (() -> Unit)?) {
    val t = tokens
    val status = stringResource(if (desk.ready) R.string.connection_connected else R.string.connection_offline)
    HeroCard {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = onOpen).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconTile(R.drawable.ic_device_desktop_32dp, size = 48.dp, radius = 16.dp, container = t.surface, content = t.accent)
            Column(Modifier.weight(1f)) {
                Text(desk.name, fontFamily = MonoFamily, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.settings_paired_key, desk.key), fontSize = 13.sp, lineHeight = 18.sp, color = t.textDim)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusDot(if (desk.ready) t.mintText else t.textFaint, size = 7.dp)
                    Text(status, fontSize = 12.sp, lineHeight = 16.sp, color = if (desk.ready) t.mintText else t.textDim)
                }
            }
            Chevron()
        }
        if (onSwitch != null) SecondaryButton(stringResource(R.string.settings_switch_computer), onSwitch, tonal = true, height = 48.dp, modifier = Modifier.fillMaxWidth())
    }
}

/** A group row: a 40 dp tile, title, and a line (the value in words); [badge] > 0 shows how many things wait. */
@Composable
private fun PageRow(page: SettingsPage, summary: String, badge: Int, onClick: () -> Unit) {
    val t = tokens
    Row(
        Modifier.fillMaxWidth().background(t.surface).clickable(role = Role.Button, onClick = onClick).heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconTile(page.icon, size = 40.dp, radius = 12.dp, container = if (badge > 0) t.coralContainer else t.accentContainer, content = if (badge > 0) t.coralText else t.onAccentContainer)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(page.title), fontSize = 16.sp, lineHeight = 21.sp, color = t.text)
            Text(summary, fontSize = 13.sp, lineHeight = 17.sp, color = if (badge > 0) t.coralText else t.textDim)
        }
        if (badge > 0) Text(
            badge.toString(), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = t.coralText,
            modifier = Modifier.background(t.coralContainer, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp).clearAndSetSemantics {},
        )
        Chevron()
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
private fun DeskSwitch(desk: DeskSettings, actions: SettingsActions, key: String, title: String, icon: Int, summary: String, reported: Boolean, notAllowedHere: Boolean = false) {
    val remote = desk.remote
    val live = desk.ready && remote != null
    val pending = key in desk.pending
    val on = DeskSettingsPlugin.shown(key, reported, desk.pending)
    GroupRow(
        title,
        icon = icon,
        subtitle = when {
            !live -> stringResource(R.string.settings_waiting_for, desk.name)
            notAllowedHere -> stringResource(R.string.settings_turn_on_at, desk.name)
            pending -> stringResource(R.string.settings_saving)
            else -> summary
        },
        checked = on,
        enabled = live && !pending && !notAllowedHere,
        onClick = { actions.onDeskSetting(key, !on) },
    )
}

/** A feature is on but Android has not granted what it needs: say so where the feature is, with one button. */
@Composable
private fun AllowRow(title: String, summary: String, onAllow: () -> Unit) {
    GroupRow(title, icon = R.drawable.sym_info, subtitle = summary) {
        SecondaryButton(stringResource(R.string.settings_allow), onAllow, tonal = true, height = 48.dp, modifier = Modifier.padding(start = 4.dp))
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
        // Where the computer has it too (Connection): ask it to stay awake while this phone is connected, if it allows that.
        if (desk != null) {
            DeskSwitch(desk, actions, DeskSettingsPlugin.KEEP_AWAKE, stringResource(R.string.settings_keep_awake, desk.name), R.drawable.sym_coffee, stringResource(R.string.settings_keep_awake_summary), desk.remote?.keepAwake == true, notAllowedHere = !DeskSettingsPlugin.keepAwakeUsable(desk.remote))
        }
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
    // The ringtone's name is the media provider's to give (a disk and binder read): worked out in the background, shown when it is.
    val ringtoneTitle by androidx.compose.runtime.produceState("", ringtone) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { RingtoneManager.getRingtone(context, ringtone)?.getTitle(context) }.getOrNull().orEmpty()
        }
    }
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
        // W13: phone-only; the computer shows the items while "Show <computer>'s notifications here" is on.
        GroupRow(
            stringResource(R.string.settings_activity_on, desk.name),
            icon = R.drawable.sym_notifications_active,
            subtitle = stringResource(R.string.settings_activity_on_summary),
            checked = desk.activityOn,
            onClick = { actions.onActivity(!desk.activityOn) },
        )
        // W14: the switch lives at the computer; the phone shows it, and asks for Do Not Disturb access only once it is on.
        val dndSync = desk.remote?.dndSync == true
        GroupRow(
            stringResource(R.string.settings_dnd_share, desk.name),
            icon = R.drawable.sym_notifications,
            subtitle = stringResource(if (!desk.ready || desk.remote == null) R.string.settings_waiting_for else if (dndSync) R.string.settings_dnd_share_on else R.string.settings_turn_on_at, desk.name),
            checked = dndSync,
            enabled = false,
        )
        if (dndSync && !org.desklink.mobile.plugins.battery.DndSync.canSet(context)) {
            AllowRow(stringResource(R.string.settings_dnd_access), stringResource(R.string.settings_dnd_access_summary, desk.name)) {
                runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
            }
        }
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
            // Why the phone's copies need a tap on Android 10+, what to do about it, and what happened last.
            val context = LocalContext.current
            var offer by remember { mutableStateOf(org.desklink.mobile.plugins.clipboard.ClipboardOffer.enabled(context, desk.id)) }
            GroupRow(
                stringResource(R.string.settings_clipboard_offer, desk.name),
                icon = R.drawable.sym_notifications,
                subtitle = stringResource(R.string.settings_clipboard_offer_summary),
                checked = offer,
                onClick = { offer = !offer; org.desklink.mobile.plugins.clipboard.ClipboardOffer.setEnabled(context, desk.id, offer) },
            )
            GroupRow(clipboardStatus(desk), icon = R.drawable.sym_info)
            ClipboardAutoSyncRows(desk.name, prefs, actions.onMessage, changed)
        }
        DeskSwitch(desk, actions, DeskSettingsPlugin.CLIPBOARD, stringResource(R.string.settings_desk_clipboard, desk.name), R.drawable.sym_content_paste, stringResource(R.string.settings_desk_clipboard_summary, desk.name), desk.remote?.clipboard == true)
        if (desk.clipboardOn) {
            GroupRow(stringResource(R.string.home_send_clipboard, desk.name), icon = R.drawable.sym_upload, onClick = actions.onSendClipboard) { Chevron() }
        }
    }
}

/** One line: when the phone's clipboard last reached the computer, or why it would not. */
@Composable
private fun clipboardStatus(desk: DeskSettings): String {
    val context = LocalContext.current
    return when (org.desklink.mobile.plugins.clipboard.ClipboardStatus.why(desk.clipboardOn, desk.ready)) {
        org.desklink.mobile.plugins.clipboard.ClipboardStatus.Why.SHARING_OFF -> stringResource(R.string.settings_clipboard_status_off, desk.name)
        org.desklink.mobile.plugins.clipboard.ClipboardStatus.Why.NOT_CONNECTED -> stringResource(R.string.settings_clipboard_status_offline, desk.name)
        org.desklink.mobile.plugins.clipboard.ClipboardStatus.Why.OK -> {
            val at = org.desklink.mobile.plugins.clipboard.ClipboardStatus.lastSent(context, desk.id)
            if (at == 0L) stringResource(R.string.settings_clipboard_status_never)
            else stringResource(R.string.settings_clipboard_status_last, android.text.format.DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS))
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
        if (desk.glideOn && !desk.accessibilityOn) {
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
            subtitle = stringResource(R.string.settings_scroll_reverse_summary),
            checked = prefs.getBoolean(scrollKey, false),
            onClick = { prefs.edit { putBoolean(scrollKey, !prefs.getBoolean(scrollKey, false)) }; changed() },
        )
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

/** W12: "Send new screenshots and photos to <computer>", disabled until the computer accepts them, and the two folders it watches. */
@Composable
private fun GalleryRows(desk: DeskSettings, changed: () -> Unit) {
    val context = LocalContext.current
    val accepted = desk.ready && desk.remote?.galleryAccepted == true
    val on = org.desklink.mobile.plugins.gallery.GalleryWatcher.isOn(context, desk.id)
    val shots = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && !org.desklink.mobile.plugins.gallery.GalleryWatcher.setFolder(context, desk.id, true, uri)) Toast.makeText(context, R.string.bucket_folder_failed, Toast.LENGTH_SHORT).show()
        changed()
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && !org.desklink.mobile.plugins.gallery.GalleryWatcher.setFolder(context, desk.id, false, uri)) Toast.makeText(context, R.string.bucket_folder_failed, Toast.LENGTH_SHORT).show()
        changed()
    }
    GroupRow(
        stringResource(R.string.settings_gallery_send, desk.name),
        icon = R.drawable.sym_photo_library,
        subtitle = when {
            !desk.ready || desk.remote == null -> stringResource(R.string.settings_waiting_for, desk.name)
            !accepted -> stringResource(R.string.settings_turn_on_at, desk.name)
            else -> stringResource(R.string.settings_gallery_send_summary)
        },
        checked = on,
        enabled = accepted || on, // turning it off is always possible
        onClick = { org.desklink.mobile.plugins.gallery.GalleryWatcher.setOn(context, desk.id, !on); changed() },
    )
    if (on) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val none = stringResource(R.string.settings_gallery_folder_none)
        val lost = stringResource(R.string.settings_gallery_folder_lost)
        fun shown(key: String, isShots: Boolean) =
            if (org.desklink.mobile.plugins.gallery.GalleryWatcher.isLost(context, desk.id, isShots)) lost
            else prefs.getString(key, null)?.let { Uri.parse(it).lastPathSegment?.substringAfterLast(':') } ?: none
        GroupRow(
            stringResource(R.string.settings_gallery_screenshots), icon = R.drawable.sym_folder_zip,
            subtitle = shown(org.desklink.mobile.plugins.gallery.GalleryWatcher.shotsKey(desk.id), true),
            onClick = { runCatching { shots.launch(org.desklink.mobile.plugins.gallery.GalleryWatcher.pickerStart("Pictures/Screenshots")) } },
        ) { Chevron() }
        GroupRow(
            stringResource(R.string.settings_gallery_camera), icon = R.drawable.sym_folder_zip,
            subtitle = shown(org.desklink.mobile.plugins.gallery.GalleryWatcher.cameraKey(desk.id), false),
            onClick = { runCatching { camera.launch(org.desklink.mobile.plugins.gallery.GalleryWatcher.pickerStart("DCIM/Camera")) } },
        ) { Chevron() }
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
            GroupRow(stringResource(R.string.settings_bucket_with, desk.name), icon = R.drawable.sym_inventory, subtitle = stringResource(R.string.more_bucket_summary), onClick = actions.onOpenBucket) { Chevron() }
        }
        GroupRow(
            stringResource(R.string.settings_downloads_folder),
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
        if (desk != null) GalleryRows(desk, changed)
        if (desk != null) {
            GroupRow(
                stringResource(R.string.settings_contacts_share, desk.name),
                icon = R.drawable.sym_badge,
                subtitle = when {
                    !desk.contactsOn -> stringResource(R.string.settings_contacts_share_off, desk.name)
                    !desk.contactsReadable -> stringResource(R.string.settings_contacts_share_permission, desk.name)
                    org.desklink.mobile.plugins.contacts.ContactsPlugin.readFailed(desk.id) -> stringResource(R.string.settings_contacts_share_failed, desk.name)
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
        // First, as on the computer (read-only here: the password is set there).
        if (desk != null) {
            remote?.sessionPasswordSet?.let { set ->
                GroupRow(
                    stringResource(R.string.settings_session_password),
                    icon = R.drawable.sym_fingerprint,
                    subtitle = if (!live) waiting else stringResource(if (set) R.string.settings_session_password_set else R.string.settings_session_password_unset, desk.name),
                    enabled = live,
                )
            }
        }
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
            GroupRow(
                stringResource(R.string.device_encryption),
                icon = R.drawable.sym_lock,
                subtitle = stringResource(R.string.settings_paired_key, desk.key),
                onClick = actions.onEncryptionInfo,
            ) { Chevron() }
        }
        val privacyUrl = stringResource(R.string.privacy_policy_url)
        GroupRow(stringResource(R.string.privacy_policy), icon = R.drawable.sym_open_in_new, subtitle = stringResource(R.string.privacy_policy_summary), onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, privacyUrl.toUri())) }
        })
        // Unpair is the last row of Privacy & security, as on the computer.
        UnpairRow(desk, actions)
    }
}

@Composable
private fun UnpairRow(desk: DeskSettings?, actions: SettingsActions) {
    val t = tokens
    var confirmUnpair by rememberSaveable { mutableStateOf(false) }
    if (desk == null) return
    GroupRow(
        stringResource(R.string.settings_unpair_row, desk.name),
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
    val failed = stringResource(R.string.export_logs_failed)
    val exportLogs = rememberLauncherForActivityResult(CreateFileResultContract()) { uri: Uri? ->
        val output = uri?.let { context.contentResolver.openOutputStream(it) } ?: return@rememberLauncherForActivityResult
        CoroutineScope(Dispatchers.IO + org.desklink.mobile.helpers.Guarded.handler("background")).launch {
            val ok = runCatching {
                val process = Runtime.getRuntime().exec(org.desklink.mobile.helpers.LogExport.command())
                output.use {
                    org.desklink.mobile.helpers.LogExport.export(
                        "DeskLink ${BuildConfig.VERSION_NAME}\nAndroid ${Build.VERSION.RELEASE} (${Build.MANUFACTURER} ${Build.MODEL})",
                        InputStreamReader(process.inputStream, Charsets.UTF_8).buffered().lineSequence(), it,
                    )
                }
            }.isSuccess
            if (!ok) withContext(Dispatchers.Main) { android.widget.Toast.makeText(context, failed, android.widget.Toast.LENGTH_LONG).show() }
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
    val stopped by org.desklink.mobile.helpers.Guarded.stopped.collectAsState()
    if (stopped.isNotEmpty()) {
        // A feature threw and was caught (its report is kept): one tap reloads what is turned on.
        RowGroup {
            GroupRow(
                stringResource(R.string.feature_stopped_title),
                icon = R.drawable.sym_refresh,
                subtitle = stringResource(R.string.feature_stopped_summary, stopped.sorted().joinToString(", ")),
                onClick = { org.desklink.mobile.helpers.Guarded.restartStopped() },
            ) { Chevron() }
        }
    }
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
