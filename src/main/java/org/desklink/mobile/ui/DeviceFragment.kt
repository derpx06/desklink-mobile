/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.UiThread
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.desklink.mobile.BackgroundService
import org.desklink.mobile.Device
import org.desklink.mobile.Device.PluginsChangedListener
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.PairingHandler
import org.desklink.mobile.base.BaseFragment
import org.desklink.mobile.helpers.security.SslHelper
import org.desklink.mobile.plugins.Plugin
import org.desklink.mobile.plugins.terminal.TerminalActivity
import org.desklink.mobile.ui.compose.DeskLinkTheme
import org.desklink.mobile.ui.compose.extensions.device.toUiModel
import org.desklink.mobile.ui.compose.screen.device.DeviceScreen
import org.desklink.mobile.ui.compose.screen.device.DeviceScreenActions
import org.desklink.mobile.ui.compose.screen.device.DeviceScreenState
import org.desklink.mobile.ui.compose.screen.device.QuickActionItem
import org.desklink.mobile.R
import org.desklink.mobile.databinding.ActivityDeviceBinding

/**
 * One device: its pairing request, or, once paired, what it can do from here.
 */
class DeviceFragment : BaseFragment<ActivityDeviceBinding>() {

    companion object {
        private const val ARG_DEVICE_ID = "deviceId"
        private const val ARG_FROM_DEVICE_LIST = "fromDeviceList"
        private const val TAG = "DeskLink/DeviceFragment"
        private const val STATUS_POLL_MS = 2_000L
        fun newInstance(deviceId: String?, fromDeviceList: Boolean): DeviceFragment {
            val frag = DeviceFragment()
            val args = Bundle()
            args.putString(ARG_DEVICE_ID, deviceId)
            args.putBoolean(ARG_FROM_DEVICE_LIST, fromDeviceList)
            frag.arguments = args
            return frag
        }

        /** The order tiles appear in: the remote and the terminal first, by their icons. */
        private val CONTROL_ORDER = listOf(
            R.drawable.touchpad_plugin_action_24dp,
            R.drawable.ic_terminal_24dp,
            R.drawable.share_plugin_action_24dp,
            R.drawable.ic_baseline_content_paste_24,
            R.drawable.ic_presenter_24dp,
            R.drawable.run_command_plugin_icon_24dp,
            R.drawable.mpris_plugin_action_24dp,
            R.drawable.ic_draw_24dp,
            R.drawable.tv_remote_24px,
        )
    }

    // The screen title is the device's own name, in the hero below the app bar.
    override fun getActionBarTitle() = ""

    val deviceId: String by lazy {
        arguments?.getString(ARG_DEVICE_ID)
            ?: throw RuntimeException("You must instantiate a new DeviceFragment using DeviceFragment.newInstance()")
    }

    private val device by lazy { DeskLinkApplication.getInstance().getDevice(deviceId) }

    private val screenState = mutableStateOf<DeviceScreenState?>(null)

    /** Set by a pairing failure or unpair; cleared when pairing starts again. */
    private var pairMessage: String? = null
    private var isRefreshing = false
    private var statusPoll: Job? = null

