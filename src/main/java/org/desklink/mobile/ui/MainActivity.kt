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
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.apache.commons.lang3.ArrayUtils
import org.desklink.mobile.BackgroundService
import org.desklink.mobile.BuildConfig
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.plugins.desksettings.DeskSettingsPlugin
import org.desklink.mobile.plugins.mprisreceiver.MprisReceiverPlugin
import org.desklink.mobile.Device
import org.desklink.mobile.PairingHandler.PairState
import org.desklink.mobile.QrPairing
import org.desklink.mobile.R
import org.desklink.mobile.helpers.DeviceHelper
import org.desklink.mobile.helpers.security.SslHelper
import org.desklink.mobile.plugins.Plugin
import org.desklink.mobile.plugins.mousepad.MousePadActivity
import org.desklink.mobile.plugins.notifications.NotificationsPlugin
import org.desklink.mobile.plugins.screen.PhoneScreenCaptureService
import org.desklink.mobile.plugins.screen.SessionLiveNotification
import org.desklink.mobile.plugins.share.SharePlugin
import org.desklink.mobile.plugins.share.ShareDestination
import org.desklink.mobile.plugins.share.PreviewKind
import org.desklink.mobile.plugins.share.TransferHistory
import org.desklink.mobile.plugins.share.TransferProgress
import org.desklink.mobile.plugins.share.TransferRecord
import org.desklink.mobile.plugins.share.previewKind
import org.desklink.mobile.ui.compose.screen.transfers.ImageViewer
import org.desklink.mobile.ui.compose.screen.transfers.LiveTransfer
import org.desklink.mobile.ui.compose.screen.transfers.TransferActions
import org.desklink.mobile.ui.compose.screen.transfers.VideoViewer
import org.desklink.mobile.plugins.terminal.TerminalActivity
import org.desklink.mobile.ui.about.EasterEggActivity
import org.desklink.mobile.ui.about.LicensesActivity
import org.desklink.mobile.ui.about.getApplicationAboutData
import org.desklink.mobile.ui.compose.DeskLinkTheme
import org.desklink.mobile.ui.compose.components.ScreenHeader
import org.desklink.mobile.ui.compose.components.rememberConnectionStats
import org.desklink.mobile.ui.compose.screen.about.AboutScreen
import org.desklink.mobile.ui.compose.screen.home.HomeActions
import org.desklink.mobile.ui.compose.screen.home.HomeModel
import org.desklink.mobile.ui.compose.screen.home.HomeScreen
import org.desklink.mobile.ui.compose.screen.pairing.NearbyComputer
import org.desklink.mobile.ui.compose.screen.pairing.PairCodeScreen
import org.desklink.mobile.ui.compose.screen.pairing.PairCodeState
import org.desklink.mobile.ui.compose.screen.pairing.PairStage
import org.desklink.mobile.ui.compose.screen.pairing.ScanScreen
import org.desklink.mobile.ui.compose.screen.pairing.WelcomeScreen
import org.desklink.mobile.ui.compose.screen.settings.DeskSettings
import org.desklink.mobile.ui.compose.screen.settings.SettingsActions
import org.desklink.mobile.ui.compose.screen.settings.SettingsPage
import org.desklink.mobile.ui.compose.screen.settings.SettingsTab
import org.desklink.mobile.ui.compose.screen.settings.waitingPermissions
import org.desklink.mobile.ui.compose.screen.tabs.FilesTab
import org.desklink.mobile.ui.compose.screen.tabs.NotesTab
import org.desklink.mobile.ui.compose.screen.tabs.TerminalsTab
import org.desklink.mobile.ui.compose.shell.AppTab
import org.desklink.mobile.ui.compose.shell.QuickAction
import org.desklink.mobile.ui.compose.shell.TabBadges
import org.desklink.mobile.ui.compose.shell.TabBarHeight
import org.desklink.mobile.ui.compose.shell.TabNav
import org.desklink.mobile.ui.compose.shell.TabsShell
import org.desklink.mobile.ui.compose.shell.filesBadge
import org.desklink.mobile.ui.compose.shell.isWide
import org.desklink.mobile.ui.compose.extensions.device.toUiModel
import org.desklink.mobile.plugins.folders.BucketEngine
import org.desklink.mobile.plugins.folders.BucketUi
import org.desklink.mobile.plugins.notes.NotesUi
import org.desklink.mobile.plugins.terminal.TerminalAttentionBadge
import androidx.compose.runtime.collectAsState
import org.desklink.mobile.ui.compose.screen.settings.showPluginPermission
import org.desklink.mobile.ui.compose.tokens

private const val STORAGE_LOCATION_CONFIGURED = 2020
private const val STATE_STACK = "stack" // Saved only in onSaveInstanceState
private const val STATE_TABS = "tabs"
private const val STATE_NOTE = "note"
private const val STATE_SELECTED_DEVICE = "selected_device" // Saved persistently in preferences
private const val REFRESH_MS = 1_000L

/**
 * One Compose root. With a paired computer it is the tab shell (Home, Files, Notes, Terminals, Settings: [nav]
 * keeps a back stack per tab); pairing, the viewer and the like are pushed full screen on a small back stack
 * of route strings. Both survive rotation and process death. The terminal and the desk screen are their own
 * activities.
 */
class MainActivity : AppCompatActivity(), OnSharedPreferenceChangeListener {
    private val stack = mutableStateListOf<String>()

    /** The tabs: which is showing and each one's own back stack. */
    private var nav = TabNav()

    /** The note being edited in the Notes tab: an id, `NotesActivity.NEW`, or null for the list. */
    private val notesEditing = mutableStateOf<String?>(null)

