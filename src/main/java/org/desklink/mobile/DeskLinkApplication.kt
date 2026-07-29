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
import org.desklink.mobile.BuildConfig
import org.slf4j.impl.HandroidLoggerAdapter
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.ConcurrentHashMap


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

    private val deviceListChangedCallbacks = ConcurrentHashMap<String, DeviceListChangedCallback>()

    override fun onCreate() {
        super.onCreate()
        _instance = this
        setupSL4JLogging()
        Log.d("DeskLink/Application", "onCreate")
        ThemeUtil.setUserPreferredTheme(this)
        DeviceHelper.initializeDeviceId(this)
        RsaHelper.initialiseRsaKeys(this)
        SslHelper.initialiseCertificate(this)
        PluginFactory.initPluginInfo(this)
        NotificationHelper.initializeChannels(this)
        LifecycleHelper.initializeObserver()
        loadRememberedDevicesFromSettings()

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

    fun requestRemoteControl(deviceId: String?): Boolean {
        val device = getDevice(deviceId) ?: return false
        return runCatching {
            webRtcCoordinatorFor(device).requestRemoteControl()
            true
        }.onFailure { error ->
            Log.w("DeskLink/RemoteSession", "Could not request remote control", error)
        }.getOrDefault(false)
    }

    fun stopRemoteSession(deviceId: String?) {
        getDevice(deviceId)?.let { webRtcCoordinatorFor(it).stopRemoteSession() }
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
            service.onNetworkChange(null)
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
            onDeviceListChanged()
        }

        override fun pairingFailed(error: String) {
            onDeviceListChanged()
        }

        override fun unpaired(device: Device) {
            webRtcCoordinators.remove(device.deviceId)?.close()
            onDeviceListChanged()
            if (!device.isReachable) {
                scheduleForDeletion(device)
            }
        }
    }

    val connectionListener: ConnectionReceiver = object : ConnectionReceiver {
        @WorkerThread
        override fun onConnectionReceived(link: BaseLink) {
            val device = devices[link.deviceId]?.also { existing ->
                existing.addLink(link)
            } ?: run {
                Device(this@DeskLinkApplication, link).also { created ->
                    devices[link.deviceId] = created
                    created.addPairingCallback(devicePairingCallback)
                }
            }

            if (link is org.desklink.mobile.backends.lan.LanLink) {
                val pairingState = if (device.isPaired || TrustedDevices.isTrustedDevice(this@DeskLinkApplication, link.deviceId)) {
                    PairingState.PAIRED
                } else {
                    PairingState.NOT_PAIRED
                }
                val coordinator = webRtcCoordinatorFor(device)
                runCatching {
                    val retainedBinding = sessionManager.currentBinding(link.deviceId)
                        ?.takeIf { coordinator.hasActiveFeatureTransport() }
                    if (retainedBinding != null) {
                        // A replacement LAN connection is bootstrap/signaling
                        // capacity only. Re-registering it would invalidate a
                        // healthy WebRTC generation and make a screen lock
                        // look like a full device disconnect.
                        legacyLanBindings[link] = retainedBinding
                        device.setSessionTransport(retainedBinding.transport)
                        Log.i(
                            "DeskLink/Session",
                            "Retaining active WebRTC session while replacing LAN bootstrap link for ${link.deviceId}",
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
                    coordinator.beginIfSupported()
                }.onFailure { error ->
                    Log.e("DeskLink/Session", "Could not register LAN session", error)
                }
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
            if (link !is org.desklink.mobile.backends.lan.LanLink) return
            val device = devices[link.deviceId] ?: return
            webRtcCoordinatorFor(device).onBootstrapLinkReplaced()
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
            devices.remove(device.deviceId)
        }
    }

    private fun webRtcCoordinatorFor(device: Device): WebRtcSessionCoordinator =
        webRtcCoordinators.computeIfAbsent(device.deviceId) {
            WebRtcSessionCoordinator(this, device, sessionManager) { state, detail ->
                Log.i("DeskLink/WebRTC", "${device.deviceId}: $state${detail?.let { " ($it)" } ?: ""}")
                onDeviceListChanged()
            }
        }

    companion object {
        @JvmStatic
        private lateinit var _instance: DeskLinkApplication

        @JvmStatic
        fun getInstance(): DeskLinkApplication = _instance
    }
}
