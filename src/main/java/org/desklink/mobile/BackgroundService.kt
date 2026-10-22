
package org.desklink.mobile

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.annotation.MainThread
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.MutableLiveData
import org.desklink.mobile.backends.BaseLinkProvider
import org.desklink.mobile.backends.BaseLinkProvider.ConnectionReceiver
import org.desklink.mobile.backends.bluetooth.BluetoothLinkProvider
import org.desklink.mobile.backends.lan.LanLinkProvider
import org.desklink.mobile.backends.relay.RelayLinkProvider
import org.desklink.mobile.backends.relay.RelaySettings
import org.desklink.mobile.helpers.NotificationHelper
import org.desklink.mobile.plugins.clipboard.ClipboardFloatingActivity
import org.desklink.mobile.plugins.clipboard.ClipboardPlugin
import org.desklink.mobile.plugins.runcommand.RunCommandActivity
import org.desklink.mobile.plugins.runcommand.RunCommandPlugin
import org.desklink.mobile.plugins.share.SendFileActivity
import org.desklink.mobile.ui.MainActivity
import org.desklink.mobile.R
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * This class (still) does 3 things:
 * - Keeps the app running by creating a foreground notification.
 * - Holds references to the active LinkProviders, but doesn't handle the DeviceLink those create (the DeskLink class does that).
 * - Listens for network connectivity changes and tells the LinkProviders to re-check for devices.
 * It can be started by the DeskLinkBroadcastReceiver on some events or when the MainActivity is launched.
 */
