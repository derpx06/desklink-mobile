/*
 * SPDX-FileCopyrightText: 2023 Albert Vaca Cintora <albertvaka@gmail.com>
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/
package org.desklink.mobile.ui

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.google.android.material.snackbar.Snackbar
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
import org.desklink.mobile.ui.ThemeUtil.applyTheme
import org.desklink.mobile.ui.compose.DeskLinkTheme
import org.desklink.mobile.ui.compose.components.DeskCard
import org.desklink.mobile.ui.compose.components.SectionHeader
import org.desklink.mobile.ui.compose.tokens
import androidx.compose.foundation.layout.Arrangement
import java.io.InputStreamReader

/**
 * The app's settings as plain Material list rows, grouped under section titles. The network
 * details live one level down, under "Advanced network". Every row reads and writes the same
 * preference keys the rest of the app reads.
 */
class SettingsFragment : Fragment() {
    private val prefs: SharedPreferences by lazy { PreferenceManager.getDefaultSharedPreferences(requireContext()) }

    /** Bumped whenever something outside Compose changed what a row shows. */
    private var revision by mutableIntStateOf(0)
    private var advanced by mutableStateOf(false)
    private var relayStatus by mutableStateOf(RelayLinkProvider.Status.OFF)

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }

    val activityLauncherWithDevicesByIpRefresh = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        revision++
    }

    val activityLauncherWithNetworkRefresh = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        BackgroundService.instance?.onNetworkChange(null)
    }

    private val exportLogs: ActivityResultLauncher<CreateFileParams> = registerForActivityResult(
        CreateFileResultContract()
    ) { uri: Uri? ->
        val output = uri?.let { context?.contentResolver?.openOutputStream(uri) } ?: return@registerForActivityResult
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DeskLinkTheme(context) {
                    BackHandler(enabled = advanced) { showAdvanced(false) }
                    if (advanced) AdvancedNetworkScreen() else SettingsScreen()
                }
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        showAdvanced(advanced)
        RelayLinkProvider.statusLive.observe(viewLifecycleOwner) { relayStatus = it }
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onResume() {
        super.onResume()
        revision++ // permissions and notification settings may have changed elsewhere
    }

    override fun onDestroyView() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onDestroyView()
    }

    private fun showAdvanced(on: Boolean) {
        advanced = on
        (activity as? MainActivity)?.supportActionBar?.apply {
            setTitle(if (on) R.string.setting_advanced_network else R.string.settings)
            subtitle = null
        }
    }

    // ---- screens ------------------------------------------------------------------------------------

    @Composable
    private fun SettingsScreen() {
        @Suppress("UNUSED_VARIABLE") val r = revision // re-read the preferences on every revision
        val context = requireContext()
        var editingName by remember { mutableStateOf(false) }
        var choosingTheme by remember { mutableStateOf(false) }

        SettingsColumn {
            Group(stringResource(R.string.settings_category_general)) {
            Row(
                title = stringResource(R.string.settings_rename),
                summary = DeviceHelper.getDeviceName(context),
                onClick = { editingName = true },
            )
            val themeValues = stringArrayResource(R.array.theme_list_values)
            val themeNames = stringArrayResource(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) R.array.theme_list_v28 else R.array.theme_list)
            val theme = prefs.getString(KEY_APP_THEME, ThemeUtil.DEFAULT_MODE) ?: ThemeUtil.DEFAULT_MODE
            Row(
                title = stringResource(R.string.setting_appearance),
                summary = themeNames.getOrNull(themeValues.indexOf(theme)),
                onClick = { choosingTheme = true },
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SwitchRow(
                    title = stringResource(R.string.setting_dynamic_colors),
                    checked = prefs.getBoolean(ThemeUtil.KEY_DYNAMIC_COLORS, false),
                ) { on ->
                    prefs.edit { putBoolean(ThemeUtil.KEY_DYNAMIC_COLORS, on) }
                    // The XML theme reads it at creation: recreate so this screen changes too.
                    view?.post { activity?.recreate() }
                }
            }
            }

            Group(stringResource(R.string.settings_category_connection)) {
            Row(
                title = stringResource(R.string.trusted_networks),
                summary = stringResource(R.string.trusted_networks_desc),
                onClick = { activityLauncherWithNetworkRefresh.launch(Intent(context, TrustedNetworksActivity::class.java)) },
            )
            Row(
                title = stringResource(R.string.custom_device_list),
                summary = getString(R.string.custom_devices_settings_summary, CustomDevicesActivity.getCustomDeviceList(context).size),
                onClick = { activityLauncherWithDevicesByIpRefresh.launch(Intent(context, CustomDevicesActivity::class.java)) },
            )
            SwitchRow(
                title = stringResource(R.string.setting_bluetooth),
                // What BluetoothLinkProvider acts on, which is off until turned on here.
                checked = prefs.getBoolean(KEY_BLUETOOTH_ENABLED, false),
                onChange = ::setBluetooth,
            )
            }

            Group(stringResource(R.string.settings_category_away)) {
            val relayOn = prefs.getBoolean(RelaySettings.KEY_ENABLED, true)
            SwitchRow(
                title = stringResource(R.string.setting_relay_enabled),
                summary = listOfNotNull(stringResource(R.string.setting_relay_summary), relayStatusText().takeIf { relayOn }).joinToString("\n"),
                checked = relayOn,
            ) { on ->
                prefs.edit { putBoolean(RelaySettings.KEY_ENABLED, on) }
                relaySettingChanged()
            }
            Row(
                title = stringResource(R.string.setting_advanced_network),
                summary = stringResource(R.string.setting_advanced_network_summary),
                onClick = { showAdvanced(true) },
            )
            }

            Group(stringResource(R.string.settings_category_notifications)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Row(
                    title = stringResource(R.string.setting_persistent_notification_oreo),
                    summary = stringResource(R.string.setting_persistent_notification_description),
                    trailingIcon = R.drawable.sym_open_in_new,
                    onClick = {
                        // Straight to this channel's own settings, not the app's list of channels.
                        startActivity(
                            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                .putExtra(Settings.EXTRA_CHANNEL_ID, NotificationHelper.Channels.PERSISTENT),
                        )
                    },
                )
            } else {
                SwitchRow(
                    title = stringResource(R.string.setting_persistent_notification),
                    checked = NotificationHelper.isPersistentNotificationEnabled(context),
                ) { on ->
                    NotificationHelper.setPersistentNotificationEnabled(context, on)
                    BackgroundService.instance?.changePersistentNotificationVisibility(on)
                    revision++
                }
            }
            }

            Group(stringResource(R.string.settings_category_help)) {
            Row(
                title = stringResource(R.string.settings_export_logs),
                summary = stringResource(R.string.settings_export_logs_text),
                onClick = { exportLogs.launch(CreateFileParams("text/plain", "desklink-log.txt")) },
            )
            Row(
                title = stringResource(R.string.privacy_policy),
                summary = stringResource(R.string.privacy_policy_summary),
                trailingIcon = R.drawable.sym_open_in_new,
                onClick = { startActivity(Intent(Intent.ACTION_VIEW, getString(R.string.privacy_policy_url).toUri())) },
            )
            }

            if (BuildConfig.DEBUG) {
                Group(stringResource(R.string.settings_category_debug)) {
                    DebugSwitch(RelaySettings.KEY_FORCE_RELAY, R.string.setting_relay_force_relay, R.string.setting_relay_force_relay_description)
                    DebugSwitch(RelaySettings.KEY_FORCE_TURN, R.string.setting_relay_force_turn, R.string.setting_relay_force_turn_description)
                }
            }
        }

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
                    view?.let { Snackbar.make(it, R.string.invalid_device_name, Snackbar.LENGTH_LONG).show() }
                } else {
                    // MainActivity listens for this key and announces the new name.
                    prefs.edit { putString(DeviceHelper.KEY_DEVICE_NAME_PREFERENCE, name) }
                }
            }
        }
        if (choosingTheme) {
            val values = stringArrayResource(R.array.theme_list_values)
            val names = stringArrayResource(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) R.array.theme_list_v28 else R.array.theme_list)
            ChoiceDialog(
                title = stringResource(R.string.setting_appearance),
                options = names.toList(),
                selected = values.indexOf(prefs.getString(KEY_APP_THEME, ThemeUtil.DEFAULT_MODE)),
                onDismiss = { choosingTheme = false },
            ) { index ->
                choosingTheme = false
                val value = values[index]
                prefs.edit { putString(KEY_APP_THEME, value) }
                applyTheme(value)
            }
        }
    }

    @Composable
    private fun AdvancedNetworkScreen() {
        @Suppress("UNUSED_VARIABLE") val r = revision
        var editing by remember { mutableStateOf<String?>(null) }
        SettingsColumn {
            Group(stringResource(R.string.settings_category_away)) {
            Row(
                title = stringResource(R.string.setting_relay_url),
                summary = prefs.getString(RelaySettings.KEY_URL, null)?.trim()?.takeIf(String::isNotEmpty)
                    ?: stringResource(R.string.setting_relay_url_default, BuildConfig.DEFAULT_RELAY_URL),
                onClick = { editing = RelaySettings.KEY_URL },
            )
            SwitchRow(
                title = stringResource(R.string.setting_relay_use_turn),
                checked = prefs.getBoolean(RelaySettings.KEY_USE_TURN, true),
            ) { on -> prefs.edit { putBoolean(RelaySettings.KEY_USE_TURN, on) } }
            }

            Group(stringResource(R.string.setting_webrtc_transport)) {
            Row(
                title = stringResource(R.string.setting_webrtc_stun_servers),
                summary = prefs.getString(DeviceHelper.KEY_WEBRTC_STUN_SERVERS_PREFERENCE, null)?.takeIf(String::isNotBlank)
                    ?: stringResource(R.string.setting_webrtc_stun_servers_description),
                onClick = { editing = DeviceHelper.KEY_WEBRTC_STUN_SERVERS_PREFERENCE },
            )
            Row(
                title = stringResource(R.string.setting_webrtc_turn_servers),
                summary = prefs.getString(DeviceHelper.KEY_WEBRTC_TURN_SERVERS_PREFERENCE, null)?.takeIf(String::isNotBlank)
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
                prefs.edit { putString(key, value) }
                if (key == RelaySettings.KEY_URL) relaySettingChanged()
            }
        }
    }

    // ---- behaviour --------------------------------------------------------------------------------

    /** Applies a relay setting once it has been saved. */
    private fun relaySettingChanged() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            BackgroundService.instance?.onRelaySettingsChanged()
        }
    }

    private fun setBluetooth(on: Boolean) {
        val context = requireContext()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && on) {
            val permissions = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            val granted = permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
            if (!granted) {
                // MainActivity turns the setting on once both are granted.
                PermissionsAlertDialogFragment.Builder()
                    .setTitle(R.string.location_permission_needed_title)
                    .setMessage(R.string.bluetooth_permission_needed_desc)
                    .setPermissions(permissions)
                    .setRequestCode(2)
                    .create().show(childFragmentManager, null)
                return
            }
        }
        prefs.edit { putBoolean(KEY_BLUETOOTH_ENABLED, on) }
    }

    @Composable
    private fun relayStatusText(): String? = when (relayStatus) {
        RelayLinkProvider.Status.CONNECTING -> stringResource(R.string.relay_status_connecting)
        RelayLinkProvider.Status.ONLINE -> stringResource(R.string.setting_relay_connected)
        RelayLinkProvider.Status.UNAVAILABLE -> stringResource(R.string.relay_status_unavailable)
        RelayLinkProvider.Status.UPDATE_REQUIRED -> stringResource(R.string.relay_status_update_required)
        else -> null
    }

    // ---- rows -----------------------------------------------------------------------------------------

    @Composable
    private fun SettingsColumn(content: @Composable () -> Unit) {
        Column(
            Modifier
                .fillMaxSize()
                .nestedScroll(rememberNestedScrollInteropConnection())
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        ) { content() }
    }

    /**
     * A titled group of rows on one card. The card is drawn in the hairline color and its rows in the
     * surface color, 1dp apart, so the hairlines between rows come for free.
     */
    @Composable
    private fun Group(title: String, content: @Composable () -> Unit) {
        SectionHeader(title)
        DeskCard(Modifier.fillMaxWidth(), color = tokens.hairline) {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) { content() }
        }
    }

    @Composable
    private fun Row(title: String, summary: String? = null, trailingIcon: Int? = null, onClick: () -> Unit) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = summary?.let { { Text(it, maxLines = 3) } },
            trailingContent = trailingIcon?.let { { Icon(painterResource(it), contentDescription = null) } },
            colors = rowColors(),
            modifier = Modifier.clickable(onClick = onClick),
        )
    }

    @Composable
    private fun rowColors() = androidx.compose.material3.ListItemDefaults.colors(
        containerColor = tokens.surface,
        supportingColor = tokens.textDim,
        trailingIconColor = tokens.textFaint,
    )

    @Composable
    private fun SwitchRow(title: String, checked: Boolean, summary: String? = null, onChange: (Boolean) -> Unit) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = summary?.let { { Text(it, maxLines = 3) } },
            // The row is the control; the switch only shows its state.
            trailingContent = { Switch(checked = checked, onCheckedChange = null) },
            colors = rowColors(),
            modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        )
    }

    @Composable
    private fun DebugSwitch(key: String, title: Int, summary: Int) {
        SwitchRow(stringResource(title), prefs.getBoolean(key, false), stringResource(summary)) { on -> prefs.edit { putBoolean(key, on) } }
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
        var text by remember { mutableStateOf(initial) }
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
                            colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                            modifier = Modifier.selectable(selected = index == selected, role = Role.RadioButton) { onChoose(index) },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        )
    }

    companion object {
        const val KEY_BLUETOOTH_ENABLED: String = "bluetooth_enabled"
        const val KEY_APP_THEME: String = "theme_pref"
    }
}
