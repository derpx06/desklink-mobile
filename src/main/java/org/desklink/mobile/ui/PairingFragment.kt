/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.MenuProvider
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.desklink.mobile.BackgroundService.Companion.ForceRefreshConnections
import org.desklink.mobile.BackgroundService.Companion.instance
import org.desklink.mobile.Device
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.base.BaseFragment
import org.desklink.mobile.helpers.TrustedNetworkHelper.Companion.isTrustedNetwork
import org.desklink.mobile.ui.compose.DeskLinkTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.desklink.mobile.ui.compose.screen.pairing.PairingScreen
import org.desklink.mobile.ui.compose.screen.pairing.PairingViewModel
import org.desklink.mobile.R
import org.desklink.mobile.databinding.DevicesListBinding

/**
 * The view that the user will see when there are no devices paired, or when you choose "add a new device" from the sidebar.
 */
class PairingFragment : BaseFragment<DevicesListBinding>() {

    private val viewModel by viewModels<PairingViewModel>()

    private var listRefreshCalledThisFrame = false

    private val menuProvider = object : MenuProvider {

        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
            menuInflater.inflate(R.menu.pairing, menu)
        }

        override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
            return when (menuItem.itemId) {
                R.id.menu_settings -> {
                    (mActivity as? MainActivity)?.openSettings()
                    true
                }

                R.id.menu_about -> {
                    (mActivity as? MainActivity)?.openAbout()
                    true
                }

                R.id.menu_refresh -> {
                    viewModel.onRefresh()
                    true
                }

                R.id.menu_custom_device_list -> {
                    startActivity(Intent(mActivity, CustomDevicesActivity::class.java))
                    true
                }

                R.id.menu_trusted_networks -> {
                    startActivity(Intent(mActivity, TrustedNetworksActivity::class.java))
                    true
                }

                else -> false
            }
        }
    }

    private fun scanQr() {
        qrScanner.launch(
            com.journeyapps.barcodescanner.ScanOptions()
                .setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                .setPrompt(getString(R.string.qr_scan_hint))
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }

    // QR pairing: one scan, nothing to compare (see QrPairing / pairing_qr.rs).
    private val qrScanner = registerForActivityResult(com.journeyapps.barcodescanner.ScanContract()) { result ->
        val text = result.contents ?: return@registerForActivityResult
        val context = requireContext().applicationContext
        val code = org.desklink.mobile.QrPairing.parse(text)
        if (code == null) {
            showMessage(getString(R.string.qr_not_a_pairing_code))
            return@registerForActivityResult
        }
        val application = org.desklink.mobile.DeskLinkApplication.getInstance()
        fun start(replaceExisting: Boolean) {
            showMessage(getString(R.string.qr_pairing_started))
            org.desklink.mobile.QrPairing.pair(application, code, replaceExisting) { error ->
                if (error != null) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post { showMessage(error) }
                }
            }
        }
        // A code can name any desktop's public id, so replacing an existing
        // pairing is the user's decision, never the scan's.
        val conflict = org.desklink.mobile.QrPairing.conflictingPairing(application, code)
        if (conflict == null) {
            start(replaceExisting = false)
        } else {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.qr_replace_pairing_title, conflict.name))
                .setMessage(R.string.qr_replace_pairing_message)
                .setPositiveButton(R.string.qr_replace_pairing_confirm) { _, _ -> start(replaceExisting = true) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /** A snackbar while this screen is up; a toast once it is gone (pairing can outlive it). */
    private fun showMessage(message: String) {
        val root = view
        if (root != null && isAdded) {
            com.google.android.material.snackbar.Snackbar.make(root, message, com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show()
        } else {
            android.widget.Toast.makeText(DeskLinkApplication.getInstance(), message, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun getActionBarTitle() = getString(R.string.app_name)

    override fun onInflateBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): DevicesListBinding = DevicesListBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mActivity?.addMenuProvider(menuProvider, viewLifecycleOwner, Lifecycle.State.RESUMED)

        createComposeView()
    }

    private fun createComposeView() {
        binding.composeView.apply {
            setContent {
                DeskLinkTheme(context) {
                    val state by viewModel.pairingUiState.collectAsStateWithLifecycle()

                    PairingScreen(
                        uiState = state,
                        onClick = { deviceId ->
                            viewModel.getDeviceById(deviceId)?.let { device ->
                                deviceClicked(device)
                            }
                        },
                        onWifiSettingsClick = {
                            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                        },
                        onNotificationSettingsClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                ActivityCompat.requestPermissions(
                                    requireActivity(),
                                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                                    MainActivity.RESULT_NOTIFICATIONS_ENABLED
                                )
                            } else {
                                openAppDetailsSettings()
                            }
                        },
                        onRefresh = { viewModel.onRefresh() },
                        onScanQr = ::scanQr,
                    )
                }
            }
        }
    }

    private fun openAppDetailsSettings() {
        val intent = Intent().apply {
            action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            data = Uri.fromParts(
                "package",
                requireContext().packageName,
                null
            )
        }
        startActivity(intent)
    }

    private fun updateDeviceList() {
        if (!isAdded) {
            //Fragment is not attached to an activity. We will crash if we try to do anything here.
            return
        }

        if (listRefreshCalledThisFrame) {
            // This makes sure we don't try to call list.getFirstVisiblePosition()
            // twice per frame, because the second time the list hasn't been drawn
            // yet and it would always return 0.
            return
        }
        listRefreshCalledThisFrame = true

        //Check if we're on Wi-Fi/Local network. If we still see a device, don't do anything special
        val service = instance
        if (service == null) {
            updateConnectivityInfoHeader(isConnectedToNonCellularNetwork = true)
        } else {
            service.isConnectedToNonCellularNetwork.observe(
                viewLifecycleOwner,
                ::updateConnectivityInfoHeader
            )
        }

        try {
            viewModel.buildUiState(devices = visibleDevices())
        } catch (_: IllegalStateException) {
            // Ignore: The activity was closed while we were trying to update it
        } finally {
            listRefreshCalledThisFrame = false
        }
    }

    private fun visibleDevices() = DeskLinkApplication.getInstance().devices.values.filter {
        it.isReachable || it.isPaired
    }

    private fun updateConnectivityInfoHeader(isConnectedToNonCellularNetwork: Boolean) {
        val hasNotificationsPermission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }

        viewModel.updateConnectivity(
            isWifiAvailable = isConnectedToNonCellularNetwork,
            hasNotificationsPermission = hasNotificationsPermission,
            isTrustedNetwork = isTrustedNetwork(requireContext())
        )
    }

    override fun onStart() {
        super.onStart()
        DeskLinkApplication.getInstance().addDeviceListChangedCallback("PairingFragment") {
            mActivity?.runOnUiThread { this.updateDeviceList() }
        }
        ForceRefreshConnections(requireContext()) // force a network re-discover
        updateDeviceList()
        // Battery and the WebRTC handover change without a device-list callback; the cards show both.
        // ponytail: a 2 s poll while visible, a per-device change listener if it ever matters.
        statusPoll = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                delay(STATUS_POLL_MS)
                viewModel.buildUiState(visibleDevices())
            }
        }
    }

    private var statusPoll: Job? = null

    override fun onStop() {
        DeskLinkApplication.getInstance().removeDeviceListChangedCallback("PairingFragment")
        statusPoll?.cancel()
        super.onStop()
    }

    fun deviceClicked(device: Device) {
        (mActivity as? MainActivity)?.onDeviceSelected(
            device.deviceId,
            !device.isPaired || !device.isReachable
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            RESULT_PAIRING_SUCCESFUL -> if (resultCode == 1) {
                val deviceId = data?.getStringExtra("deviceId")
                (mActivity as? MainActivity)?.onDeviceSelected(deviceId)
            }

            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    companion object {
        private const val RESULT_PAIRING_SUCCESFUL = Activity.RESULT_FIRST_USER
        private const val STATUS_POLL_MS = 2_000L
    }
}