class BackgroundService : Service() {
    private lateinit var applicationInstance: DeskLinkApplication
    private var networkCallback: NetworkCallback? = null
    private var defaultNetworkCallback: NetworkCallback? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var connectionWakeLock: PowerManager.WakeLock? = null
    private val availableNetworks = Collections.newSetFromMap(ConcurrentHashMap<Network, Boolean>())
    private val linkAddresses = ConcurrentHashMap<Network, Set<java.net.InetAddress>>()
    /**
     * Link-provider network changes close sockets and restart discovery.  That
     * work is allowed to touch TLS/network objects, so it must never run from
     * the service main thread (NetworkCallback is normally asynchronous, but
     * refresh intents and settings actions are not).
     */
    private val networkChangeExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DeskLink-network-change").apply { isDaemon = true }
    }

    private val linkProviders = mutableListOf<BaseLinkProvider>()

    private val connectedToNonCellularNetwork = MutableLiveData<Boolean>()
    /** Indicates whether device is connected over wifi / usb / bluetooth / (anything other than cellular) */
    val isConnectedToNonCellularNetwork: LiveData<Boolean>
        get() = connectedToNonCellularNetwork

    fun updateForegroundNotification() {
        if (NotificationHelper.isPersistentNotificationEnabled(this)) {
            // Update the foreground notification with the currently connected device list
            val notificationManager = getSystemService<NotificationManager>()
            notificationManager?.notify(FOREGROUND_NOTIFICATION_ID, createForegroundNotification())
        }
    }

    private fun registerLinkProviders() {
        // Debug "Force relay": no LAN or Bluetooth links at all, so every
        // paired device is reached through the relay. Read at service start.
        if (!RelaySettings.forceRelay(this)) {
            linkProviders.add(LanLinkProvider(this))
            //linkProviders.add(LoopbackLinkProvider(this))
            linkProviders.add(BluetoothLinkProvider(this))
        }
        linkProviders.add(RelayLinkProvider(this))
    }

    private fun relayProvider(): RelayLinkProvider? = linkProviders.filterIsInstance<RelayLinkProvider>().firstOrNull()

    /** The relay settings changed: reconnect with them, the ladder starting over. */
    fun onRelaySettingsChanged() {
        if (!initialized) return
        runCatching { networkChangeExecutor.execute { relayProvider()?.reset(reconnect = true) } }
    }

    /**
     * The default network (the one the relay socket and media use) became a
     * different network. Reconnect the relay at once, ladder reset, and let the
     * existing recovery rebuild WebRTC once for peers that have no LAN link.
     * Debounced like [onLocalNetworkMoved]: a switch reports lost and available
     * moments apart.
     */
    private fun onDefaultNetworkChanged() {
        networkMovedDebounce.removeCallbacks(defaultNetworkTask)
        networkMovedDebounce.postDelayed(defaultNetworkTask, 1_000L)
    }

    private val defaultNetworkTask = Runnable {
        if (!initialized) return@Runnable
        runCatching { networkChangeExecutor.execute { relayProvider()?.reset(reconnect = true) } }
        applicationInstance.onDefaultNetworkMoved()
    }

    /** The app came to the foreground: a relay that gave up gets a fresh ladder. */
    private val foregroundObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            if (!initialized) return
            runCatching { networkChangeExecutor.execute { relayProvider()?.reset(reconnect = false) } }
        }
    }

    fun onNetworkChange(network: Network?) {
        if (!initialized) {
            Log.d(LOG_TAG, "ignoring onNetworkChange called before the service is initialized")
            return
        }
        Log.d(LOG_TAG, "onNetworkChange")
        runCatching {
            networkChangeExecutor.execute {
                if (!initialized) return@execute
                for (linkProvider in linkProviders) {
                    runCatching { linkProvider.onNetworkChange(network) }
                        .onFailure { error ->
                            Log.w(LOG_TAG, "Link-provider network refresh failed", error)
                        }
                }
            }
        }.onFailure { error ->
            // A refresh can race service teardown. The service is already
            // stopping in this case, so dropping this refresh is safer than
            // surfacing RejectedExecutionException to Android.
            Log.d(LOG_TAG, "Ignoring network refresh during service shutdown", error)
        }
    }

    /** Unicasts this device's identity to one address (QR pairing's host hint). */
    fun sendIdentityTo(address: java.net.InetAddress) {
        networkChangeExecutor.execute {
            linkProviders.filterIsInstance<org.desklink.mobile.backends.lan.LanLinkProvider>()
                .forEach { it.sendUdpIdentityPacket(listOf(address), null) }
        }
    }

    /** Re-announces on every provider without dropping any link. */
    fun refreshDiscovery() {
        if (!initialized) return
        runCatching {
            networkChangeExecutor.execute {
                if (!initialized) return@execute
                for (linkProvider in linkProviders) {
                    runCatching { linkProvider.refreshDiscovery() }
                        .onFailure { Log.w(LOG_TAG, "Link-provider discovery refresh failed", it) }
                }
            }
        }.onFailure { Log.d(LOG_TAG, "Ignoring discovery refresh during service shutdown", it) }
    }

    /**
     * The interface the links and WebRTC candidates were bound to is gone or
     * re-addressed: drop the LAN links, re-announce, and rebuild WebRTC now
     * rather than waiting out its 15 s silence budget.
     */
    private fun onLocalNetworkMoved(network: Network?) {
        // Debounced: a make-before-break switch reports onAvailable(new) then
        // onLost(old) moments apart, and acting on each rebuilt everything twice.
        networkMovedDebounce.removeCallbacks(networkMovedTask)
        networkMovedDebounce.postDelayed(networkMovedTask, 1_000L)
    }

    private val networkMovedDebounce = android.os.Handler(android.os.Looper.getMainLooper())
    private val networkMovedTask = Runnable {
        if (!initialized) return@Runnable
        onNetworkChange(null)
        applicationInstance.onLocalNetworkMoved()
    }

    fun addConnectionListener(connectionReceiver: ConnectionReceiver) {
        for (linkProvider in linkProviders) {
            linkProvider.addConnectionReceiver(connectionReceiver)
        }
    }

    fun removeConnectionListener(connectionReceiver: ConnectionReceiver) {
        for (linkProvider in linkProviders) {
            linkProvider.removeConnectionReceiver(connectionReceiver)
        }
    }

    /** This will called only once, even if we launch the service intent several times */
    @MainThread
    override fun onCreate() {
        super.onCreate()
        Log.d("DeskLink/BgService", "onCreate")
        this.applicationInstance = DeskLinkApplication.getInstance()
        instance = this
        acquireWifiLock()

        DeskLinkApplication.getInstance().addDeviceListChangedCallback(
            "BackgroundService",
            this::onDeviceConnectionStateChanged,
        )

        // F7: automation triggers (broadcasts and callbacks only, nothing polled).
        org.desklink.mobile.plugins.automations.AutomationTriggers.start(this)

        // Register screen on listener
        val filter = IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) }
        // See: https://developer.android.com/reference/android/net/ConnectivityManager.html#CONNECTIVITY_ACTION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        }
        registerReceiver(DeskLinkBroadcastReceiver(), filter)

        // Watch for changes on all network connections except cellular networks
        val networkRequestBuilder = createNonCellularNetworkRequestBuilder()
        val connectivityManager = this.getSystemService<ConnectivityManager>()
        val callback = object : NetworkCallback() {

            // All callbacks runs on a dedicated thread that isn't the main thread

            // VPNs are ignored: they come and go without moving the LAN
            // interface, and reacting to them tore down healthy sessions.
            private fun isVpn(network: Network) = connectivityManager
                ?.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

            override fun onAvailable(network: Network) {
                Log.i("BackgroundService", "Valid network available")
                availableNetworks.add(network)
                connectedToNonCellularNetwork.postValue(availableNetworks.isNotEmpty())
                if (wifiLock == null) acquireWifiLock()
                // A new network next to the current one (make-before-break
                // Wi-Fi, ethernet) moves nothing that exists: announce on it,
                // drop nothing. Previously only the *first* network did
                // anything, so a switch never triggered discovery at all.
                if (!isVpn(network)) refreshDiscovery()
            }

            override fun onLost(network: Network) {
                Log.i("BackgroundService", "Valid network lost")
                availableNetworks.remove(network)
                connectedToNonCellularNetwork.postValue(availableNetworks.isNotEmpty())
                // Any lost LAN network may have carried the links. Capabilities
                // can't be read for a lost network, so the address map decides:
                // VPNs never get an entry (see onLinkPropertiesChanged).
                if (linkAddresses.remove(network) != null) onLocalNetworkMoved(null)
            }

            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                if (isVpn(network)) return
                // IPv4 only: the LAN path is IPv4, while IPv6 privacy addresses
                // rotate and router advertisements add some late — counting
                // those tore down healthy links for nothing.
                val addresses = properties.linkAddresses
                    .map { it.address }
                    .filterIsInstance<java.net.Inet4Address>()
                    .toSet()
                val previous = linkAddresses.put(network, addresses)
                // The first report just records the baseline. A later change
                // is a DHCP renewal or roam that re-addressed the interface.
                if (previous != null && previous != addresses) {
                    Log.i("BackgroundService", "Local address changed")
                    onLocalNetworkMoved(network)
                }
            }
        }
        networkCallback = callback
        connectivityManager?.registerNetworkCallback(networkRequestBuilder.build(), callback)

        val defaultCallback = object : NetworkCallback() {
            // Registration reports the current default first: that is the baseline, not a change.
            private var current: Network? = null

            override fun onAvailable(network: Network) {
                val previous = current
                current = network
                if (previous != null && previous != network) onDefaultNetworkChanged()
            }

            override fun onLost(network: Network) {
                if (network == current) {
                    current = null
                    onDefaultNetworkChanged()
                }
            }
        }
        defaultNetworkCallback = defaultCallback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivityManager?.registerDefaultNetworkCallback(defaultCallback)
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(foregroundObserver)

        registerLinkProviders()
        addConnectionListener(applicationInstance.connectionListener) // Link Providers need to be already registered
        for (linkProvider in linkProviders) {
            linkProvider.onStart()
        }
        initialized = true
        updateConnectionPowerLease()
    }

    private fun onDeviceConnectionStateChanged() {
        updateForegroundNotification()
        updateConnectionPowerLease()
    }

    /**
     * A partial wake lock only while something is really being done: a terminal attached, the desk
     * screen viewed or controlled, this phone's screen shared, or a file moving. Never merely for
     * being connected: an always-held lock is what ColorOS's GuardElf killed the app for
     * ("guardelf_wakelock"), and OEM freezers release it anyway. Each hold has a timeout and is
     * renewed while the work lasts, so a missed release cannot keep the CPU up.
     *
     * The check runs on a main-thread Handler, which does not wake a sleeping phone: while asleep
     * nothing is checked, and nothing needs to be.
     */
    private fun updateConnectionPowerLease() {
        if (!::applicationInstance.isInitialized) return
        val working = applicationInstance.devices.values.any { device -> device.isPaired && hasActiveWork(device) }
        if (working) acquireWorkWakeLock() else releaseConnectionWakeLock()
        leaseRecheck.removeCallbacks(leaseRecheckTask)
        leaseRecheck.postDelayed(leaseRecheckTask, WORK_LEASE_CHECK_MS)
    }

    private fun hasActiveWork(device: org.desklink.mobile.Device): Boolean {
        val id = device.deviceId
        val screen = device.getPlugin(org.desklink.mobile.plugins.screen.ScreenControlPlugin::class.java)
        return isActiveWork(
            terminalAttached = applicationInstance.terminalClient(id)?.state?.value?.sessions?.any { it.attached && !it.gone } == true,
            deskScreen = screen?.remoteLive == true,
            phoneScreenShared = org.desklink.mobile.plugins.screen.PhoneScreenCaptureService.sharingDeviceId == id,
            transferring = org.desklink.mobile.plugins.share.TransferProgress.visibleFor(id)?.finished == false,
        )
    }

    private val leaseRecheck = android.os.Handler(android.os.Looper.getMainLooper())
    private val leaseRecheckTask = Runnable { updateConnectionPowerLease() }

    private fun acquireWorkWakeLock() {
        val powerManager = getSystemService<PowerManager>() ?: return
        val lock = connectionWakeLock ?: powerManager
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DeskLink:active-work")
            .apply { setReferenceCounted(false) }
            .also { connectionWakeLock = it }
        // Re-arms the timeout on every check while the work lasts.
        runCatching { lock.acquire(WORK_LEASE_MS) }
            .onFailure { error -> Log.w(LOG_TAG, "Could not acquire DeskLink work wake lock", error) }
    }

    private fun releaseConnectionWakeLock() {
        connectionWakeLock?.let { lock ->
            if (lock.isHeld) {
                runCatching { lock.release() }
                    .onFailure { error -> Log.w(LOG_TAG, "Could not release DeskLink work wake lock", error) }
            }
        }
    }

    fun changePersistentNotificationVisibility(visible: Boolean) {
        if (visible) {
            updateForegroundNotification()
        }
        else {
            // Android requires a foreground service to keep its user-visible
            // notification while it owns a persistent LAN connection. The
            // notification channel may be hidden by the user, but dropping
            // foreground mode makes screen-off/OEM process killing likely.
            updateForegroundNotification()
        }
    }

    private fun acquireWifiLock() {
        val wifiManager = getSystemService<WifiManager>() ?: return
        if (!wifiManager.isWifiEnabled) return
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifiManager.createWifiLock(mode, "DeskLink:lan").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun createForegroundNotification(): Notification {
        // Why is this needed: https://developer.android.com/guide/components/services#Foreground

        val connectedDevices = mutableListOf<String>()
        val connectedDeviceIds = mutableListOf<String>()
        for (device in applicationInstance.devices.values) {
            if (device.isReachable && device.isPaired) {
                connectedDeviceIds.add(device.deviceId)
                connectedDevices.add(device.name)
            }
        }

        val intent = Intent(this, MainActivity::class.java)
        if (connectedDeviceIds.size == 1) {
            // Force open screen of the only connected device
            intent.putExtra(MainActivity.EXTRA_DEVICE_ID, connectedDeviceIds[0])
        }

        val pi = PendingIntent.getActivity(this, 0, intent, UPDATE_IMMUTABLE_FLAGS)
        val notification = NotificationCompat.Builder(this, NotificationHelper.Channels.PERSISTENT).apply {
            setSmallIcon(R.drawable.ic_notification)
            setOngoing(true)
            setContentIntent(pi)
            setPriority(NotificationCompat.PRIORITY_MIN) //MIN so it's not shown in the status bar before Oreo, on Oreo it will be bumped to LOW
            setShowWhen(false)
            setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            setAutoCancel(false)
            setGroup("BackgroundService")
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // Pre-oreo, the notification will have an empty title line without this
            notification.setContentTitle(getString(R.string.app_name))
        }

        if (connectedDevices.isEmpty()) {
            notification.setContentText(getString(R.string.foreground_notification_no_devices))
        }
        else {
            notification.setContentText(getString(R.string.foreground_notification_devices, connectedDevices.joinToString(", ")))

            val powerManager = getSystemService<PowerManager>()
            val ignoresBatteryOptimizations = powerManager
                ?.isIgnoringBatteryOptimizations(packageName)
                ?: false
            if (
                BackgroundExecutionPolicy.shouldOfferBatteryExemption(
                    hasConnectedDevice = true,
                    ignoresBatteryOptimizations = ignoresBatteryOptimizations,
                )
            ) {
                val exemptionIntent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                )
                val exemptionPendingIntent = PendingIntent.getActivity(
                    this,
                    4,
                    exemptionIntent,
                    UPDATE_IMMUTABLE_FLAGS,
                )
                notification.addAction(
                    0,
                    getString(R.string.foreground_notification_allow_background),
                    exemptionPendingIntent,
                )
            }

            // Adding an action button to send clipboard manually in Android 10 and later.
            if (!ClipboardPlugin.canSyncAutomatically(this)) {
                val sendClipboard = ClipboardFloatingActivity.getIntent(this, true)
                val sendPendingClipboard = PendingIntent.getActivity(this, 3, sendClipboard, UPDATE_IMMUTABLE_FLAGS)
                notification.addAction(0, getString(R.string.foreground_notification_send_clipboard), sendPendingClipboard)
            }

            if (connectedDeviceIds.size == 1) {
                val deviceId = connectedDeviceIds[0]
                val device = DeskLinkApplication.getInstance().getDevice(deviceId)
                if (device != null) {
                    // Adding two action buttons only when there is a single device connected.
                    // Setting up Send File Intent.
                    val sendFile = Intent(this, SendFileActivity::class.java)
                    sendFile.putExtra("deviceId", deviceId)
                    val sendPendingFile = PendingIntent.getActivity(this, 1, sendFile, UPDATE_IMMUTABLE_FLAGS)
                    notification.addAction(0, getString(R.string.send_files), sendPendingFile)

                    // Checking if there are registered commands and adding the button.
                    val plugin = device.getPlugin("RunCommandPlugin") as RunCommandPlugin?
                    if (plugin != null && plugin.commandList.isNotEmpty()) {
                        val runCommand = Intent(this, RunCommandActivity::class.java)
                        runCommand.putExtra("deviceId", connectedDeviceIds[0])
                        val runPendingCommand = PendingIntent.getActivity(this, 2, runCommand, UPDATE_IMMUTABLE_FLAGS)
                        notification.addAction(0, getString(R.string.pref_plugin_runcommand), runPendingCommand)
                    }
                }
            }
        }
        return notification.build()
    }

    override fun onDestroy() {
        Log.d("DeskLink/BgService", "onDestroy")
        initialized = false
        leaseRecheck.removeCallbacks(leaseRecheckTask)
        networkMovedDebounce.removeCallbacks(networkMovedTask)
        networkCallback?.let { callback ->
            getSystemService<ConnectivityManager>()?.unregisterNetworkCallback(callback)
        }
        networkCallback = null
        defaultNetworkCallback?.let { callback ->
            runCatching { getSystemService<ConnectivityManager>()?.unregisterNetworkCallback(callback) }
        }
        defaultNetworkCallback = null
        ProcessLifecycleOwner.get().lifecycle.removeObserver(foregroundObserver)
        networkMovedDebounce.removeCallbacks(defaultNetworkTask)
        availableNetworks.clear()
        // Provider shutdown closes sockets and TLS streams. Keep it off the
        // service main thread as well; stopping a foreground service can race
        // with a network callback and Android rejects TLS I/O on main.
        val providersToStop = linkProviders.toList()
        runCatching {
            networkChangeExecutor.execute {
                providersToStop.forEach { linkProvider ->
                    runCatching { linkProvider.onStop() }
                        .onFailure { error ->
                            Log.w(LOG_TAG, "Link-provider shutdown failed", error)
                        }
                }
            }
        }.onFailure { error ->
            Log.d(LOG_TAG, "Link providers were already stopping", error)
        }
        networkChangeExecutor.shutdown()
        wifiLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wifiLock = null
        leaseRecheck.removeCallbacks(leaseRecheckTask)
        releaseConnectionWakeLock()
        connectionWakeLock = null
        DeskLinkApplication.getInstance().removeDeviceListChangedCallback("BackgroundService")
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(LOG_TAG, "onStartCommand")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(FOREGROUND_NOTIFICATION_ID, createForegroundNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } catch (e: IllegalStateException) { // To catch ForegroundServiceStartNotAllowedException
                Log.w("BackgroundService", "Couldn't startForeground", e)
                return START_STICKY
            }
        }
        else {
            startForeground(FOREGROUND_NOTIFICATION_ID, createForegroundNotification())
        }
        // Screen-on and connectivity broadcasts only re-announce. They used to
        // drop every LAN link on each unlock, killing in-flight negotiations
        // while the desktop ignored our beacon for up to 15 s.
        if (intent != null && intent.getBooleanExtra("refresh", false)) {
            refreshDiscovery()
        }
        updateConnectionPowerLease()
        return START_STICKY
    }

    companion object {
        const val LOG_TAG = "DeskLink/BackgroundService"

        const val UPDATE_IMMUTABLE_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        private const val FOREGROUND_NOTIFICATION_ID = 1

        /** One hold lasts at most this long; renewed every [WORK_LEASE_CHECK_MS] while working. */
        const val WORK_LEASE_MS = 60_000L
        const val WORK_LEASE_CHECK_MS = 20_000L

        /** Work that needs the CPU with the screen off; being connected is not work. */
        fun isActiveWork(terminalAttached: Boolean, deskScreen: Boolean, phoneScreenShared: Boolean, transferring: Boolean): Boolean =
            terminalAttached || deskScreen || phoneScreenShared || transferring

        @JvmStatic
        var instance: BackgroundService? = null
            private set

        private var initialized = false

        private fun createNonCellularNetworkRequestBuilder(): NetworkRequest.Builder {
            return NetworkRequest.Builder().apply {
                addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                addTransportType(NetworkCapabilities.TRANSPORT_BLUETOOTH)
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O) {
                    addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                }
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.S) {
                    addTransportType(NetworkCapabilities.TRANSPORT_USB)
                    addTransportType(NetworkCapabilities.TRANSPORT_LOWPAN)
                }
            }
        }

        fun Start(context: Context) {
            Log.d(LOG_TAG, "Start")
            val intent = Intent(context, BackgroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        @JvmStatic
        fun ForceRefreshConnections(context: Context) {
            Log.d(LOG_TAG, "ForceRefreshConnections")
            val intent = Intent(context, BackgroundService::class.java)
            intent.putExtra("refresh", true)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

/** Pure policy kept separate from Android services so its behavior is unit-testable. */
internal object BackgroundExecutionPolicy {
    fun shouldOfferBatteryExemption(
        hasConnectedDevice: Boolean,
        ignoresBatteryOptimizations: Boolean,
    ): Boolean = hasConnectedDevice && !ignoresBatteryOptimizations
}
