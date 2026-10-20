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
import org.desklink.mobile.ui.compose.screen.device.ActiveSession
import org.desklink.mobile.ui.compose.screen.device.FeatureRow
import org.desklink.mobile.ui.compose.components.SessionPreviews
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import org.desklink.mobile.plugins.screen.ScreenControlPlugin
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

        /** Features in the order both apps list them, by the icon each plugin gives its button. */
        private val FEATURE_ORDER = listOf(
            R.drawable.touchpad_plugin_action_24dp,
            R.drawable.share_plugin_action_24dp,
            R.drawable.ic_baseline_content_paste_24,
            R.drawable.ic_terminal_24dp,
            R.drawable.ic_presenter_24dp,
            R.drawable.run_command_plugin_icon_24dp,
            R.drawable.mpris_plugin_action_24dp,
            R.drawable.ic_draw_24dp,
            R.drawable.tv_remote_24px,
        )

        /** The one line under each feature card's title. */
        private val FEATURE_DESCRIPTIONS = mapOf(
            R.drawable.touchpad_plugin_action_24dp to R.string.feature_desc_screen,
            R.drawable.share_plugin_action_24dp to R.string.feature_desc_files,
            R.drawable.ic_baseline_content_paste_24 to R.string.feature_desc_clipboard,
            R.drawable.ic_terminal_24dp to R.string.feature_desc_terminal,
            R.drawable.ic_presenter_24dp to R.string.feature_desc_presenter,
            R.drawable.run_command_plugin_icon_24dp to R.string.feature_desc_run_command,
            R.drawable.mpris_plugin_action_24dp to R.string.feature_desc_media,
            R.drawable.ic_draw_24dp to R.string.feature_desc_digitizer,
            R.drawable.tv_remote_24px to R.string.feature_desc_tv,
        )

        /** One icon family in the list: each plugin icon's Material Symbols counterpart. */
        private val FEATURE_ICONS = mapOf(
            R.drawable.touchpad_plugin_action_24dp to R.drawable.sym_cast,
            R.drawable.share_plugin_action_24dp to R.drawable.sym_upload_file,
            R.drawable.ic_baseline_content_paste_24 to R.drawable.sym_content_paste,
            R.drawable.ic_terminal_24dp to R.drawable.sym_terminal,
            R.drawable.ic_presenter_24dp to R.drawable.sym_co_present,
            R.drawable.run_command_plugin_icon_24dp to R.drawable.sym_bolt,
            R.drawable.mpris_plugin_action_24dp to R.drawable.sym_play_circle,
            R.drawable.ic_draw_24dp to R.drawable.sym_draw,
            R.drawable.tv_remote_24px to R.drawable.sym_settings_remote,
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
            onRow = { row -> mActivity?.let(row.onClick) },
            onOpenSession = { id ->
                mActivity?.let { startActivity(TerminalActivity.intent(it, deviceId).putExtra(TerminalActivity.EXTRA_TERMINAL_ID, id)) }
            },
            onNewSession = { command ->
                // The terminal screen opens the new session as soon as the computer reports it.
                DeskLinkApplication.getInstance().terminalClient(deviceId)?.create(24, 80, command?.let(::listOf))
                mActivity?.let { startActivity(TerminalActivity.intent(it, deviceId)) }
            },
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
            onFeatureSettings = ::openFeatureSettings,
            onUnpair = {
                device?.apply {
                    // Remove listener so buttons don't show for an instant before changing the view
                    removePairingCallback(pairingCallback)
                    removePluginsChangedListener(pluginsChangedListener)
                    unpair()
                }
                view.post { (mActivity as? MainActivity)?.onDeviceSelected(null) }
            },
            onEndSession = {
                DeskLinkApplication.getInstance().stopRemoteSession(deviceId)
                DeskLinkApplication.getInstance().terminalClient(deviceId)?.detachAll()
                refreshUI()
            },
            onHeroHidden = { hidden ->
                // The name moves into the app bar once the hero has scrolled away.
                mActivity?.supportActionBar?.title = if (hidden) device?.name else ""
            },
        )
        mActivity?.addMenuProvider(menuProvider, viewLifecycleOwner)
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
            .setTitle(R.string.device_encryption)
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
        val menuEntries = if (live) device.loadedPlugins.values.flatMap { it.getUiMenuEntries() } else emptyList()
        screenState.value = DeviceScreenState(
            deviceId = deviceId,
            name = device.name,
            icon = device.iconDrawable,
            pairState = device.pairStatus,
            reachable = device.isReachable,
            connection = model.connection,
            batteryPercent = model.batteryPercent,
            charging = model.charging,
            verificationKey = device.verificationKey,
            pairMessage = pairMessage,
            fingerprint = if (paired) shortFingerprint(device) else null,
            features = if (live) featureRows(device) + menuEntries.filter { it.name == getString(R.string.send_clipboard) }.map { entry ->
                FeatureRow(entry.name, R.drawable.sym_content_paste, opensScreen = false, onClick = entry.onClick, description = getString(R.string.feature_desc_clipboard))
            } else {
                emptyList()
            },
            quickActions = if (live) quickActions(device, menuEntries) else emptyList(),
            terminalReady = live && device.hasActiveWebRtcFeatureTransport,
            sessions = if (live) SessionPreviews.of(requireContext(), deviceId) else emptyList(),
            launchers = DeskLinkApplication.getInstance().terminalClient(deviceId)?.takeIf { live }?.state?.value?.launchers.orEmpty()
                .mapNotNull { launcher -> SessionPreviews.agentName(requireContext(), launcher)?.let { it to launcher } },
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
            activeSession = if (live) activeSession() else null,
            isRefreshing = isRefreshing,
        )
        val overflow = menuEntries.filter {
            it.name !in setOf(getString(R.string.ring), getString(R.string.lock_remote_device), getString(R.string.send_ping), getString(R.string.send_clipboard))
        }
        // Rebuilt only when it changes: the status poll would otherwise close an open menu.
        if (overflow.map { it.name } != overflowEntries.map { it.name }) {
            overflowEntries = overflow
            mActivity?.invalidateMenu()
        } else {
            overflowEntries = overflow
        }
    }

    /** Ring, Lock, Ping and Send files: the pill buttons under the name. */
    private fun quickActions(device: Device, menuEntries: List<Plugin.PluginUiMenuEntry>): List<FeatureRow> {
        val byName = menuEntries.associateBy { it.name }
        return listOfNotNull(
            byName[getString(R.string.ring)]?.let { FeatureRow(getString(R.string.device_ring), R.drawable.sym_vibration, false, it.onClick) },
            byName[getString(R.string.lock_remote_device)]?.let { FeatureRow(getString(R.string.device_lock), R.drawable.sym_lock, false, it.onClick) },
            byName[getString(R.string.send_ping)]?.let { FeatureRow(getString(R.string.device_ping), R.drawable.sym_wifi_tethering, false, it.onClick) },
            device.loadedPlugins.values.flatMap { it.getUiButtons() }.firstOrNull { it.iconRes == R.drawable.share_plugin_action_24dp }
                ?.let { FeatureRow(getString(R.string.send_files), R.drawable.sym_upload_file, true, it.onClick) },
        )
    }

    /** Anything a plugin offers that has no button of its own. */
    private var overflowEntries: List<Plugin.PluginUiMenuEntry> = emptyList()

    private val menuProvider = object : MenuProvider {
        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
            overflowEntries.forEach { entry ->
                menu.add(entry.name).setOnMenuItemClickListener {
                    mActivity?.let(entry.onClick)
                    true
                }
            }
            menu.add(R.string.device_features).setOnMenuItemClickListener {
                openFeatureSettings()
                true
            }
        }

        override fun onMenuItemSelected(menuItem: MenuItem): Boolean = false
    }

    private fun openFeatureSettings() {
        startActivity(Intent(mActivity, PluginSettingsActivity::class.java).putExtra("deviceId", deviceId))
    }

    /**
     * The features as list rows, in the order and with the names both apps use: Computer screen,
     * Send files, Clipboard, Terminal, then the rest. A row opens a screen unless it acts at once.
     */
    private fun featureRows(device: Device): List<FeatureRow> =
        device.loadedPlugins.values.flatMap { it.getUiButtons() }
            .sortedBy { FEATURE_ORDER.indexOf(it.iconRes).let { i -> if (i < 0) FEATURE_ORDER.size else i } }
            .map { button ->
                FeatureRow(
                    title = when (button.iconRes) {
                        R.drawable.touchpad_plugin_action_24dp -> getString(R.string.feature_computer_screen)
                        R.drawable.run_command_plugin_icon_24dp -> getString(R.string.feature_run_command)
                        else -> button.name
                    },
                    icon = FEATURE_ICONS[button.iconRes] ?: button.iconRes,
                    opensScreen = button.iconRes != R.drawable.ic_baseline_content_paste_24,
                    onClick = button.onClick,
                    description = FEATURE_DESCRIPTIONS[button.iconRes]?.let(::getString),
                )
            }

    /** The screen being viewed and the terminals attached right now, or null when nothing is. */
    private fun activeSession(): ActiveSession? {
        val app = DeskLinkApplication.getInstance()
        val screen = app.getDevicePlugin(deviceId, ScreenControlPlugin::class.java)?.remoteLive == true
        val terminals = app.terminalClient(deviceId)?.state?.value?.sessions?.count { it.attached } ?: 0
        return if (screen || terminals > 0) ActiveSession(screen, terminals) else null
    }

    /** "3F2A 91C0…": enough of the fingerprint to recognise, the whole of it one tap away. */
    private fun shortFingerprint(device: Device): String =
        SslHelper.getCertificateHash(device.certificate).filter { it.isLetterOrDigit() }.uppercase().take(8).chunked(4).joinToString(" ") + "…"

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
                    pairMessage = null
                    refreshUI()
                    view?.let {
                        com.google.android.material.snackbar.Snackbar
                            .make(it, getString(R.string.pair_paired_with, device?.name.orEmpty()), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
                            .show()
                    }
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