    override fun onInflateBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): ActivityDeviceBinding = ActivityDeviceBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val actions = DeviceScreenActions(
            onControl = { button -> mActivity?.let(button.onClick) },
            onQuickAction = { entry -> mActivity?.let(entry.onClick) },
            onRequiredPermission = { plugin ->
                PluginPermissionDialogSelector.requiredPermissionDialog(plugin).show(childFragmentManager, null)
            },
            onOptionalPermission = { plugin ->
                PluginPermissionDialogSelector.optionalPermissionDialog(plugin).show(childFragmentManager, null)
            },
            onRequestPairing = {
                pairMessage = null
                device?.requestPairing()
                refreshUI()
            },
            onAcceptPairing = { device?.acceptPairing() },
            onRejectPairing = {
                device?.apply {
                    // Remove listener so buttons don't show for an instant before changing the view
                    removePluginsChangedListener(pluginsChangedListener)
                    removePairingCallback(pairingCallback)
                    cancelPairing()
                }
                (mActivity as? MainActivity)?.onDeviceSelected(null)
            },
            onCancelPairing = { device?.cancelPairing() },
            onRefresh = ::refreshDevicesAction,
            onEncryptionInfo = ::showEncryptionInfo,
            onPluginSettings = {
                startActivity(Intent(mActivity, PluginSettingsActivity::class.java).putExtra("deviceId", deviceId))
            },
            onUnpair = {
                device?.apply {
                    // Remove listener so buttons don't show for an instant before changing the view
                    removePairingCallback(pairingCallback)
                    removePluginsChangedListener(pluginsChangedListener)
                    unpair()
                }
                view.post { (mActivity as? MainActivity)?.onDeviceSelected(null) }
            },
        )
        binding.deviceViewCompose.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DeskLinkTheme(context) {
                    screenState.value?.let { DeviceScreen(it, actions) }
                }
            }
        }
        device?.apply {
            removePairingCallback(pairingCallback)
            addPairingCallback(pairingCallback)
            removePluginsChangedListener(pluginsChangedListener)
            addPluginsChangedListener(pluginsChangedListener)
        } ?: run { // device is null
            Log.e(TAG, "Trying to display a device fragment but the device is not present")
            (mActivity as? MainActivity)?.onDeviceSelected(null)
        }
        refreshUI()
    }

    override fun onStart() {
        super.onStart()
        // Battery, reachability and the WebRTC handover change without a plugins callback.
        statusPoll = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                delay(STATUS_POLL_MS)
                refreshUI()
            }
        }
    }

    override fun onStop() {
        statusPoll?.cancel()
        super.onStop()
    }

    private fun refreshDevicesAction() {
        BackgroundService.ForceRefreshConnections(requireContext())
        isRefreshing = true
        refreshUI()
        view?.postDelayed({
            isRefreshing = false
            if (hasBinding) refreshUI()
        }, 1500)
    }

    private fun showEncryptionInfo() {
        val device = device ?: return
        val context = requireContext()
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.encryption_info_title)
            .setPositiveButton(R.string.ok) { dialog, _ -> dialog.dismiss() }
            .setMessage(
                "${context.getString(R.string.my_device_fingerprint)}\n${SslHelper.getCertificateHash(SslHelper.certificate)}\n\n" +
                    "${context.getString(R.string.remote_device_fingerprint)}\n${SslHelper.getCertificateHash(device.certificate)}\n\n" +
                    "${context.getString(R.string.protocol_version)} ${device.protocolVersion}",
            )
            .show()
    }

    private val pluginsChangedListener =
        PluginsChangedListener { mActivity?.runOnUiThread { refreshUI() } }

    override fun onDestroyView() {
        device?.apply {
            removePluginsChangedListener(pluginsChangedListener)
            removePairingCallback(pairingCallback)
        }
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        with(requireView()) {
            isFocusableInTouchMode = true
            requestFocus()
        }
    }

    @UiThread
    private fun refreshUI() {
        if (!hasBinding) return // in case onDestroyView has already been called
        val device = device ?: return

        //Once in-app, there is no point in keep displaying the notification if any
        device.hidePairingNotification()

        val model = device.toUiModel()
        val paired = device.pairStatus == PairingHandler.PairState.Paired
        val live = paired && device.isReachable
        screenState.value = DeviceScreenState(
            name = device.name,
            icon = device.iconDrawable,
            pairState = device.pairStatus,
            reachable = device.isReachable,
            connection = model.connection,
            batteryPercent = model.batteryPercent,
            charging = model.charging,
            verificationKey = device.verificationKey,
            pairMessage = pairMessage,
            protocolVersion = device.protocolVersion,
            controls = if (live) {
                (device.loadedPlugins.values.flatMap { it.getUiButtons() } + terminalButtons(device))
                    .sortedBy { CONTROL_ORDER.indexOf(it.iconRes).let { i -> if (i < 0) CONTROL_ORDER.size else i } }
            } else {
                emptyList()
            },
            quickActions = if (live) {
                device.loadedPlugins.values.flatMap { it.getUiMenuEntries() }.map { QuickActionItem(it, quickActionIcon(it.name)) }
            } else {
                emptyList()
            },
            needPermissions = if (live) {
                device.pluginsWithoutPermissions.values.filter { device.isPluginEnabled(it.pluginKey) }
            } else {
                emptyList()
            },
            optionalPermissions = if (live) {
                device.pluginsWithoutOptionalPermissions.values.filter { device.isPluginEnabled(it.pluginKey) }
            } else {
                emptyList()
            },
            isRefreshing = isRefreshing,
        )
    }

    private fun quickActionIcon(name: String): Int = when (name) {
        getString(R.string.ring) -> R.drawable.ic_vibration_24dp
        getString(R.string.send_ping) -> R.drawable.ic_wifi_tethering_24dp
        getString(R.string.lock_remote_device) -> R.drawable.ic_lock_24dp
        getString(R.string.send_clipboard) -> R.drawable.ic_baseline_content_paste_24
        else -> R.drawable.ic_phonelink_36dp
    }

    /**
     * Terminal is not a plugin (it rides its own WebRTC data channel, like the file browser), so
     * its entry point is added here, and only while the authenticated WebRTC transport is ready.
     */
    private fun terminalButtons(device: Device): List<Plugin.PluginUiButton> {
        if (!device.isPaired || !device.hasActiveWebRtcFeatureTransport) return emptyList()
        return listOf(
            Plugin.PluginUiButton(getString(R.string.terminal_open), R.drawable.ic_terminal_24dp) { parentActivity ->
                parentActivity.startActivity(TerminalActivity.intent(parentActivity, device.deviceId))
            },
        )
    }

    private val pairingCallback: PairingHandler.PairingCallback =
        object : PairingHandler.PairingCallback {
            override fun incomingPairRequest() {
                mActivity?.runOnUiThread {
                    pairMessage = null
                    refreshUI()
                }
            }

            override fun pairingSuccessful() {
                mActivity?.runOnUiThread {
                    view?.announceForAccessibility(getString(R.string.pair_succeeded))
                    pairMessage = null
                    refreshUI()
                }
            }

            override fun pairingFailed(error: String) {
                mActivity?.runOnUiThread {
                    pairMessage = error
                    refreshUI()
                }
            }

            override fun unpaired(device: Device) {
                mActivity?.runOnUiThread {
                    pairMessage = getString(R.string.device_not_paired)
                    refreshUI()
                }
            }
        }
}
