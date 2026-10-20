/*
 * SPDX-FileCopyrightText: 2023 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/
package org.desklink.mobile.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputFilter.LengthFilter
import android.text.Spanned
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.net.toUri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.preference.EditTextPreference
import androidx.annotation.DrawableRes
import androidx.preference.ListPreference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.apache.commons.io.IOUtils
import org.desklink.mobile.BackgroundService
import org.desklink.mobile.backends.relay.RelayLinkProvider
import org.desklink.mobile.backends.relay.RelaySettings
import org.desklink.mobile.helpers.CreateFileParams
import org.desklink.mobile.helpers.CreateFileResultContract
import org.desklink.mobile.helpers.DeviceHelper
import org.desklink.mobile.helpers.DeviceHelper.filterInvalidCharactersFromDeviceName
import org.desklink.mobile.helpers.DeviceHelper.getDeviceName
import org.desklink.mobile.helpers.NotificationHelper
import org.desklink.mobile.ui.ThemeUtil.applyTheme
import org.desklink.mobile.extensions.setupBottomPadding
import org.desklink.mobile.BuildConfig
import org.desklink.mobile.R
import java.io.InputStreamReader

class SettingsFragment : PreferenceFragmentCompat() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        if (activity != null) {
            (activity as? MainActivity)?.supportActionBar?.setTitle(R.string.settings)
            (activity as? MainActivity)?.supportActionBar?.subtitle = null
        }
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)

        // Categories must be on the screen before preferences are added to them.
        screen.category(
            context,
            R.string.settings_category_general,
            deviceNamePref(context).withIcon(R.drawable.ic_device_phone_32dp),
            themePref(context).withIcon(R.drawable.ic_brightness_24dp),
            *listOfNotNull(dynamicColorsPref(context)?.withIcon(R.drawable.ic_palette_24dp)).toTypedArray(),
        )
        screen.category(
            context,
            R.string.settings_category_connection,
            trustedNetworkPref(context).withIcon(R.drawable.ic_shield_24dp),
            devicesByIpPref(context).withIcon(R.drawable.ic_desktop_24dp),
            bluetoothSupportPref(context).withIcon(R.drawable.ic_bluetooth_24dp),
        )
        screen.category(
            context,
            R.string.settings_category_away,
            relayEnabledPref(context).withIcon(R.drawable.ic_cloud_24dp),
            relayUrlPref(context).withIcon(R.drawable.ic_link_24dp),
            relayUseTurnPref(context).withIcon(R.drawable.ic_swap_horiz_24dp),
            webRtcStunServersPref(context).withIcon(R.drawable.ic_dns_24dp),
            webRtcTurnServersPref(context).withIcon(R.drawable.ic_dns_24dp),
        )
        screen.category(
            context,
            R.string.settings_category_notifications,
            persistentNotificationPref(context).withIcon(R.drawable.ic_notifications_24dp),
        )
        screen.category(
            context,
            R.string.settings_category_advanced,
            exportLogsPref(context).withIcon(R.drawable.ic_download_24dp),
            privacyPolicyPref(context).withIcon(R.drawable.ic_policy_24dp),
            moreSettingsPref(context).withIcon(R.drawable.ic_info_24dp),
        )
        if (BuildConfig.DEBUG) {
            screen.category(
                context,
                R.string.settings_category_debug,
                relayDebugPref(context, RelaySettings.KEY_FORCE_RELAY, R.string.setting_relay_force_relay, R.string.setting_relay_force_relay_description)
                    .withIcon(R.drawable.ic_baseline_bug_report_24),
                relayDebugPref(context, RelaySettings.KEY_FORCE_TURN, R.string.setting_relay_force_turn, R.string.setting_relay_force_turn_description)
                    .withIcon(R.drawable.ic_baseline_bug_report_24),
            )
        }

        preferenceScreen = screen
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView.setupBottomPadding()
        setDivider(null) // sections are separated by their titles, not rules
        RelayLinkProvider.statusLive.observe(viewLifecycleOwner) { status ->
            relayEnabledPref?.summary = getString(R.string.setting_relay_disclosure) + "\n" + getString(
                when (status) {
                    RelayLinkProvider.Status.CONNECTING -> R.string.relay_status_connecting
                    RelayLinkProvider.Status.ONLINE -> R.string.relay_status_online
                    RelayLinkProvider.Status.UNAVAILABLE -> R.string.relay_status_unavailable
                    RelayLinkProvider.Status.UPDATE_REQUIRED -> R.string.relay_status_update_required
                    else -> R.string.relay_status_off
                },
            )
        }
    }

    private var relayEnabledPref: Preference? = null

    /** Applies a relay setting once the preference has been saved (change listeners run before). */
    private val relaySettingChanged = Preference.OnPreferenceChangeListener { _, _ ->
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            BackgroundService.instance?.onRelaySettingsChanged()
        }
        true
    }

    private fun relayEnabledPref(context: Context) = SwitchPreference(context).apply {
        relayEnabledPref = this
        key = RelaySettings.KEY_ENABLED
        setDefaultValue(true)
        setTitle(R.string.setting_relay_enabled)
        setSummary(R.string.setting_relay_disclosure)
        onPreferenceChangeListener = relaySettingChanged
    }

    private fun relayUrlPref(context: Context) = EditTextPreference(context).apply {
        key = RelaySettings.KEY_URL
        setTitle(R.string.setting_relay_url)
        summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            pref.text?.trim()?.takeIf(String::isNotEmpty)
                ?: getString(R.string.setting_relay_url_default, BuildConfig.DEFAULT_RELAY_URL)
        }
        setOnBindEditTextListener(EditText::setSingleLine)
        onPreferenceChangeListener = relaySettingChanged
    }

    private fun relayUseTurnPref(context: Context) = SwitchPreference(context).apply {
        key = RelaySettings.KEY_USE_TURN
        setDefaultValue(true)
        setTitle(R.string.setting_relay_use_turn)
    }

    private fun relayDebugPref(context: Context, prefKey: String, title: Int, summary: Int) = SwitchPreference(context).apply {
        key = prefKey
        setDefaultValue(false)
        setTitle(title)
        setSummary(summary)
    }

    private fun deviceNamePref(context: Context) = EditTextPreference(context).apply {
        key = DeviceHelper.KEY_DEVICE_NAME_PREFERENCE
        isSelectable = true
        setOnBindEditTextListener(EditText::setSingleLine)
        setOnBindEditTextListener { editText: EditText ->
            editText.filters = arrayOf(
                InputFilter { source: CharSequence, start: Int, end: Int, _: Spanned?, _: Int, _: Int ->
                    filterInvalidCharactersFromDeviceName(source.subSequence(start, end).toString())
                },
                LengthFilter(DeviceHelper.MAX_DEVICE_NAME_LENGTH),
            )
        }
        val deviceName = getDeviceName(context)
        setTitle(R.string.settings_rename)
        summary = deviceName
        setDialogTitle(R.string.device_rename_title)
        text = deviceName
        setPositiveButtonText(R.string.device_rename_confirm)
        setNegativeButtonText(R.string.cancel)
        onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _: Preference?, new: Any? ->
            val name = new as String?
            if (name.isNullOrBlank()) {
                if (view != null) {
                    // Material 3 snackbars already contrast with either theme.
                    Snackbar.make(requireView(), R.string.invalid_device_name, Snackbar.LENGTH_LONG).show()
                }
                false
            }
            else {
                summary = new
                true
            }
        }
    }

    private fun PreferenceScreen.category(context: Context, title: Int, vararg preferences: Preference) {
        val category = PreferenceCategory(context).apply { setTitle(title) }
        addPreference(category)
        preferences.forEach(category::addPreference)
    }

    /** Icons in the theme's primary: one accent down the list, as in the system's own settings. */
    private fun <T : Preference> T.withIcon(@DrawableRes icon: Int): T = apply {
        val tint = com.google.android.material.color.MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, 0)
        this.icon = androidx.core.content.ContextCompat.getDrawable(context, icon)?.mutate()?.also { it.setTint(tint) }
    }

    /** Android 12+ only: older systems have no wallpaper palette to follow. */
    private fun dynamicColorsPref(context: Context): Preference? =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) null else SwitchPreference(context).apply {
            key = ThemeUtil.KEY_DYNAMIC_COLORS
            setDefaultValue(true)
            setTitle(R.string.setting_dynamic_colors)
            setSummary(R.string.setting_dynamic_colors_summary)
            onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, _ ->
                // Saved after this returns; recreate once it is, so the new palette applies here too.
                view?.post { activity?.recreate() }
                true
            }
        }

    private fun themePref(context: Context) = ListPreference(context).apply {
        key = KEY_APP_THEME
        setTitle(R.string.theme_dialog_title)
        setDialogTitle(R.string.theme_dialog_title)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setEntries(R.array.theme_list_v28) else setEntries(R.array.theme_list)
        setEntryValues(R.array.theme_list_values)
        setDefaultValue(ThemeUtil.DEFAULT_MODE)
        summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _: Preference?, new: Any? ->
            if (new is String) {
                applyTheme(new)
            }
            true
        }
    }

    private fun persistentNotificationPref(context: Context): Preference =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Preference(context).apply {
            setTitle(R.string.setting_persistent_notification_oreo)
            setSummary(R.string.setting_persistent_notification_description)
            onPreferenceClickListener = Preference.OnPreferenceClickListener {
                val intent = Intent()
                intent.setAction("android.settings.APP_NOTIFICATION_SETTINGS")
                intent.putExtra("android.provider.extra.APP_PACKAGE", context.packageName)
                context.startActivity(intent)
                true
            }
        }
        // Persistent notification toggle for Android Versions below Oreo
        else SwitchPreference(context).apply {
            isPersistent = false
            isChecked = NotificationHelper.isPersistentNotificationEnabled(context)
            setTitle(R.string.setting_persistent_notification)
            onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _: Preference?, new: Any ->
                val isChecked = new as Boolean
                NotificationHelper.setPersistentNotificationEnabled(context, isChecked)
                BackgroundService.instance?.changePersistentNotificationVisibility(isChecked)
                NotificationHelper.setPersistentNotificationEnabled(context, isChecked)
                true
            }
        }

    val activityLauncherWithDevicesByIpRefresh = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        updateDevicesByIpSummary()
    }

    private fun trustedNetworkPref(context: Context) = Preference(context).apply {
        isPersistent = false
        setTitle(R.string.trusted_networks)
        setSummary(R.string.trusted_networks_desc)
        onPreferenceClickListener = Preference.OnPreferenceClickListener {
            activityLauncherWithNetworkRefresh.launch(Intent(context, TrustedNetworksActivity::class.java))
            true
        }
    }

    private lateinit var devicesByIpPref : Preference

    val activityLauncherWithNetworkRefresh = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        BackgroundService.instance?.onNetworkChange(null)
    }

    /** Opens activity to configure device by IP when clicked */
    private fun devicesByIpPref(context: Context) = Preference(context).apply {
        devicesByIpPref = this
        isPersistent = false
        setTitle(R.string.custom_device_list)
        updateDevicesByIpSummary()
        onPreferenceClickListener = Preference.OnPreferenceClickListener {
            activityLauncherWithDevicesByIpRefresh.launch(Intent(context, CustomDevicesActivity::class.java))
            true
        }
    }

    private fun updateDevicesByIpSummary() {
        devicesByIpPref.setSummary(getString(
            R.string.custom_devices_settings_summary,
            CustomDevicesActivity.getCustomDeviceList(context).size
        ))
    }

    private fun bluetoothSupportPref(context: Context) = SwitchPreference(context).apply {
        setDefaultValue(true)
        key = KEY_BLUETOOTH_ENABLED
        setTitle(R.string.enable_bluetooth)
        onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _: Preference?, newValue: Any ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && newValue as Boolean) {
                val permissions = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
                val permissionsGranted = permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
                if (!permissionsGranted) {
                    PermissionsAlertDialogFragment.Builder()
                        .setTitle(R.string.location_permission_needed_title)
                        .setMessage(R.string.bluetooth_permission_needed_desc)
                        .setPermissions(permissions)
                        .setRequestCode(2)
                        .create().show(childFragmentManager, null)
                    return@OnPreferenceChangeListener false
                }
            }
            true
        }
    }

    private fun webRtcStunServersPref(context: Context) = EditTextPreference(context).apply {
        key = DeviceHelper.KEY_WEBRTC_STUN_SERVERS_PREFERENCE
        setTitle(R.string.setting_webrtc_stun_servers)
        setSummary(R.string.setting_webrtc_stun_servers_description)
        setOnBindEditTextListener(EditText::setSingleLine)
    }

    private fun webRtcTurnServersPref(context: Context) = EditTextPreference(context).apply {
        key = DeviceHelper.KEY_WEBRTC_TURN_SERVERS_PREFERENCE
        setTitle(R.string.setting_webrtc_turn_servers)
        setSummary(R.string.setting_webrtc_turn_servers_description)
        setOnBindEditTextListener(EditText::setSingleLine)
    }

    private fun exportLogsPref(context: Context) = Preference(context).apply {
        isPersistent = false
        setTitle(R.string.settings_export_logs)
        setSummary(R.string.settings_export_logs_text)
        onPreferenceClickListener = Preference.OnPreferenceClickListener {
            exportLogs.launch(CreateFileParams("text/plain", "desklink-log.txt"))
            true
        }
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

    private fun privacyPolicyPref(context: Context) = Preference(context).apply {
        isPersistent = false
        setTitle(R.string.privacy_policy)
        setSummary(R.string.privacy_policy_summary)
        intent = Intent(Intent.ACTION_VIEW, context.getString(R.string.privacy_policy_url).toUri())
    }

    private fun moreSettingsPref(context: Context) = Preference(context).apply {
        isPersistent = false
        isSelectable = false
        setTitle(R.string.settings_more_settings_title)
        setSummary(R.string.settings_more_settings_text)
    }

    companion object {
        const val KEY_BLUETOOTH_ENABLED: String = "bluetooth_enabled"
        const val KEY_APP_THEME: String = "theme_pref"
    }
}
