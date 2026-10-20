/*
 * SPDX-FileCopyrightText: 2023 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/

package org.desklink.mobile.ui

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.apache.commons.lang3.ArrayUtils
import org.desklink.mobile.BackgroundService
import org.desklink.mobile.Device
import org.desklink.mobile.helpers.DeviceHelper
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.plugins.share.ShareSettingsFragment
import org.desklink.mobile.ui.about.AboutFragment
import org.desklink.mobile.ui.about.getApplicationAboutData
import org.desklink.mobile.R
import org.desklink.mobile.databinding.ActivityMainBinding

private const val STORAGE_LOCATION_CONFIGURED = 2020
private const val STATE_SCREEN = "screen" //Saved only in onSaveInstanceState
private const val STATE_SELECTED_DEVICE = "selected_device" //Saved persistently in preferences

/**
 * Home-first navigation: the device list is the root; a device, Settings and About sit one level
 * below it with an Up arrow, and Back from any of them returns to the list.
 */
class MainActivity : AppCompatActivity(), OnSharedPreferenceChangeListener {
    private enum class Screen { HOME, DEVICE, SETTINGS, ABOUT }

    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }

    private var mCurrentDevice: String? = null
    private var screen = Screen.HOME
        set(value) {
            field = value
            backToHomeCallback.isEnabled = value != Screen.HOME
            supportActionBar?.setDisplayHomeAsUpEnabled(value != Screen.HOME)
        }
    private val preferences: SharedPreferences by lazy { getSharedPreferences("stored_menu_selection", MODE_PRIVATE) }

    private val backToHomeCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            onDeviceSelected(null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        DeviceHelper.initializeDeviceId(this)

        setContentView(binding.root)
        setSupportActionBar(binding.toolbarLayout.toolbar)
        supportActionBar?.setHomeActionContentDescription(R.string.navigate_up)
        onBackPressedDispatcher.addCallback(this, backToHomeCallback)

        // Note: The preference changed listener should be registered before getting the name, because getting
        // it can trigger a background fetch from the internet that will eventually update the preference
        PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(this)
        DeviceHelper.getDeviceName(this)

        // Decide which screen should be shown at start
        var savedDevice: String?
        var savedScreen: Screen
        when {
            intent.hasExtra(FLAG_FORCE_OVERVIEW) -> {
                Log.i(this::class.simpleName, "Requested to start main overview")
                savedDevice = null
                savedScreen = Screen.HOME
            }

            intent.hasExtra(EXTRA_DEVICE_ID) -> {
                Log.i(this::class.simpleName, "Loading selected device from parameter")
                savedDevice = intent.getStringExtra(EXTRA_DEVICE_ID)
                savedScreen = Screen.DEVICE
                // If pairStatus is not empty, then the user has accepted/reject the pairing from the notification
                val pairStatus = intent.getStringExtra(PAIR_REQUEST_STATUS)
                if (pairStatus != null) {
                    Log.i(this::class.simpleName, "Pair status is $pairStatus")
                    savedDevice = onPairResultFromNotification(savedDevice, pairStatus)
                    if (savedDevice == null) {
                        savedScreen = Screen.HOME
                    }
                }
            }

            savedInstanceState != null -> {
                Log.i(this::class.simpleName, "Loading selected device from saved activity state")
                savedDevice = savedInstanceState.getString(STATE_SELECTED_DEVICE)
                savedScreen = savedInstanceState.getString(STATE_SCREEN)
                    ?.let { name -> Screen.entries.firstOrNull { it.name == name } } ?: Screen.HOME
            }

            else -> {
                Log.i(this::class.simpleName, "Loading selected device from persistent storage")
                savedDevice = preferences.getString(STATE_SELECTED_DEVICE, null)
                savedScreen = if (savedDevice != null) Screen.DEVICE else Screen.HOME
            }
        }
        mCurrentDevice = savedDevice
        screen = savedScreen

        //FragmentManager will restore whatever fragment was there
        if (savedInstanceState != null) {
            val frag = supportFragmentManager.findFragmentById(R.id.container)
            if (frag !is DeviceFragment || frag.deviceId == savedDevice) return
        }

        // Activate the chosen fragment
        when {
            savedScreen == Screen.DEVICE && savedDevice != null -> onDeviceSelected(savedDevice)
            savedScreen == Screen.SETTINGS -> openSettings()
            savedScreen == Screen.ABOUT -> openAbout()
            else -> onDeviceSelected(null)
        }

        val missingPermissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionResult = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            if (permissionResult != PackageManager.PERMISSION_GRANTED) {
                if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)) {
                    missingPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }

        if(missingPermissions.isNotEmpty()){
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), RESULT_NOTIFICATIONS_ENABLED)
        }
    }

    fun openSettings() {
        preferences.edit { putString(STATE_SELECTED_DEVICE, null) }
        screen = Screen.SETTINGS
        setContentFragment(SettingsFragment())
    }

    fun openAbout() {
        preferences.edit { putString(STATE_SELECTED_DEVICE, null) }
        screen = Screen.ABOUT
        setContentFragment(AboutFragment.newInstance(getApplicationAboutData(this)))
    }

    override fun onDestroy() {
        super.onDestroy()
        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(this)
    }

    private fun onPairResultFromNotification(deviceId: String?, pairStatus: String): String? {
        assert(deviceId != null)
        // Navigation only. This activity is exported, so it must never act on
        // pairing: accept/reject now go through the non-exported
        // PairingActionReceiver. Any other status from an outside caller is
        // ignored.
        return if (pairStatus == PAIRING_PENDING) deviceId else null
    }

    override fun onStart() {
        super.onStart()
        BackgroundService.Start(applicationContext)
    }

    /** Opens [deviceId]'s screen, or the device list for null. [fromDeviceList] is kept for callers; Back always returns to the list. */
    @Suppress("UNUSED_PARAMETER")
    @JvmOverloads
    fun onDeviceSelected(deviceId: String?, fromDeviceList: Boolean = false) {
        mCurrentDevice = deviceId
        preferences.edit { putString(STATE_SELECTED_DEVICE, deviceId) }
        if (deviceId != null) {
            screen = Screen.DEVICE
            setContentFragment(DeviceFragment.newInstance(deviceId, true))
        } else {
            screen = Screen.HOME
            setContentFragment(PairingFragment(), toHome = true)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    /** Swaps the screen with the platform's own open/close motion; none for the first one shown. */
    private fun setContentFragment(fragment: Fragment, toHome: Boolean = false) {
        val first = supportFragmentManager.findFragmentById(R.id.container) == null
        supportFragmentManager
            .beginTransaction()
            .apply {
                if (!first) setTransition(if (toHome) FragmentTransaction.TRANSIT_FRAGMENT_CLOSE else FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
            }
            .replace(R.id.container, fragment)
            .commit()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SELECTED_DEVICE, mCurrentDevice)
        outState.putString(STATE_SCREEN, screen.name)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when {
            requestCode == RESULT_NEEDS_RELOAD -> {
                CoroutineScope(Dispatchers.IO).launch {
                    DeskLinkApplication.getInstance().devices.values.forEach(Device::reloadPluginsFromSettings)
                }
            }
            requestCode == STORAGE_LOCATION_CONFIGURED && resultCode == RESULT_OK && data != null -> {
                val uri = data.data
                ShareSettingsFragment.saveStorageLocationPreference(this, uri)
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    fun isPermissionGranted(permissions: Array<String>, grantResults: IntArray, permission : String) : Boolean {
        val index = ArrayUtils.indexOf(permissions, permission)
        return index != ArrayUtils.INDEX_NOT_FOUND && grantResults[index] == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val permissionsGranted = ArrayUtils.contains(grantResults, PackageManager.PERMISSION_GRANTED)
        if (permissionsGranted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isPermissionGranted(permissions, grantResults, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                // To get a writeable path manually on Android 10 and later for Share and Receive Plugin.
                // Otherwise, Receiving files will keep failing until the user chooses a path manually to receive files.
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                startActivityForResult(intent, STORAGE_LOCATION_CONFIGURED)
            }

            if (isPermissionGranted(permissions, grantResults, Manifest.permission.BLUETOOTH_CONNECT) &&
                isPermissionGranted(permissions, grantResults, Manifest.permission.BLUETOOTH_SCAN)) {
                PreferenceManager.getDefaultSharedPreferences(this).edit {
                    putBoolean(SettingsFragment.KEY_BLUETOOTH_ENABLED, true)
                }
                openSettings()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && isPermissionGranted(permissions, grantResults, Manifest.permission.POST_NOTIFICATIONS)) {
                // If PairingFragment is active, reload it
                if (screen == Screen.HOME) {
                    setContentFragment(PairingFragment())
                }
            }

            //New permission granted, reload plugins
            CoroutineScope(Dispatchers.IO).launch {
                DeskLinkApplication.getInstance().devices.values.forEach(Device::reloadPluginsFromSettings)
            }
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (DeviceHelper.KEY_DEVICE_NAME_PREFERENCE == key) {
            BackgroundService.ForceRefreshConnections(this) //Re-send our identity packet
        }
    }

    companion object {
        const val EXTRA_DEVICE_ID = "deviceId"
        const val PAIR_REQUEST_STATUS = "pair_req_status"
        const val PAIRING_PENDING = "pending"
        const val RESULT_NEEDS_RELOAD = RESULT_FIRST_USER
        const val RESULT_NOTIFICATIONS_ENABLED = RESULT_FIRST_USER+1
        const val FLAG_FORCE_OVERVIEW = "forceOverview"
    }
}
