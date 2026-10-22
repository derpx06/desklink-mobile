/*
 * SPDX-FileCopyrightText: 2023 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile

import android.app.Application
import android.os.Build
import android.os.StrictMode
import android.os.StrictMode.ThreadPolicy
import android.os.StrictMode.VmPolicy
import android.util.Log
import androidx.annotation.WorkerThread
import org.desklink.mobile.backends.BaseLink
import org.desklink.mobile.backends.BaseLinkProvider.ConnectionReceiver
import org.desklink.mobile.backends.lan.LanLink
import org.desklink.mobile.backends.relay.RelayLink
import org.desklink.mobile.backends.relay.RelayLinkProvider
import org.desklink.mobile.helpers.CrashReporter
import org.desklink.mobile.helpers.DeviceHelper
import org.desklink.mobile.helpers.LifecycleHelper
import org.desklink.mobile.helpers.NotificationHelper
import org.desklink.mobile.helpers.security.RsaHelper
import org.desklink.mobile.helpers.security.SslHelper
import org.desklink.mobile.helpers.ThreadHelper
import org.desklink.mobile.helpers.TrustedDevices
import org.desklink.mobile.PairingHandler.PairingCallback
import org.desklink.mobile.plugins.Plugin
import org.desklink.mobile.plugins.PluginFactory
import org.desklink.mobile.session.DeviceManager
import org.desklink.mobile.session.PairingState
import org.desklink.mobile.session.SessionBinding
import org.desklink.mobile.transport.DisconnectReason
import org.desklink.mobile.transport.LegacyLanTransport
import org.desklink.mobile.ui.ThemeUtil
import org.desklink.mobile.webrtc.WebRtcSessionCoordinator
import org.desklink.mobile.webrtc.WebRtcScreenDirection
import org.desklink.mobile.webrtc.terminal.TerminalClient
import org.desklink.mobile.BuildConfig
import org.slf4j.impl.HandroidLoggerAdapter
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference


/*
 * This class holds all the active devices and makes them accessible from every other class.
 * It also takes care of initializing all classes that need so when the app boots.
 * It provides a ConnectionReceiver that the BackgroundService uses to ping this class every time a new DeviceLink is created.
 */
class DeskLinkApplication : Application() {
    fun interface DeviceListChangedCallback {
        fun onDeviceListChanged()
    }

    val devices: ConcurrentHashMap<String, Device> = ConcurrentHashMap()

    /** The single owner of live LAN session identity and transport generations. */
    val sessionManager = DeviceManager()

    private val legacyLanBindings = ConcurrentHashMap<BaseLink, SessionBinding>()
    private val webRtcCoordinators = ConcurrentHashMap<String, WebRtcSessionCoordinator>()

    /** Each desktop's session password, in memory only. Outlives its coordinator, which is
     * replaced on every reconnect; dropped on unpair, never persisted. */
    private val sessionPasswords = ConcurrentHashMap<String, AtomicReference<String?>>()

    /** Whether the person was viewing this desktop when its WebRTC peer went away, so the view
     * is asked for again once a rebuilt peer is ready. Outlives the coordinator, like the password. */
    private val screenResumes = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicBoolean>()

    /**
     * The bootstrap link to this device is known-healthy right now, so make sure
     * a WebRTC session exists.
     *
     * Called on every heartbeat, which is the point. WebRTC previously started
     * only on a *reconnect* — so a link that came up once and then stayed up
     * had exactly one chance, and if that attempt was lost the session sat in
     * "Connecting" forever with every WebRTC-gated feature dead. Waiting for the
     * desktop's `restart_request` was no better: it is one packet, unacked, and
     * observed not to arrive.
     *
     * `onBootstrapReconnected` is idempotent — it returns immediately when a
     * feature transport is already live — so calling it repeatedly costs a map
     * lookup and a boolean, and turns the heartbeat into a self-healing tick
     * rather than only a liveness probe.
     */
    fun onBootstrapHealthy(deviceId: String) {
        webRtcCoordinators[deviceId]?.onBootstrapReconnected()
    }