    /** Bumped by the Home tab's long-press: opens the computers sheet. */
    private var computersRequest by mutableIntStateOf(0)
    private val bucketUi by lazy { BucketUi(this) }
    private val notesUi by lazy { NotesUi(this, notesEditing) }
    private var selectedId by mutableStateOf<String?>(null)
    private var tick by mutableIntStateOf(0)
    private val snackbar = SnackbarHostState()
    private var knownPaired: Set<String>? = null
    private val selection: SharedPreferences by lazy { getSharedPreferences("stored_menu_selection", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Long-press on the clipboard tile: the snippets (F6), not the home screen.
        if (savedInstanceState == null && clipboardTileLongPress(intent)) {
            startActivity(org.desklink.mobile.plugins.notes.NotesActivity.intent(this, snippets = true))
            finish()
            return
        }
        DeviceHelper.initializeDeviceId(this)

        // Registered before reading the name: reading it can start a fetch that writes it.
        PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(this)
        DeviceHelper.getDeviceName(this)

        selectedId = savedInstanceState?.getString(STATE_SELECTED_DEVICE) ?: selection.getString(STATE_SELECTED_DEVICE, null)
        stack.addAll(savedInstanceState?.getStringArrayList(STATE_STACK)?.takeIf { it.isNotEmpty() } ?: listOf(HOME))
        savedInstanceState?.getStringArrayList(STATE_TABS)?.let { nav = TabNav.restore(it) }
        notesEditing.value = savedInstanceState?.getString(STATE_NOTE)
        if (savedInstanceState == null) handleIntent(intent)

        setContent { DeskLinkTheme(this) { Root() } }
        if (BuildConfig.DEBUG && savedInstanceState == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) logFirstFrame()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    tick++
                    delay(REFRESH_MS)
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), RESULT_NOTIFICATIONS_ENABLED)
        }
    }

    /** Debug: the time from the process starting to the first frame drawn (the number the W7 target is measured by). */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.N)
    private fun logFirstFrame() {
        val decor = window.decorView
        decor.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                decor.viewTreeObserver.removeOnPreDrawListener(this)
                decor.post {
                    val ms = android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()
                    Log.i("DeskLink/Startup", "first frame $ms ms after the process started")
                }
                return true
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun clipboardTileLongPress(intent: Intent): Boolean {
        if (intent.action != "android.service.quicksettings.action.QS_TILE_PREFERENCES") return false
        @Suppress("DEPRECATION")
        val tile = intent.getParcelableExtra<android.content.ComponentName>(Intent.EXTRA_COMPONENT_NAME)
        return tile?.className == org.desklink.mobile.plugins.clipboard.ClipboardTileService::class.java.name
    }

    /** Launcher, notifications and shortcuts: which computer, and the pairing screen if one waits. */
    private fun handleIntent(intent: Intent) {
        when {
            // Launcher shortcuts (long-press the icon).
            intent.action == ACTION_NEW_NOTE -> {
                resetTo(HOME)
                nav.select(AppTab.NOTES)
                notesEditing.value = org.desklink.mobile.plugins.notes.NotesActivity.NEW
            }
            intent.action == ACTION_ADD_TO_BUCKET -> startActivity(org.desklink.mobile.plugins.folders.BucketActivity.intent(this, selectedId))
            intent.hasExtra(FLAG_FORCE_OVERVIEW) -> { resetTo(HOME); nav.select(AppTab.HOME) }
            // A Settings page asked for from outside (the desk screen's settings button): the tab, on that page.
            intent.hasExtra(EXTRA_SETTINGS_PAGE) -> {
                val page = intent.getStringExtra(EXTRA_SETTINGS_PAGE)
                resetTo(HOME)
                nav.reset(AppTab.SETTINGS)
                SettingsPage.of(page)?.let { nav.push(AppTab.SETTINGS, it.route) }
                nav.select(AppTab.SETTINGS)
            }
            intent.hasExtra(EXTRA_DEVICE_ID) -> {
                val id = intent.getStringExtra(EXTRA_DEVICE_ID) ?: return
                // Navigation only. This activity is exported, so it never acts on pairing itself:
                // accepting and rejecting go through the non-exported PairingActionReceiver or the
                // pairing screen's own buttons.
                if (intent.getStringExtra(PAIR_REQUEST_STATUS) == PAIRING_PENDING) {
                    resetTo(HOME)
                    push(pairRoute(id))
                } else if (DeskLinkApplication.getInstance().getDevice(id)?.isPaired == true) {
                    select(id)
                    resetTo(HOME)
                    nav.select(AppTab.HOME)
                }
            }
        }
    }

    override fun onStop() {
        org.desklink.mobile.plugins.notes.NotesStore.flush(this)
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        org.desklink.mobile.helpers.CrashLoop.clear(this)
        BackgroundService.Start(applicationContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_STACK, ArrayList(stack))
        outState.putStringArrayList(STATE_TABS, ArrayList(nav.snapshot()))
        notesEditing.value?.let { outState.putString(STATE_NOTE, it) }
        outState.putString(STATE_SELECTED_DEVICE, selectedId)
    }

    // ---- navigation ----------------------------------------------------------------------------------

    private fun push(route: String) {
        if (stack.lastOrNull() != route) stack.add(route)
    }

    private fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    private fun resetTo(route: String) {
        stack.clear()
        stack.add(route)
    }

    private fun select(id: String?) {
        selectedId = id
        selection.edit { putString(STATE_SELECTED_DEVICE, id) }
    }

    fun showMessage(message: String) {
        lifecycleScope.launch { snackbar.showSnackbar(message) }
    }

    /**
     * Keeps the selection on a paired computer (the last one picked, else the first), switches to
     * a newly paired one, and opens the pairing screen when a computer asks to pair.
     */
    private fun reconcile(paired: List<Device>) {
        val ids = paired.map { it.deviceId }.toSet()
        val previous = knownPaired
        knownPaired = ids
        val fresh = previous?.let { ids - it }.orEmpty()
        if (fresh.isNotEmpty()) {
            val id = fresh.first()
            select(id)
            if (stack.any { it == SCAN || it.startsWith(PAIR_PREFIX) }) resetTo(HOME)
            showMessage(getString(R.string.pair_paired_with, paired.first { it.deviceId == id }.name))
        } else if (selectedId !in ids) {
            select(paired.firstOrNull { it.isReachable }?.deviceId ?: paired.firstOrNull()?.deviceId)
        }
        DeskLinkApplication.getInstance().devices.values
            .firstOrNull { it.pairStatus == PairState.RequestedByPeer && it.isReachable }
            ?.let { asking -> if (stack.none { it == pairRoute(asking.deviceId) }) push(pairRoute(asking.deviceId)) }
    }

    @Composable
    private fun Root() {
        @Suppress("UNUSED_VARIABLE") val refresh = tick
        val paired = HomeModel.pairedDevices()
        LaunchedEffect(tick) { reconcile(paired) }
        val holder = rememberSaveableStateHolder()
        val onTabs = stack.last() == HOME && paired.isNotEmpty()
        BackHandler(enabled = stack.size > 1 || (onTabs && nav.canGoBack)) {
            if (stack.size > 1) onBack(stack.last()) else nav.back()
        }
        Box(Modifier.fillMaxSize().background(tokens.bg)) {
            AnimatedContent(
                targetState = stack.last(),
                transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                label = "screen",
            ) { route ->
                holder.SaveableStateProvider(route) { Screen(route, paired) }
            }
            // Above the tab bar when it is showing.
            SnackbarHost(
                snackbar,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = if (onTabs && !isWide()) 16.dp + TabBarHeight else 16.dp),
            ) { data ->
                Snackbar(data, containerColor = tokens.inverse, contentColor = tokens.onInverse, actionColor = tokens.accent)
            }
        }
    }

    /** Back from the pairing screen is Cancel: it answers the computer that asked. */
    private fun onBack(route: String) {
        if (route.startsWith(PAIR_PREFIX)) {
            val id = route.removePrefix(PAIR_PREFIX)
            val device = DeskLinkApplication.getInstance().getDevice(id)
            // Only a pairing still in progress is answered; never one just accepted or done,
            // which would unpair the computer the person has just paired.
            if (id != accepted && (device?.pairStatus == PairState.RequestedByPeer || device?.pairStatus == PairState.Requested)) {
                device.cancelPairing()
            }
        }
        // Settings without a computer keep their pages on the Settings tab's stack.
        if (route == SETTINGS && nav.pop(AppTab.SETTINGS)) return
        pop()
    }

    /** The computer "Codes match" was tapped for, until its pairing settles. */
    private var accepted: String? = null

    /** The last pairing failure per computer, for the pairing screen. */
    private val pairFailures = androidx.compose.runtime.mutableStateMapOf<String, String>()

    @Composable
    private fun Screen(route: String, paired: List<Device>) {
        // Before the first reconcile the selection may be stale: never flash Welcome for it.
        val device = paired.firstOrNull { it.deviceId == selectedId } ?: paired.firstOrNull()
        when {
            route == HOME -> if (device == null) WelcomeScreen(onPair = { push(SCAN) }, onSettings = { push(SETTINGS) }) else TabsRoute(device, paired)
            route == SCAN -> ScanRoute()
            route.startsWith(PAIR_PREFIX) -> PairRoute(route.removePrefix(PAIR_PREFIX))
            route == SETTINGS -> SettingsContent(device)
            route.startsWith(VIEWER_PREFIX) -> ViewerRoute(route.removePrefix(VIEWER_PREFIX))
            else -> LaunchedEffect(route) { pop() }
        }
    }

    // ---- home ----------------------------------------------------------------------------------------------

    @Composable
    private fun TabsRoute(device: Device, paired: List<Device>) {
        // Re-read the devices on every refresh tick: they are not observable state.
        @Suppress("UNUSED_VARIABLE") val refresh = tick
        val id = device.deviceId
        val bucketViews by BucketEngine.views.collectAsState()
        val attention by TerminalAttentionBadge.pending.collectAsState()
        val running = TransferProgress.visibleFor(id)?.takeIf { !it.finished }
        val bucket = bucketViews[id]?.summary?.takeIf { !it.paused }
        val (filesBusy, filesProgress) = filesBadge(bucket != null, bucket?.fraction, running != null, running?.percent?.let { it / 100f })
        val settingsWaiting = androidx.compose.runtime.remember(tick / 5, id, device.name) { waitingPermissions(this, device.name) > 0 }
        val badges = TabBadges(
            link = device.toUiModel().connection,
            filesProgress = filesProgress,
            filesBusy = filesBusy,
            terminalsAttention = id in attention,
            settingsAttention = settingsWaiting,
        )
        FirstShareDialog(device)
        // Seen: opening the tab clears its dot.
        LaunchedEffect(nav.selected, id) { if (nav.selected == AppTab.TERMINALS) TerminalAttentionBadge.clear(id) }
        TabsShell(nav, badges, onQuickAction = { quickAction(it, device) }) { tab ->
            when (tab) {
                AppTab.HOME -> HomeTab(device, paired)
                AppTab.FILES -> FilesContent(device)
                AppTab.NOTES -> NotesContent(device)
                AppTab.TERMINALS -> TerminalsTab(
                    deviceId = id,
                    deskName = device.name,
                    onOpenSession = { sessionId -> startActivity(TerminalActivity.intent(this, id).putExtra(TerminalActivity.EXTRA_TERMINAL_ID, sessionId)) },
                    onOpenTerminals = { startActivity(TerminalActivity.intent(this, id)) },
                    onNew = { startActivity(TerminalActivity.intent(this, id, newTerminal = true)) },
                )
                AppTab.SETTINGS -> SettingsContent(device)
            }
        }
    }

    /**
     * After pairing, once per computer: what DeskLink may share with it. Both start off; nothing is shared that the person did
     * not tick. (A computer paired before this question keeps what it had and is never asked.)
     */
    @Composable
    private fun FirstShareDialog(device: Device) {
        val id = device.deviceId
        val needed by androidx.compose.runtime.produceState(false, id) {
            value = kotlinx.coroutines.withContext(Dispatchers.IO) { device.isPaired && org.desklink.mobile.helpers.FirstShare.needed(this@MainActivity, id) }
        }
        var gone by androidx.compose.runtime.remember(id) { mutableStateOf(false) }
        if (!needed || gone) return
        var clipboard by androidx.compose.runtime.remember { mutableStateOf(false) }
        var notifications by androidx.compose.runtime.remember { mutableStateOf(false) }
        fun finish(apply: Boolean) {
            gone = true
            CoroutineScope(Dispatchers.IO + org.desklink.mobile.helpers.Guarded.handler("background")).launch { org.desklink.mobile.helpers.FirstShare.markDone(this@MainActivity, id) }
            if (apply && clipboard) setPluginEnabled(device, HomeModel.clipboardKey, true)
            if (apply && notifications) setPluginEnabled(device, Plugin.getPluginKey(NotificationsPlugin::class.java), true)
        }
        AlertDialog(
            onDismissRequest = { finish(apply = false) },
            title = { Text(stringResource(R.string.first_share_title, device.name)) },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text(stringResource(R.string.first_share_body, device.name))
                    androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
                    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().clickable { clipboard = !clipboard }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = clipboard, onCheckedChange = null)
                        Text(stringResource(R.string.first_share_clipboard), Modifier.padding(start = 8.dp))
                    }
                    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().clickable { notifications = !notifications }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = notifications, onCheckedChange = null)
                        Text(stringResource(R.string.first_share_notifications, device.name), Modifier.padding(start = 8.dp))
                    }
                    Text(stringResource(R.string.first_share_later), Modifier.padding(top = 8.dp), style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { finish(apply = true) }) { Text(stringResource(R.string.first_share_done)) } },
        )
    }

    /** A long-press on a tab. */
    private fun quickAction(action: QuickAction, device: Device) {
        when (action) {
            QuickAction.SWITCH_COMPUTER -> { nav.select(AppTab.HOME); computersRequest++ }
            QuickAction.SEND_FILES -> sendFiles(device)
            QuickAction.NEW_NOTE -> { nav.select(AppTab.NOTES); notesEditing.value = org.desklink.mobile.plugins.notes.NotesActivity.NEW }
            QuickAction.NEW_TERMINAL -> startActivity(TerminalActivity.intent(this, device.deviceId, newTerminal = true))
        }
    }

    private fun sendFiles(device: Device) {
        device.loadedPlugins.values.flatMap { it.getUiButtons() }.firstOrNull { it.iconRes == R.drawable.share_plugin_action_24dp }
            ?.onClick?.invoke(this) ?: showMessage(getString(R.string.home_feature_off))
    }

    @Composable
    private fun HomeTab(device: Device, paired: List<Device>) {
        val stats = rememberConnectionStats(device.deviceId)
        // The history only changes when its revision does: not re-read and re-sorted every second.
        val history = TransferHistory.revision
        val historyCount = androidx.compose.runtime.remember(history, device.deviceId) { TransferHistory.store(this).forDevice(device.deviceId).size }
        val state = HomeModel.build(this, device, paired, historyCount).let { s ->
            if (s.ready && stats != null) s.copy(path = stats.path, rttMs = stats.roundTripTimeMs?.toInt()) else s
        }
        var sharingInfo by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
        // One actions object per computer: a new one every second would make Home recompose even when nothing changed.
        val actions = androidx.compose.runtime.remember(device, computersRequest) { homeActions(device) { sharingInfo = true } }
        HomeScreen(state, actions)
        if (sharingInfo) {
            AlertDialog(
                onDismissRequest = { sharingInfo = false },
                title = { Text(stringResource(R.string.home_share_screen_title, device.name)) },
                text = { Text(stringResource(R.string.home_share_screen_body, device.name)) },
                confirmButton = { TextButton(onClick = { sharingInfo = false }) { Text(stringResource(R.string.ok)) } },
            )
        }
    }

    @Composable
    private fun FilesContent(device: Device) {
        val revision = TransferHistory.revision
        val records = androidx.compose.runtime.remember(revision, device.deviceId) { TransferHistory.store(this).forDevice(device.deviceId) }
        val running = TransferProgress.visibleFor(device.deviceId)?.takeIf { !it.finished }
        // Read off the main thread: a folder listing is disk I/O.
        // Only this computer's files; the folder name is read here too (checking a chosen folder is disk I/O).
        val unsaved by androidx.compose.runtime.produceState(emptyList<org.desklink.mobile.plugins.share.UnpublishedFiles.Item>(), revision, device.deviceId) {
            value = kotlinx.coroutines.withContext(Dispatchers.IO) {
                org.desklink.mobile.plugins.share.UnpublishedFiles.list(this@MainActivity).filter { it.deviceId == device.deviceId }
            }
        }
        val sendAgain by androidx.compose.runtime.produceState(emptyList<org.desklink.mobile.plugins.share.SendAgain.Item>(), revision, device.deviceId) {
            value = kotlinx.coroutines.withContext(Dispatchers.IO) {
                org.desklink.mobile.plugins.share.SendAgain.list(this@MainActivity).filter { it.deviceId == device.deviceId }
            }
        }
        val savedIn by androidx.compose.runtime.produceState(getString(R.string.files_saved_in_downloads), revision) {
            value = kotlinx.coroutines.withContext(Dispatchers.IO) { receivedFolderName() }
        }
        FilesTab(
            bucket = bucketUi,
            deviceId = device.deviceId,
            deskName = device.name,
            live = liveTransfer(running),
            records = records,
            revision = revision,
            actions = androidx.compose.runtime.remember(device) { transferActions(device) },
            savedIn = savedIn,
            unsaved = unsaved,
            sendAgain = sendAgain,
            onSendAgain = { id ->
                lifecycleScope.launch(Dispatchers.IO) { org.desklink.mobile.plugins.share.SendAgain.dismiss(this@MainActivity, id) }
                sendFiles(device) // the picker
            },
            onDismissSendAgain = { id -> lifecycleScope.launch(Dispatchers.IO) { org.desklink.mobile.plugins.share.SendAgain.dismiss(this@MainActivity, id) } },
            retrying = retryingUnsaved,
            onRetryUnsaved = ::retryUnsaved,
            onDiscardUnsaved = { id -> lifecycleScope.launch(Dispatchers.IO) { org.desklink.mobile.plugins.share.UnpublishedFiles.discard(this@MainActivity, id) } },
        )
    }

    /** "the Downloads folder", or the folder chosen in Settings by its name; the Downloads folder when the chosen one is not there. */
    private fun receivedFolderName(): String {
        if (!ShareDestination.isCustomDestinationEnabled(this) || !ShareDestination.customFolderUsable(this)) return getString(R.string.files_saved_in_downloads)
        val chosen = PreferenceManager.getDefaultSharedPreferences(this).getString("share_destination_folder_uri", null)
        return chosen?.let { android.net.Uri.parse(it).lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/') }?.takeIf { it.isNotBlank() }
            ?: getString(R.string.files_saved_in_downloads)
    }

    /** The files whose "Try again" is running: their card is disabled until it has an answer. */
    private val retryingUnsaved = androidx.compose.runtime.mutableStateListOf<String>()

    private fun retryUnsaved(id: String) {
        if (id in retryingUnsaved) return
        retryingUnsaved.add(id)
        lifecycleScope.launch(Dispatchers.IO) {
            val result = try {
                org.desklink.mobile.plugins.share.UnpublishedFiles.retry(this@MainActivity, id, org.desklink.mobile.plugins.share.DeviceStorageDestination(this@MainActivity))
            } finally {
                runOnUiThread { retryingUnsaved.remove(id) }
            }
            val text = when (result) {
                is org.desklink.mobile.plugins.share.UnpublishedFiles.Retry.Done ->
                    getString(R.string.files_unsaved_done, result.published.name) +
                        if (result.published.fellBackToDownloads) " " + getString(R.string.files_fell_back) else ""
                is org.desklink.mobile.plugins.share.UnpublishedFiles.Retry.Failed ->
                    result.freeUpBytes?.let { result.message + " " + getString(R.string.files_storage_free_up, org.desklink.mobile.helpers.Sizes.iec(it)) } ?: result.message
                else -> null
            }
            if (text != null) runOnUiThread { showMessage(text) }
        }
    }

    @Composable
    private fun NotesContent(device: Device) {
        androidx.activity.compose.BackHandler(enabled = notesEditing.value != null) { notesEditing.value = null }
        NotesTab(
            notes = notesUi,
            host = this,
            deskName = device.name,
            clipboardOn = device.isPluginEnabled(HomeModel.clipboardKey),
            onClipboard = { on -> setPluginEnabled(device, HomeModel.clipboardKey, on) },
            onSendClipboard = { sendClipboard(device) },
            onMessage = ::showMessage,
        )
    }

    private fun sendClipboard(device: Device) {
        val clipboard = device.getPlugin(org.desklink.mobile.plugins.clipboard.ClipboardPlugin::class.java)
        when {
            clipboard == null -> showMessage(getString(R.string.home_feature_off))
            clipboard.sendNow() -> showMessage(getString(R.string.home_clipboard_sent, device.name))
            else -> showMessage(getString(R.string.clipboard_nothing_sent))
        }
    }

    private fun homeActions(device: Device, onShareInfo: () -> Unit): HomeActions {
        val id = device.deviceId
        fun menu(name: Int) = device.loadedPlugins.values.flatMap { it.getUiMenuEntries() }.firstOrNull { it.name == getString(name) }
        fun run(entry: Plugin.PluginUiMenuEntry?, done: Int? = null) {
            if (entry == null) showMessage(getString(R.string.home_feature_off)) else {
                entry.onClick(this)
                done?.let { showMessage(getString(it, device.name)) }
            }
        }
        return HomeActions(
            onSelectComputer = ::select,
            onPairAnother = { push(SCAN) },
            onRetry = {
                BackgroundService.ForceRefreshConnections(this)
                showMessage(getString(R.string.home_looking_for, device.name))
            },
            onEndLive = { SessionLiveNotification.endAll(this, id) },
            // Each goes to the desk's current player through the plugin of *now* (never one held
            // from before a reconnect), exactly one request per tap; tick shows the result at once.
            onPlayPause = { media(device) { it.togglePlayPause() } },
            onNext = { media(device) { it.next() } },
            onPrevious = { media(device) { it.previous() } },
            onSeek = { ms -> media(device) { it.seekTo(ms) } },
            onVolume = { level -> media(device) { it.setVolume(level) } },
            onSelectPlayer = { name -> media(device) { it.selectedPlayer = name } },
            onDeskScreen = {
                if (device.getPlugin(org.desklink.mobile.plugins.screen.ScreenControlPlugin::class.java) == null) showMessage(getString(R.string.home_feature_off))
                else startActivity(Intent(this, MousePadActivity::class.java).putExtra("deviceId", id))
            },
            onSendFiles = { sendFiles(device) },
            onSendLink = { url ->
                val share = device.getPlugin(SharePlugin::class.java)
                if (share == null) showMessage(getString(R.string.home_feature_off)) else {
                    share.share(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, url))
                    showMessage(getString(R.string.home_link_sent, device.name))
                }
            },
            onLock = { run(menu(R.string.lock_remote_device), R.string.home_locked) },
            onShareScreen = {
                when {
                    PhoneScreenCaptureService.sharingDeviceId == id -> SessionLiveNotification.endAll(this, id)
                    // Offered from here; the desk opens its Phone Screen window and asks back.
                    DeskLinkApplication.getInstance().offerPhoneScreen(id) -> showMessage(getString(R.string.home_share_offered, device.name))
                    else -> onShareInfo()
                }
            },
            onPing = { run(menu(R.string.send_ping), R.string.home_pinged) },
            onRing = { run(menu(R.string.ring), R.string.home_rang) },
            onOpenFiles = { nav.select(AppTab.FILES) },
            onUnlockDesk = {
                val unlock = device.getPlugin(org.desklink.mobile.plugins.deskunlock.DeskUnlockPlugin::class.java)
                when {
                    unlock == null -> showMessage(getString(R.string.home_feature_off))
                    unlock.ask() -> showMessage(getString(R.string.home_unlock_asking, device.name))
                    else -> showMessage(getString(R.string.settings_waiting_for, device.name))
                }
            },
            onNotes = { nav.select(AppTab.NOTES) },
            onNote = { noteId -> nav.select(AppTab.NOTES); notesEditing.value = noteId },
            onNewNote = { nav.select(AppTab.NOTES); notesEditing.value = org.desklink.mobile.plugins.notes.NotesActivity.NEW },
            onBucket = { nav.select(AppTab.FILES) },
            onUnavailable = ::showMessage,
            openComputersRequest = computersRequest,
        )
    }

    private fun media(device: Device, action: (org.desklink.mobile.plugins.mpris.MprisPlugin) -> Unit) {
        val plugin = device.getPlugin(org.desklink.mobile.plugins.mpris.MprisPlugin::class.java)
        if (plugin == null) showMessage(getString(R.string.home_feature_off)) else action(plugin)
        tick++
    }

    private fun requestMissing(permissions: List<String>) {
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            org.desklink.mobile.helpers.PermissionRecovery.requestOrExplain(this, missing.toTypedArray()) { asking ->
                ActivityCompat.requestPermissions(this, asking, RESULT_CALL_PERMISSIONS)
            }
        }
    }

    /** Written at once, applied off the main thread: reloading plugins is blocking. */
    private fun setPluginEnabled(device: Device, key: String, on: Boolean) {
        CoroutineScope(Dispatchers.IO + org.desklink.mobile.helpers.Guarded.handler("background")).launch { device.setPluginEnabled(key, on) }
        tick++
    }

    // ---- pairing ---------------------------------------------------------------------------------------------

    @Composable
    private fun ScanRoute() {
        // Re-read the devices on every refresh tick: they are not observable state.
        @Suppress("UNUSED_VARIABLE") val refresh = tick
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        var camera by androidx.compose.runtime.remember { mutableStateOf(granted) }
        val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            camera = ok
            if (!ok) showMessage(getString(R.string.scan_camera_denied))
        }
        LaunchedEffect(Unit) {
            // Look again now, so computers on this network fill in while the camera starts.
            BackgroundService.ForceRefreshConnections(this@MainActivity)
            if (!granted) request.launch(Manifest.permission.CAMERA)
        }
        val nearby = DeskLinkApplication.getInstance().devices.values
            .filter { it.isReachable && !it.isPaired }
            .sortedBy { it.name.lowercase() }
            .map { NearbyComputer(it.deviceId, it.name) }
        ScanScreen(
            nearby = nearby,
            camera = camera || granted,
            attempt = scanAttempt,
            onAllowCamera = {
                        org.desklink.mobile.helpers.PermissionRecovery.requestOrExplain(this, arrayOf(Manifest.permission.CAMERA)) { request.launch(Manifest.permission.CAMERA) }
                    },
            onCode = ::onQrCode,
            onPairNearby = { id ->
                org.desklink.mobile.backends.lan.UnpairedLimits.shared.reserve(id)
                DeskLinkApplication.getInstance().getDevice(id)?.requestPairing()
                push(pairRoute(id))
            },
            onClose = ::pop,
            onCameraError = { showMessage(getString(R.string.scan_camera_error)) },
        )
        QrProgress()
        qrConflict?.let { (code, name) ->
            AlertDialog(
                onDismissRequest = { qrConflict = null; pop() },
                title = { Text(stringResource(R.string.qr_replace_pairing_title, name)) },
                text = { Text(stringResource(R.string.qr_replace_pairing_message)) },
                confirmButton = { TextButton(onClick = { qrConflict = null; startQrPairing(code, replaceExisting = true) }) { Text(stringResource(R.string.qr_replace_pairing_confirm)) } },
                dismissButton = { TextButton(onClick = { qrConflict = null; pop() }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }

    /** Bumped to restart the camera after a code that was not a pairing code. */
    private var scanAttempt by mutableIntStateOf(0)

    /** A scanned code that would replace an existing pairing, waiting for the person's answer. */
    private var qrConflict by mutableStateOf<Pair<QrPairing.Code, String>?>(null)

    /** A scanned code being paired: which desk, and how far (null stage: scanning). */
    private var qrProgress by mutableStateOf<Pair<String, QrPairing.Stage>?>(null)

    private fun startQrPairing(code: QrPairing.Code, replaceExisting: Boolean) {
        qrProgress = code.desktopId to QrPairing.Stage.CONNECTING
        QrPairing.pair(
            DeskLinkApplication.getInstance(),
            code,
            replaceExisting,
            onProgress = { stage -> runOnUiThread { qrProgress = code.desktopId to stage } },
        ) { error ->
            if (error != null) runOnUiThread {
                qrProgress = null
                showMessage(error)
                scanAttempt++
            }
        }
    }

    /** Connecting → verifying → paired, over the scan screen; paired goes to the desk's Home. */
    @Composable
    private fun QrProgress() {
        val (id, stage) = qrProgress ?: return
        @Suppress("UNUSED_VARIABLE") val refresh = tick
        val device = DeskLinkApplication.getInstance().getDevice(id)
        val paired = device?.isPaired == true
        LaunchedEffect(paired) {
            if (paired) {
                kotlinx.coroutines.delay(700)
                qrProgress = null
                select(id)
                resetTo(HOME)
            }
        }
        // The desk refused the proof (the code rotated): say so at once, in words, and scan again.
        androidx.compose.runtime.DisposableEffect(id) {
            val callback = object : org.desklink.mobile.PairingHandler.PairingCallback {
                override fun incomingPairRequest() = Unit
                override fun pairingSuccessful() = Unit
                override fun pairingFailed(error: String) = runOnUiThread {
                    if (qrProgress?.first == id) {
                        qrProgress = null
                        showMessage(error)
                        scanAttempt++
                    }
                }
                override fun unpaired(device: Device) = Unit
            }
            device?.addPairingCallback(callback)
            onDispose { device?.removePairingCallback(callback) }
        }
        // The desk never answered the proof: say so instead of spinning.
        LaunchedEffect(stage) {
            if (stage == QrPairing.Stage.VERIFYING) {
                kotlinx.coroutines.delay(20_000)
                if (qrProgress?.first == id && DeskLinkApplication.getInstance().getDevice(id)?.isPaired != true) {
                    qrProgress = null
                    showMessage(getString(R.string.qr_progress_timeout))
                    scanAttempt++
                }
            }
        }
        val label = when {
            paired -> getString(R.string.qr_progress_paired, device?.name.orEmpty())
            stage == QrPairing.Stage.CONNECTING -> getString(R.string.qr_progress_connecting)
            else -> getString(R.string.qr_progress_verifying, device?.name.orEmpty())
        }
        AlertDialog(
            onDismissRequest = {},
            text = {
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                ) {
                    if (paired) {
                        androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.sym_check_circle), contentDescription = null, tint = tokens.mint)
                    } else {
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                    }
                    Text(label)
                }
            },
            confirmButton = {},
            // A way out whatever stage it hangs at (it used to be a dialog nothing could dismiss).
            dismissButton = {
                TextButton(onClick = { qrProgress = null; scanAttempt++ }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    /** QR pairing: one scan, nothing to compare (see QrPairing / pairing_qr.rs). */
    private fun onQrCode(text: String) {
        val code = QrPairing.parse(text)
        if (code == null) {
            showMessage(getString(R.string.qr_not_a_pairing_code))
            // Scan again: the camera paused on the code it read.
            scanAttempt++
            return
        }
        // A code can name any desktop's public id, so replacing an existing pairing is the
        // person's decision, never the scan's.
        val conflict = QrPairing.conflictingPairing(DeskLinkApplication.getInstance(), code)
        if (conflict == null) startQrPairing(code, replaceExisting = false) else qrConflict = code to conflict.name
    }

    @Composable
    private fun PairRoute(id: String) {
        // Re-read the devices on every refresh tick: they are not observable state.
        @Suppress("UNUSED_VARIABLE") val refresh = tick
        val device = DeskLinkApplication.getInstance().getDevice(id)
        // Pairing callbacks make the screen follow at once instead of on the next tick.
        androidx.compose.runtime.DisposableEffect(device) {
            val callback = object : org.desklink.mobile.PairingHandler.PairingCallback {
                override fun incomingPairRequest() = runOnUiThread { pairFailures.remove(id); tick++ }
                override fun pairingSuccessful() = runOnUiThread { tick++ }
                override fun pairingFailed(error: String) = runOnUiThread { pairFailures[id] = error; accepted = null; tick++ }
                override fun unpaired(device: Device) = runOnUiThread { accepted = null; tick++ }
            }
            device?.addPairingCallback(callback)
            onDispose { device?.removePairingCallback(callback) }
        }
        LaunchedEffect(device?.pairStatus) {
            device?.hidePairingNotification()
            if (device == null) pop()
            if (device?.isPaired == true) {
                accepted = null
                select(id)
                resetTo(HOME)
            }
        }
        device ?: return
        val stage = when (device.pairStatus) {
            PairState.RequestedByPeer -> PairStage.ASKED
            PairState.Requested -> PairStage.WAITING
            else -> PairStage.IDLE
        }
        PairCodeScreen(
            PairCodeState(device.name, device.verificationKey, stage, message = pairFailures[id].takeIf { stage == PairStage.IDLE }),
            onCancel = { onBack(pairRoute(id)) },
            onMatch = {
                accepted = id
                device.acceptPairing()
            },
            onRequest = {
                pairFailures.remove(id)
                device.requestPairing()
            },
        )
    }

    // ---- transfers ---------------------------------------------------------------------------------------------

    private fun liveTransfer(running: TransferProgress.Transfer?): LiveTransfer? {
        running ?: return null
        val speed = running.bytesPerSecond.takeIf { it > 0 }?.let { getString(R.string.transfers_speed, org.desklink.mobile.helpers.Sizes.iec(it)) }
        if (running.saving) return LiveTransfer(running.title, running.upload, null, getString(R.string.files_saving_label))
        return LiveTransfer(running.title, running.upload, running.percent?.let { it / 100f }, listOfNotNull(running.percent?.let { "$it%" }, speed).joinToString(" · "))
    }

    /** Read when used, so one object serves for as long as the computer does. */
    private fun transferActions(device: Device) = TransferActions(
        onBack = {},
        onSendFiles = { sendFiles(device) },
        onCancelLive = {
            TransferProgress.visibleFor(device.deviceId)?.takeIf { !it.finished }?.let { sendBroadcast(TransferProgress.cancelIntent(this, it)) }
            showMessage(getString(R.string.transfers_cancelled))
        },
        onOpen = { record ->
            when (previewKind(record.mime, record.name)) {
                PreviewKind.IMAGE, PreviewKind.VIDEO -> push(VIEWER_PREFIX + record.id)
                else -> openWith(record)
            }
        },
        onShare = { record ->
            val share = Intent(Intent.ACTION_SEND).setType(record.mime ?: "*/*")
                .putExtra(Intent.EXTRA_STREAM, shareableUri(record))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(share, record.name))
        },
        onOpenWith = ::openWith,
        onRemove = { record -> TransferHistory.remove(this, record.id) },
    )

    /** Another app opens it, read access granted through our FileProvider for local files. */
    private fun openWith(record: TransferRecord) {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(shareableUri(record), record.mime ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(view, record.name))
        } catch (_: android.content.ActivityNotFoundException) {
            showMessage(getString(R.string.transfers_no_app))
        }
    }

    private fun shareableUri(record: TransferRecord): android.net.Uri {
        val uri = android.net.Uri.parse(record.uri)
        return if (uri.scheme == "file") {
            androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", java.io.File(uri.path.orEmpty()))
        } else {
            uri
        }
    }

    @Composable
    private fun ViewerRoute(id: String) {
        val all = androidx.compose.runtime.remember(TransferHistory.revision) { TransferHistory.store(this).all() }
        val record = all.firstOrNull { it.id == id }
        if (record == null) {
            LaunchedEffect(id) { pop() }
            return
        }
        when (previewKind(record.mime, record.name)) {
            PreviewKind.VIDEO -> VideoViewer(record, onBack = ::pop)
            else -> ImageViewer(
                all.filter { it.deviceId == record.deviceId && previewKind(it.mime, it.name) == PreviewKind.IMAGE },
                id,
                onBack = ::pop,
            )
        }
    }

    // ---- settings ---------------------------------------------------------------------------------------------

    @Composable
    private fun SettingsContent(device: Device?) {
        // Re-read the devices on every refresh tick: they are not observable state.
        @Suppress("UNUSED_VARIABLE") val refresh = tick
        val desk = device?.let {
            DeskSettings(
                id = it.deviceId,
                name = it.name,
                clipboardOn = it.isPluginEnabled(HomeModel.clipboardKey),
                notificationsOn = it.isPluginEnabled(Plugin.getPluginKey(NotificationsPlugin::class.java)),
                key = SslHelper.getCertificateHash(it.certificate).filter(Char::isLetterOrDigit).uppercase().take(8).chunked(2).joinToString(":"),
                pluginPermissions = it.pluginsWithoutPermissions.values.filter { p -> it.isPluginEnabled(p.pluginKey) }.map { p -> p.displayName to { showPluginPermission(p, optional = false) } } +
                    it.pluginsWithoutOptionalPermissions.values.filter { p -> it.isPluginEnabled(p.pluginKey) }.map { p -> p.displayName to { showPluginPermission(p, optional = true) } },
                remote = it.getPlugin(DeskSettingsPlugin::class.java)?.settings,
                phoneMediaOn = it.isPluginEnabled(Plugin.getPluginKey(MprisReceiverPlugin::class.java)),
                pending = it.getPlugin(DeskSettingsPlugin::class.java)?.pending.orEmpty(),
                callsOn = it.isPluginEnabled(Plugin.getPluginKey(org.desklink.mobile.plugins.telephony.TelephonyPlugin::class.java)),
                showCaller = org.desklink.mobile.plugins.telephony.TelephonyPlugin.showCaller(this, it.deviceId),
                typeOnPhoneOn = org.desklink.mobile.plugins.mousereceiver.FocusedFieldTyping.allowed(this, it.deviceId),
                glideOn = org.desklink.mobile.plugins.inputdevicesreceiver.Glide.allowed(this, it.deviceId),
                deskSoundOn = org.desklink.mobile.plugins.audio.PhoneAudio.playDeskSound(this, it.deviceId),
                deskSoundVolume = org.desklink.mobile.plugins.audio.PhoneAudio.volume(this, it.deviceId),
                deskMayPlay = org.desklink.mobile.plugins.audio.PhoneAudio.deskMayPlay(this, it.deviceId),
                micConsent = org.desklink.mobile.plugins.audio.PhoneAudio.consented(this, it.deviceId),
                contactsOn = it.isPluginEnabled(Plugin.getPluginKey(org.desklink.mobile.plugins.contacts.ContactsPlugin::class.java)),
                contactsReadable = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED,
                accessibilityOn = org.desklink.mobile.plugins.mousereceiver.MouseReceiverService.instance != null,
                deskNotificationsOn = it.isPluginEnabled(Plugin.getPluginKey(org.desklink.mobile.plugins.receivenotifications.ReceiveNotificationsPlugin::class.java)),
                ready = it.hasActiveWebRtcFeatureTransport,
            )
        }
        fun deskSet(key: String, on: Boolean) = device?.getPlugin(DeskSettingsPlugin::class.java)?.set(key, on)
        SettingsTab(
            desk = desk,
            route = nav.top(AppTab.SETTINGS),
            onOpen = { nav.push(AppTab.SETTINGS, it) },
            onBack = { nav.pop(AppTab.SETTINGS) },
            waitingPermissions = waitingPermissions(this, device?.name ?: getString(R.string.settings_your_computer)),
            about = { AboutRoute { nav.pop(AppTab.SETTINGS) } },
            actions = SettingsActions(
                // One writer per setting: these are this phone's plugins, the desk's own
                // switches go through onDeskSetting only.
                onPhoneClipboard = { on -> device?.let { setPluginEnabled(it, HomeModel.clipboardKey, on) } },
                onPhoneNotifications = { on -> device?.let { setPluginEnabled(it, Plugin.getPluginKey(NotificationsPlugin::class.java), on) } },
                onDeskSetting = { key, on -> deskSet(key, on) },
                onPhoneMedia = { on ->
                    device?.let { setPluginEnabled(it, Plugin.getPluginKey(MprisReceiverPlugin::class.java), on) }
                    // Notification access is asked for only now, when the person turns this on.
                    if (on && !org.desklink.mobile.plugins.notifications.NotificationReceiver.hasReadNotificationsPermission(this)) {
                        runCatching { startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                    }
                },
                onCalls = { on ->
                    device?.let { setPluginEnabled(it, Plugin.getPluginKey(org.desklink.mobile.plugins.telephony.TelephonyPlugin::class.java), on) }
                    // Asked only now, when the person turns calls on; the rows under Permissions stay until granted.
                    if (on) requestMissing(buildList {
                        add(Manifest.permission.READ_PHONE_STATE)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) add(Manifest.permission.ANSWER_PHONE_CALLS)
                        add(Manifest.permission.CALL_PHONE)
                    })
                },
                onShowCaller = { on ->
                    device?.let { d ->
                        org.desklink.mobile.plugins.telephony.TelephonyPlugin.setShowCaller(this, d.deviceId, on)
                        // Names from contacts are optional: without it the dialer's own title is used.
                        if (on) requestMissing(listOf(Manifest.permission.READ_CONTACTS))
                        d.launchBackgroundReloadPluginsFromSettings()
                    }
                    tick++
                },
                onDeskNotifications = { on ->
                    device?.let { setPluginEnabled(it, Plugin.getPluginKey(org.desklink.mobile.plugins.receivenotifications.ReceiveNotificationsPlugin::class.java), on) }
                    if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) requestMissing(listOf(Manifest.permission.POST_NOTIFICATIONS))
                },
                onDeskSound = { on -> device?.let { setDeskSound(it.deviceId, on) } },
                onMicConsent = { on ->
                    device?.let { d ->
                        if (!on) org.desklink.mobile.plugins.audio.PhoneAudio.forgetConsent(this, d.deviceId)
                        tick++
                    }
                },
                onDeskSoundVolume = { percent ->
                    device?.let {
                        org.desklink.mobile.plugins.audio.PhoneAudio.setVolume(this, it.deviceId, percent)
                        DeskLinkApplication.getInstance().webRtcCoordinator(it.deviceId)?.setDeskSoundVolume(percent)
                    }
                },
                onDeskMayPlay = { on ->
                    device?.let {
                        org.desklink.mobile.plugins.audio.PhoneAudio.setDeskMayPlay(this, it.deviceId, on)
                        // Turned off while it plays: it stops now.
                        if (!on && org.desklink.mobile.plugins.audio.PhoneAudio.hearingFrom == it.deviceId && !org.desklink.mobile.plugins.audio.PhoneAudio.playDeskSound(this, it.deviceId)) {
                            DeskLinkApplication.getInstance().webRtcCoordinator(it.deviceId)?.stopDeskSound()
                        }
                    }
                    tick++
                },
                onContacts = { on -> device?.let { setContactsShared(it, on) } },
                onGlide = { on ->
                    device?.let { d ->
                        org.desklink.mobile.plugins.inputdevicesreceiver.Glide.setAllowed(this, d.deviceId, on)
                        d.getPlugin(org.desklink.mobile.plugins.inputdevicesreceiver.InputDevicesReceiverPlugin::class.java)?.reportAllowed()
                        // Turned off mid-glide: the desk gets its pointer back now.
                        if (!on) d.getPlugin(org.desklink.mobile.plugins.inputdevicesreceiver.InputDevicesReceiverPlugin::class.java)
                            ?.takeIf { org.desklink.mobile.plugins.inputdevicesreceiver.Glide.activeDesk == d.deviceId }?.stop(release = true)
                    }
                    tick++
                },
                onTypeOnPhone = { on ->
                    device?.let { org.desklink.mobile.plugins.mousereceiver.FocusedFieldTyping.setAllowed(this, it.deviceId, on) }
                    // The accessibility service is asked for only now, when the person turns this on.
                    if (on && org.desklink.mobile.plugins.mousereceiver.MouseReceiverService.instance == null) {
                        org.desklink.mobile.plugins.mousereceiver.AccessibilitySetupActivity.open(this)
                    }
                    tick++
                },
                onPairAnother = { push(SCAN) },
                onSendClipboard = { device?.let(::sendClipboard) },
                onChooseNotificationApps = { device?.getPlugin(NotificationsPlugin::class.java)?.openAppFilter(this) },
                onAddTiles = { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) offerTiles() },
                onOpenBucket = { nav.select(AppTab.FILES); if (stack.last() != HOME) resetTo(HOME) },
                onUnpair = {
                    device?.unpair()
                    select(null)
                    resetTo(HOME)
                    nav.reset(AppTab.SETTINGS)
                    nav.select(AppTab.HOME)
                },
                onEncryptionInfo = { device?.let(::showEncryptionInfo) },
                onRecreate = ::recreate,
                onMessage = ::showMessage,
            ),
        )
    }

    /**
     * F5: "Share contacts with <desk>". Turning it on says what is shared first, then Android asks for
     * the permission; turning it off makes the plugin tell the desk to delete its copy.
     */
    private fun setContactsShared(device: Device, on: Boolean) {
        val key = Plugin.getPluginKey(org.desklink.mobile.plugins.contacts.ContactsPlugin::class.java)
        if (!on) return setPluginEnabled(device, key, false)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.contacts_share_title, device.name))
            .setMessage(getString(R.string.contacts_share_text, device.name))
            .setPositiveButton(R.string.contacts_share_confirm) { _, _ ->
                setPluginEnabled(device, key, true)
                requestMissing(listOf(Manifest.permission.READ_CONTACTS))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** F1: "Play <desk>'s sound here" — remembered, and asked for (or stopped) on the live link now. */
    private fun setDeskSound(deviceId: String, on: Boolean) {
        org.desklink.mobile.plugins.audio.PhoneAudio.setPlayDeskSound(this, deviceId, on)
        DeskLinkApplication.getInstance().webRtcCoordinator(deviceId)?.let { if (on) it.requestDeskSound() else it.stopDeskSound() }
        tick++
    }

    private fun showEncryptionInfo(device: Device) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.device_encryption)
            .setPositiveButton(R.string.ok) { dialog, _ -> dialog.dismiss() }
            .setMessage(
                "${getString(R.string.my_device_fingerprint)}\n${SslHelper.getCertificateHash(SslHelper.certificate)}\n\n" +
                    "${getString(R.string.remote_device_fingerprint)}\n${SslHelper.getCertificateHash(device.certificate)}\n\n" +
                    "${getString(R.string.protocol_version)} ${device.protocolVersion}",
            )
            .show()
    }

    /** Picks one of DeskLink's Quick Settings tiles and asks the system to add it (API 33+). */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun offerTiles() {
        val tiles = listOf(
            Triple(org.desklink.mobile.ui.surfaces.LockDeskTileService::class.java, R.string.tile_lock_desk, R.drawable.sym_lock),
            Triple(org.desklink.mobile.ui.surfaces.KeepAwakeTileService::class.java, R.string.tile_keep_awake, R.drawable.sym_coffee),
            Triple(org.desklink.mobile.ui.surfaces.RingDeskTileService::class.java, R.string.tile_ring_desk, R.drawable.sym_vibration),
            Triple(org.desklink.mobile.plugins.clipboard.ClipboardTileService::class.java, R.string.send_clipboard, R.drawable.sym_content_paste),
        )
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.surfaces_add_tiles)
            .setItems(tiles.map { getString(it.second) }.toTypedArray()) { _, which ->
                val (cls, label, icon) = tiles[which]
                getSystemService(android.app.StatusBarManager::class.java)?.requestAddTileService(
                    android.content.ComponentName(this, cls),
                    getString(label),
                    android.graphics.drawable.Icon.createWithResource(this, icon),
                    mainExecutor,
                ) { result ->
                    if (result == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED) showMessage(getString(R.string.surfaces_tile_added))
                }
            }
            .show()
    }

    @Composable
    private fun AboutRoute(onBack: () -> Unit) {
        val about = androidx.compose.runtime.remember { getApplicationAboutData(this) }
        fun open(url: String?) = url?.let { runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(it))) } }
        Column(Modifier.fillMaxSize().background(tokens.bg).statusBarsPadding()) {
            ScreenHeader(onBack)
            Box(Modifier.weight(1f)) {
                AboutScreen(
                    aboutData = about,
                    onEasterEggTriggered = { startActivity(Intent(this@MainActivity, EasterEggActivity::class.java)) },
                    onReportBugClicked = { open(about.bugURL) },
                    onDonateClicked = { open(about.donateURL) },
                    onSourceCodeClicked = { open(about.sourceCodeURL) },
                    onLicensesClicked = { startActivity(Intent(this@MainActivity, LicensesActivity::class.java)) },
                )
            }
        }
    }

    // ---- results -------------------------------------------------------------------------------------------------

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when {
            requestCode == RESULT_NEEDS_RELOAD -> CoroutineScope(Dispatchers.IO + org.desklink.mobile.helpers.Guarded.handler("background")).launch {
                DeskLinkApplication.getInstance().devices.values.forEach(Device::reloadPluginsFromSettings)
            }
            requestCode == STORAGE_LOCATION_CONFIGURED && resultCode == RESULT_OK && data != null ->
                ShareDestination.saveStorageLocationPreference(this, data.data)
            else -> @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun isPermissionGranted(permissions: Array<String>, grantResults: IntArray, permission: String): Boolean {
        val index = ArrayUtils.indexOf(permissions, permission)
        return index != ArrayUtils.INDEX_NOT_FOUND && grantResults[index] == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!ArrayUtils.contains(grantResults, PackageManager.PERMISSION_GRANTED)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isPermissionGranted(permissions, grantResults, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            // Android 10+ needs a writable folder chosen by hand for received files.
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), STORAGE_LOCATION_CONFIGURED)
        }
        // A new permission: reload the plugins that waited for it.
        CoroutineScope(Dispatchers.IO + org.desklink.mobile.helpers.Guarded.handler("background")).launch {
            DeskLinkApplication.getInstance().devices.values.forEach(Device::reloadPluginsFromSettings)
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (DeviceHelper.KEY_DEVICE_NAME_PREFERENCE == key) {
            BackgroundService.ForceRefreshConnections(this) // Re-send our identity packet
        }
    }

    companion object {
        /** Launcher shortcuts (res/xml/shortcuts.xml). */
        const val ACTION_NEW_NOTE = "org.desklink.mobile.action.NEW_NOTE"
        const val ACTION_ADD_TO_BUCKET = "org.desklink.mobile.action.ADD_TO_BUCKET"
        const val EXTRA_DEVICE_ID = "deviceId"
        const val PAIR_REQUEST_STATUS = "pair_req_status"
        const val PAIRING_PENDING = "pending"
        const val RESULT_NEEDS_RELOAD = RESULT_FIRST_USER
        const val RESULT_NOTIFICATIONS_ENABLED = RESULT_FIRST_USER + 1
        const val RESULT_CALL_PERMISSIONS = RESULT_FIRST_USER + 2
        const val FLAG_FORCE_OVERVIEW = "forceOverview"
        const val EXTRA_SETTINGS_PAGE = "settingsPage"

        private const val HOME = "home"
        private const val SCAN = "scan"
        private const val SETTINGS = "settings"
        private const val VIEWER_PREFIX = "viewer/"
        private const val PAIR_PREFIX = "pair/"
        private fun pairRoute(id: String) = PAIR_PREFIX + id
    }
}