    /** The local interface moved: every live WebRTC peer's candidates are stale. */
    fun onLocalNetworkMoved() {
        webRtcCoordinators.values.forEach(WebRtcSessionCoordinator::onLocalNetworkMoved)
    }

    /** The default network changed: peers bootstrapped away from home rebuild once (docs/RELAY.md §6). */
    fun onDefaultNetworkMoved() {
        devices.values.filter { !it.hasLanLink }.forEach { device ->
            webRtcCoordinators[device.deviceId]?.onLocalNetworkMoved()
        }
    }

    private val deviceListChangedCallbacks = ConcurrentHashMap<String, DeviceListChangedCallback>()

    override fun onCreate() {
        super.onCreate()
        _instance = this
        CrashReporter.install(this)
        setupSL4JLogging()
        Log.d("DeskLink/Application", "onCreate")
        ThemeUtil.setUserPreferredTheme(this)
        DeviceHelper.initializeDeviceId(this)
        RsaHelper.initialiseRsaKeys(this)
        SslHelper.initialiseCertificate(this)
        PluginFactory.initPluginInfo(this)
        NotificationHelper.initializeChannels(this)
        CrashReporter.offerPendingReport(this)
        LifecycleHelper.initializeObserver()
        org.desklink.mobile.plugins.clipboard.ClipboardListener.followForeground(this)
        // Objects holding Compose state are made here, on the main thread before any composition,
        // so a background thread touching them first cannot create state a running composition
        // cannot read (see PluginFactory.instantiatePluginForDevice).
        org.desklink.mobile.plugins.audio.PhoneAudio.hearingFrom
        org.desklink.mobile.plugins.capture.PhoneCapture.current
        org.desklink.mobile.plugins.screen.ScreenDisplay.viewArea
        org.desklink.mobile.plugins.screen.ScreenControlPlugin.latestStatus
        org.desklink.mobile.plugins.screen.PhoneScreenCaptureService.sharingDeviceId
        org.desklink.mobile.plugins.share.TransferProgress.current
        loadRememberedDevicesFromSettings()
        // The transfer history keeps 30 days: prune it once per start, off the main thread.
        org.desklink.mobile.helpers.ThreadHelper.execute { org.desklink.mobile.plugins.share.TransferHistory.store(this).prune() }

        if (BuildConfig.DEBUG && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            StrictMode.setVmPolicy(
                VmPolicy.Builder(StrictMode.getVmPolicy())
                    .detectActivityLeaks()
                    .detectLeakedClosableObjects()
                    .detectLeakedRegistrationObjects()
                    .detectFileUriExposure()
                    .detectContentUriWithoutPermission()
                    .detectCredentialProtectedWhileLocked()
                    .detectIncorrectContextUse()
                    .detectUnsafeIntentLaunch()
                    //.detectBlockedBackgroundActivityLaunch()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setThreadPolicy(
                ThreadPolicy.Builder(StrictMode.getThreadPolicy())
                    .detectUnbufferedIo()
                    .detectResourceMismatches()
                    .penaltyLog()
                    .build()
            )
        }
    }

    private fun setupSL4JLogging() {
        HandroidLoggerAdapter.DEBUG = BuildConfig.DEBUG
        HandroidLoggerAdapter.ANDROID_API_LEVEL = Build.VERSION.SDK_INT
        HandroidLoggerAdapter.APP_NAME = getString(R.string.product_name)
    }

    override fun onTerminate() {
        Log.d("DeskLink/Application", "onTerminate")
        webRtcCoordinators.values.forEach(WebRtcSessionCoordinator::close)
        devices.values.forEach(Device::closeLifecycle)
        sessionManager.terminateAll()
        super.onTerminate()
    }

    fun addDeviceListChangedCallback(key: String, callback: DeviceListChangedCallback) {
        deviceListChangedCallbacks[key] = callback
    }

    fun removeDeviceListChangedCallback(key: String) {
        deviceListChangedCallbacks.remove(key)
    }

    private fun onDeviceListChanged() {
        Log.i("MainActivity", "Device list changed, notifying ${deviceListChangedCallbacks.size} observers.")
        deviceListChangedCallbacks.values.forEach(DeviceListChangedCallback::onDeviceListChanged)
        org.desklink.mobile.ui.surfaces.DeskSurfaces.refresh(this)
    }

    fun getDevice(id: String?): Device? {
        if (id == null) {
            return null
        }
        return devices[id]
    }

    fun <T : Plugin> getDevicePlugin(deviceId: String?, pluginClass: Class<T>): T? {
        val device = getDevice(deviceId)
        return device?.getPlugin(pluginClass)
    }

    fun startWebRtcScreenCapture(deviceId: String?, permissionData: android.content.Intent): Boolean {
        val device = getDevice(deviceId) ?: return false
        return runCatching {
            webRtcCoordinatorFor(device).startPhoneScreenCapture(permissionData)
        }.isSuccess
    }

    fun stopWebRtcScreenCapture(deviceId: String?) {
        getDevice(deviceId)?.let { webRtcCoordinatorFor(it).stopPhoneScreenCapture() }
    }

    /** MediaProjection failure is recoverable for the paired WebRTC session;
     * it stops only the requested screen view. */
    fun reportWebRtcScreenCaptureFailure(deviceId: String?, reason: String) {
        getDevice(deviceId)?.let { webRtcCoordinatorFor(it).onPhoneScreenCaptureFailure(reason) }
    }

    /** Remote view/control entry points used only by explicit UI actions. */
    fun requestRemoteView(deviceId: String?, direction: WebRtcScreenDirection): Boolean {
        val device = getDevice(deviceId) ?: return false
        return runCatching {
            webRtcCoordinatorFor(device).requestRemoteView(direction)
            true
        }.onFailure { error ->
            Log.w("DeskLink/RemoteSession", "Could not request remote view", error)
        }.getOrDefault(false)
    }

    /** Home → Share screen: offer this phone's screen to the desk (`offer-view`). */
    fun offerPhoneScreen(deviceId: String?): Boolean {
        val device = getDevice(deviceId) ?: return false
        return runCatching {
            webRtcCoordinatorFor(device).offerPhoneScreen()
            true
        }.onFailure { error ->
            Log.w("DeskLink/RemoteSession", "Could not offer the phone screen", error)
        }.getOrDefault(false)
    }

    fun requestRemoteControl(deviceId: String?): Boolean {
        val device = getDevice(deviceId) ?: return false
        return runCatching {
            webRtcCoordinatorFor(device).requestRemoteControl()
            true
        }.onFailure { error ->
            Log.w("DeskLink/RemoteSession", "Could not request remote control", error)
        }.getOrDefault(false)
    }

    /** The desktop's session password, as the person typed it; offered with the next screen request. */
    fun setSessionPassword(deviceId: String?, password: String) {
        getDevice(deviceId)?.let { webRtcCoordinatorFor(it).sessionPassword.set(password) }
    }

    /** SCREEN_ON: every live peer is probed at once and rebuilt if silent (contract B2). */
    fun onScreenOn() = webRtcCoordinators.values.forEach { runCatching { it.onScreenOn() } }

    /** The live WebRTC connection's identity for [deviceId], or null (see [org.desklink.mobile.ui.SessionGate]). */
    fun connectionKey(deviceId: String?): Long? = getDevice(deviceId)?.let { webRtcCoordinatorFor(it).connectionKey() }

    /** The session password known for [deviceId] this run, if any. */
    fun sessionPassword(deviceId: String?): String? = getDevice(deviceId)?.let { webRtcCoordinatorFor(it).sessionPassword.get() }

    /** The terminal client of a paired device; it outlives any activity, so rotation keeps sessions. */
    /**
     * The terminal client of a paired device. Application-owned and never creating a coordinator:
     * it outlives each one, so an open terminal keeps its client across reconnects (it used to
     * hold the closed coordinator's dead client).
     */
    fun terminalClient(deviceId: String?): TerminalClient? =
        getDevice(deviceId)?.takeIf { it.isPaired }?.let(::terminalClientFor)

    private val terminalClients = ConcurrentHashMap<String, TerminalClient>()

    private fun terminalClientFor(device: Device): TerminalClient = terminalClients.computeIfAbsent(device.deviceId) { id ->
        TerminalClient(
            // Through whichever coordinator is current; none means the link is down.
            sender = { type, payload, callback ->
                webRtcCoordinators[id]?.sendTerminalMessage(type, payload, callback)
                    ?: callback.onFailure(org.desklink.mobile.transport.TransportError(org.desklink.mobile.transport.TransportErrorCode.CLOSED, "No DeskLink WebRTC session"))
            },
            sinkFactory = org.desklink.mobile.plugins.terminal.TerminalSinks.factory(this, id),
            executor = TerminalClient.newExecutor("DeskLink-Terminal-$id"),
            discard = { message, error -> Log.w("DeskLink/Terminal", message, error) },
            password = sessionPasswords.computeIfAbsent(id) { AtomicReference() },
            onJobFinished = { finished -> org.desklink.mobile.plugins.terminal.TerminalAttentionNotifier.jobFinished(this, id, finished) },
        ).also { client ->
            // The live-session notification follows the attached sessions, whoever changed them.
            // The job lives exactly as long as the client (shutDownTerminalClient).
            terminalWatchers[id] = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()).launch {
                client.state.map { state -> state.sessions.count { !it.gone } to state.sessions.count { it.attached && !it.gone } }.distinctUntilChanged().collect {
                    org.desklink.mobile.plugins.screen.SessionLiveNotification.refresh(this@DeskLinkApplication, id)
                    org.desklink.mobile.ui.surfaces.DeskSurfaces.refresh(this@DeskLinkApplication)
                }
            }
        }
    }

    private val terminalWatchers = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /** Unpair or deletion: the client's thread and its watcher both end. */
    private fun shutDownTerminalClient(deviceId: String) {
        terminalWatchers.remove(deviceId)?.cancel()
        terminalClients.remove(deviceId)?.shutdown()
    }

    fun switchRemoteScreen(deviceId: String?) {
        getDevice(deviceId)?.let { webRtcCoordinatorFor(it).switchRemoteScreen() }
    }

    fun stopRemoteSession(deviceId: String?) {
        getDevice(deviceId)?.let { webRtcCoordinatorFor(it).stopRemoteSession() }
    }

    /** Runs the ordered transport/session invalidation for explicit unpair. */
    @WorkerThread
    fun beginDeviceUnpair(device: Device) {
        webRtcCoordinators.remove(device.deviceId)?.closeForUnpair()
        org.desklink.mobile.webrtc.WebRtcFeatureProfile.forgetPeer(device.deviceId)
        org.desklink.mobile.ui.SessionGate.forget(this, device.deviceId)
        sessionPasswords.remove(device.deviceId)?.set(null)
        screenResumes.remove(device.deviceId)?.set(false)
        shutDownTerminalClient(device.deviceId)
        device.clearWebRtcBindingsForUnpair()
        sessionManager.revokePairing(device.deviceId)
    }

    /**
     * Requests a fresh discovery/signaling bootstrap only when WebRTC recovery
     * needs it. This is intentionally not invoked for a healthy WebRTC peer;
     * recreating LAN links during ordinary screen-off events used to replace a
     * working transport generation.
     */
    fun requestBootstrapReconnect() {
        val service = BackgroundService.instance
        if (service != null) {
            service.refreshDiscovery()
        } else {
            runCatching { BackgroundService.ForceRefreshConnections(this) }
                .onFailure { error ->
                    Log.w("DeskLink/Session", "Could not request DeskLink bootstrap recovery", error)
                }
        }
    }

    private fun loadRememberedDevicesFromSettings() {
        // Log.e("BackgroundService", "Loading remembered trusted devices")
        val trustedDevices = TrustedDevices.getAllTrustedDevices(this)
        trustedDevices.asSequence()
            .onEach { Log.d("DeskLink", "Loading device $it") }
            .forEach {
                try {
                    val device = Device(applicationContext, it)
                    val now = Date()
                    val x509Cert = device.certificate as X509Certificate
                    if(now < x509Cert.notBefore) {
                        throw CertificateException("Certificate not effective yet: "+x509Cert.notBefore)
                    }
                    else if(now > x509Cert.notAfter) {
                        throw CertificateException("Certificate already expired: "+x509Cert.notAfter)
                    }
                    devices[it] = device
                    device.addPairingCallback(devicePairingCallback)
                } catch (e: CertificateException) {
                    Log.w(
                        "DeskLink",
                        "Couldn't load the certificate for a remembered device. Removing from trusted list.", e
                    )
                    TrustedDevices.removeTrustedDevice(this, it)
                }
            }
    }

    private val devicePairingCallback: PairingCallback = object : PairingCallback {
        override fun incomingPairRequest() {
            onDeviceListChanged()
        }

        override fun pairingSuccessful() {
            devices.values.forEach { device -> webRtcCoordinatorFor(device).beginIfSupported() }
            RelayLinkProvider.instance?.onTrustChanged()
            onDeviceListChanged()
        }

        override fun pairingFailed(error: String) {
            onDeviceListChanged()
        }

        override fun unpaired(device: Device) {
            // Either side unpaired: the password belongs to that pairing.
            sessionPasswords[device.deviceId]?.set(null)
            screenResumes[device.deviceId]?.set(false)
            RelayLinkProvider.instance?.onTrustChanged()
            onDeviceListChanged()
            if (!device.isReachable) {
                scheduleForDeletion(device)
            }
        }
    }

    val connectionListener: ConnectionReceiver = object : ConnectionReceiver {
        @WorkerThread
        override fun onConnectionReceived(link: BaseLink) {
            val previousInstance = devices[link.deviceId]?.peerInstanceId
            val device = devices[link.deviceId]?.also { existing ->
                existing.addLink(link)
            } ?: run {
                Device(this@DeskLinkApplication, link).also { created ->
                    devices[link.deviceId] = created
                    created.addPairingCallback(devicePairingCallback)
                }
            }

            // A relay link is a bootstrap link only while no LAN link exists; it
            // stays attached otherwise, as the failover path (docs/RELAY.md §4).
            if (link is LanLink || (link is RelayLink && device.relayLink === link && !device.hasLanLink)) {
                registerBootstrapSession(device, link, previousInstance)
            }
            onDeviceListChanged()
        }

        @WorkerThread
        override fun onConnectionLost(link: BaseLink) {
            val device = devices[link.deviceId]
            Log.i("DeskLink/onConnectionLost", "removeLink, deviceId: ${link.deviceId}")
            val binding = legacyLanBindings.remove(link)
            val retainWebRtcSession = (
                device
                    ?.let { webRtcCoordinators[it.deviceId]?.shouldRetainSessionAfterBootstrapLoss() }
                    == true
            )
            val disconnectedCurrentSession = if (retainWebRtcSession) {
                Log.i(
                    "DeskLink/Session",
                    "LAN bootstrap link closed; preserving active WebRTC session for ${link.deviceId}",
                )
                false
            } else {
                binding?.let {
                    sessionManager.disconnectIfCurrent(it, DisconnectReason.NETWORK_LOST)
                } == true
            }
            if (disconnectedCurrentSession) {
                device?.setSessionTransport(null)
                device?.let { webRtcCoordinators.remove(it.deviceId)?.close() }
            }
            if (device != null) {
                device.removeLink(link)
                // The LAN went away but the relay is up: carry the session over
                // at once instead of waiting for the LAN to come back.
                val relay = device.relayLink
                if (binding != null && link is LanLink && relay != null && !device.hasLanLink) {
                    registerBootstrapSession(device, relay, device.peerInstanceId)
                }
                if (!device.isReachable && !device.isPaired) {
                    scheduleForDeletion(device)
                }
            } else {
                Log.d("DeskLink/onConnectionLost", "Removing connection to unknown device")
            }
            onDeviceListChanged()
        }

        @WorkerThread
        override fun onDeviceInfoUpdated(deviceInfo: DeviceInfo) {
            val device = devices[deviceInfo.id]
            if (device == null) {
                Log.e("DeskLink", "onDeviceInfoUpdated for an unknown device")
                return
            }
            val hasChanges = device.updateDeviceInfo(deviceInfo)
            if (hasChanges) {
                webRtcCoordinatorFor(device).beginIfSupported()
                onDeviceListChanged()
            }
        }

        @WorkerThread
        override fun onConnectionReplaced(link: BaseLink) {
            if (link !is LanLink) return
            val device = devices[link.deviceId] ?: return
            val coordinator = webRtcCoordinatorFor(device)
            // A replaced link (LanLink.reset) skips onConnectionReceived, so the
            // restart check must happen here too: a desktop that died without
            // a FIN leaves its stale link in place, and the new one arrives as
            // a replacement carrying the new epoch.
            val newInstance = link.deviceInfo.instanceId
            if (DeviceInfo.peerRestarted(device.peerInstanceId, newInstance)) {
                Log.i("DeskLink/Session", "${link.deviceId} restarted; replacing its WebRTC session")
                coordinator.onPeerRestarted()
            }
            if (newInstance != null) device.peerInstanceId = newInstance
            coordinator.onBootstrapLinkReplaced()
        }
    }

    /**
     * Makes [link] the device's bootstrap session: every LAN link, and a relay
     * link while the device has no LAN link.
     *
     * A healthy WebRTC peer is kept across a replacement bootstrap link, except
     * that a LAN link always supersedes a relay-bootstrapped session: that
     * peer was negotiated for the path away from home, so coming home costs
     * one rebuild (docs/RELAY.md §4).
     */
    @WorkerThread
    private fun registerBootstrapSession(device: Device, link: BaseLink, previousInstance: String?) {
        val pairingState = if (device.isPaired || TrustedDevices.isTrustedDevice(this, link.deviceId)) {
            PairingState.PAIRED
        } else {
            PairingState.NOT_PAIRED
        }
        val coordinator = webRtcCoordinatorFor(device)
        val newInstance = link.deviceInfo.instanceId
        // The desktop restarted: its previous process's WebRTC peer is
        // dead, so drop ours now instead of after the 15 s silence budget.
        val restarted = DeviceInfo.peerRestarted(previousInstance, newInstance)
        if (newInstance != null) device.peerInstanceId = newInstance
        if (restarted) {
            Log.i("DeskLink/Session", "${link.deviceId} restarted; replacing its WebRTC session")
            coordinator.onPeerRestarted()
        }
        runCatching {
            val current = sessionManager.currentBinding(link.deviceId)
            val comingHome = link is LanLink && (current?.transport as? LegacyLanTransport)?.link is RelayLink
            val retainedBinding = current
                ?.takeIf { !restarted && !comingHome && coordinator.hasActiveFeatureTransport() }
            if (retainedBinding != null) {
                // A replacement bootstrap connection is signaling capacity
                // only. Re-registering it would invalidate a healthy WebRTC
                // generation and make a screen lock look like a full device
                // disconnect.
                legacyLanBindings[link] = retainedBinding
                device.setSessionTransport(retainedBinding.transport)
                Log.i(
                    "DeskLink/Session",
                    "Retaining active WebRTC session while replacing the bootstrap link for ${link.deviceId}",
                )
            } else {
                val registration = sessionManager.register(
                    link.deviceId,
                    LegacyLanTransport(link),
                    pairingState,
                )
                legacyLanBindings[link] = registration.binding
                device.setSessionTransport(registration.binding.transport)
            }
            device.setControlPacketHandler(coordinator)
            if (retainedBinding != null) {
                coordinator.onBootstrapReconnected()
            } else {
                coordinator.beginIfSupported()
                // A fresh path away from home: the phone offers if it initiates,
                // otherwise it asks the desktop to (docs/RELAY.md §4).
                if (link is RelayLink) coordinator.requestPeerRestart()
            }
        }.onFailure { error ->
            Log.e("DeskLink/Session", "Could not register the ${link.name} session", error)
        }
    }

    fun scheduleForDeletion(device: Device) {
        // Note: By this point the `devices` map should be the only reference to `device`, so it
        //       should get garbage collected after removing it here. However, it's easy to leak
        //       references to Device from views, preventing them from being freed. You can use the
        //       debugger to check for alive instances of `Device` after a device is supposed to be
        //       destroyed to make sure we don't have leaks. Note that you might need to trigger
        //       `Runtime.getRuntime().gc()` to actually make them disappear. If we have leaks,
        //       deleting devices from the map is actually counterproductive because each time we
        //       detect the same device, a new Device object will be created (and leaked again).
        Log.i("DeskLink", "Scheduled for deletion: $device, paired: ${device.isPaired}, reachable: ${device.isReachable}")
        ThreadHelper.execute {
            try {Thread.sleep(1000)} catch (_: InterruptedException) { }
            if (device.isReachable) {
                Log.i("DeskLink", "Not deleting device since it's reachable again: $device")
                return@execute
            }
            if (device.isPaired) {
                Log.i("DeskLink", "Not deleting device since it's still paired: $device")
                return@execute
            }
            Log.i("DeskLink", "Deleting unpaired and unreachable device: $device")
            device.removePairingCallback(devicePairingCallback)
            webRtcCoordinators.remove(device.deviceId)?.close()
            sessionPasswords.remove(device.deviceId)?.set(null)
            screenResumes.remove(device.deviceId)
            shutDownTerminalClient(device.deviceId)
            devices.remove(device.deviceId)
        }
    }

    /**
     * The device's coordinator, created on first use. A new one is attached as
     * the device's signaling handler at once: unpair closes the old one and
     * clears the handler, and re-pairing over the still-open link (QR, or
     * accepting a request) registers no new bootstrap session. Without this the
     * desktop's answers queued forever and the device read "Connecting…".
     */
    /** The live coordinator for [deviceId], if any (the camera service and its consent use it). */
    fun webRtcCoordinator(deviceId: String?): WebRtcSessionCoordinator? = deviceId?.let { webRtcCoordinators[it] }

    private fun webRtcCoordinatorFor(device: Device): WebRtcSessionCoordinator {
        var created = false
        val coordinator = webRtcCoordinators.computeIfAbsent(device.deviceId) {
            created = true
            newWebRtcCoordinator(device)
        }
        // Outside computeIfAbsent: attaching delivers queued signaling, which
        // may look the coordinator up again.
        if (created) device.setControlPacketHandler(coordinator)
        return coordinator
    }

    private fun newWebRtcCoordinator(device: Device): WebRtcSessionCoordinator =
        WebRtcSessionCoordinator(
            this,
            device,
            sessionManager,
            sessionPasswords.computeIfAbsent(device.deviceId) { AtomicReference() },
            screenResumes.computeIfAbsent(device.deviceId) { java.util.concurrent.atomic.AtomicBoolean() },
            terminalClientFor(device),
        ) { state, detail ->
            Log.i("DeskLink/WebRTC", "${device.deviceId}: $state${detail?.let { " ($it)" } ?: ""}")
            onDeviceListChanged()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                org.desklink.mobile.plugins.screen.SessionLiveNotification.refresh(this, device.deviceId)
            }
        }

    companion object {
        @JvmStatic
        private lateinit var _instance: DeskLinkApplication

        @JvmStatic
        fun getInstance(): DeskLinkApplication = _instance
    }
}
