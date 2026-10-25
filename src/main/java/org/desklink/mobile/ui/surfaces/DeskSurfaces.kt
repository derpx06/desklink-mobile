/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.surfaces

import android.content.ComponentName
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.core.content.edit
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.Device
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.R
import org.desklink.mobile.plugins.desksettings.DeskSettingsPlugin
import org.desklink.mobile.plugins.mpris.MediaControl
import org.desklink.mobile.plugins.mpris.MprisPlugin
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol

/** How the desk is reached, as the tiles and the widget show it. */
enum class DeskLink { CONNECTED, CONNECTING, OFFLINE }

/**
 * One desk as the Quick Settings tiles and the home-screen widget show it, built from the same
 * caches as Home (Phase 1 rules: desk-owned values only while the feature link is ready).
 */
data class DeskSnapshot(
    val id: String,
    val name: String,
    val link: DeskLink,
    /** The feature link is up: desk-owned values are current and actions go out now. */
    val ready: Boolean,
    /** The desk's own keepAwake (shown as asked while pending); null before its first report. */
    val keepAwake: Boolean?,
    val keepAwakePending: Boolean,
    /** The computer lets this phone keep it awake (W10); false until it says so. */
    val keepAwakeAllowed: Boolean = false,
    val terminals: Int,
    /** A terminal is open here right now (the coral dot). */
    val terminalAttached: Boolean,
    /** "Finished" alerts not yet seen in Terminals. */
    val unseenFinished: Int,
    val mediaTitle: String?,
    val mediaPlaying: Boolean,
    /** When the desk was last seen ready (wall clock), for "last seen" while it is not. */
    val lastSeen: Long?,
)

/** A tile's state and second line. */
data class TileUi(val state: Int, val subtitle: String)

enum class TileKind { LOCK, CLIPBOARD, KEEP_AWAKE, RING }

/** The pure parts, unit-tested. */
object DeskSurfaceRules {
    /**
     * Lock, clipboard and ring are plain actions: available only while the desk is ready (they
     * would otherwise queue silently behind a tile that looks like it worked). Keep awake shows the
     * desk's own value (its single writer) and is unavailable until the desk has reported it.
     */
    fun tile(kind: TileKind, desk: DeskSnapshot?, notConnected: String, saving: String, turnOnAtDesk: String = ""): TileUi {
        if (desk == null) return TileUi(Tile.STATE_UNAVAILABLE, notConnected)
        if (!desk.ready) return TileUi(Tile.STATE_UNAVAILABLE, notConnected)
        return when (kind) {
            TileKind.KEEP_AWAKE -> when (val on = desk.keepAwake) {
                null -> TileUi(Tile.STATE_UNAVAILABLE, desk.name)
                // The computer has not allowed it: shown as it really is, disabled, with where to turn it on.
                else -> if (!desk.keepAwakeAllowed) TileUi(Tile.STATE_UNAVAILABLE, turnOnAtDesk.ifEmpty { desk.name }) else TileUi(if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE, if (desk.keepAwakePending) saving else desk.name)
            }
            else -> TileUi(Tile.STATE_INACTIVE, desk.name)
        }
    }

    fun link(ready: Boolean, reachable: Boolean): DeskLink = when {
        ready -> DeskLink.CONNECTED
        reachable -> DeskLink.CONNECTING
        else -> DeskLink.OFFLINE
    }

    /** The widget's terminal line: count, then the badge for unseen finished alerts. */
    fun terminalBadge(unseenFinished: Int): String? = when {
        unseenFinished <= 0 -> null
        unseenFinished > 9 -> "9+"
        else -> unseenFinished.toString()
    }

    /** "Last seen 14:05" for today, else with the date; null when never seen. */
    fun lastSeenLabel(lastSeen: Long?, format: (Long) -> String): String? = lastSeen?.takeIf { it > 0 }?.let(format)

    /** The next refresh: at once if the last one was a second ago or more, else at that second. */
    fun nextRefreshDelay(lastRefreshAt: Long, now: Long, minIntervalMs: Long = 1_000): Long =
        (lastRefreshAt + minIntervalMs - now).coerceAtLeast(0)
}

/**
 * Tiles and widget, kept current from the app's caches: [refresh] coalesces every change to at
 * most one redraw a second, and nothing polls (the widget's updatePeriodMillis is 0).
 */
object DeskSurfaces {
    private const val PREFS = "desk_surfaces"
    private const val SELECTION_PREFS = "stored_menu_selection"
    private const val SELECTED = "selected_device"

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var lastRefreshAt = -10_000L
    private var pending = false

    /** The desk the phone shows: the one picked in the app if still paired, else the first paired. */
    fun currentDesk(context: Context): Device? {
        val app = DeskLinkApplication.getInstance()
        val paired = app.devices.values.filter { it.isPaired }
        val selected = context.getSharedPreferences(SELECTION_PREFS, Context.MODE_PRIVATE).getString(SELECTED, null)
        return paired.firstOrNull { it.deviceId == selected } ?: paired.minByOrNull { it.name.lowercase() }
    }

    fun snapshot(context: Context, device: Device): DeskSnapshot {
        val ready = device.hasActiveWebRtcFeatureTransport
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (ready) prefs.edit { putLong(org.desklink.mobile.helpers.PerPeerState.seenKey(device.deviceId), System.currentTimeMillis()) }
        val settingsPlugin = device.getPlugin(DeskSettingsPlugin::class.java)
        val settings = settingsPlugin?.settings?.takeIf { ready }
        val pending = settingsPlugin?.pending.orEmpty()
        val sessions = DeskLinkApplication.getInstance().terminalClient(device.deviceId)?.state?.value?.sessions.orEmpty().filter { !it.gone }
        val mpris = device.getPlugin(MprisPlugin::class.java)?.takeIf { ready }
        val player = mpris?.currentPlayer()
        val now = SystemClock.elapsedRealtime()
        return DeskSnapshot(
            id = device.deviceId,
            name = device.name,
            link = DeskSurfaceRules.link(ready, device.isReachable),
            ready = ready,
            keepAwake = settings?.let { DeskSettingsPlugin.shown(DeskSettingsPlugin.KEEP_AWAKE, it.keepAwake, pending) },
            keepAwakePending = DeskSettingsPlugin.KEEP_AWAKE in pending,
            keepAwakeAllowed = DeskSettingsPlugin.keepAwakeUsable(settings),
            terminals = sessions.size,
            terminalAttached = sessions.any { it.attached },
            unseenFinished = prefs.getInt(org.desklink.mobile.helpers.PerPeerState.finishedKey(device.deviceId), 0),
            mediaTitle = player?.title?.takeIf(String::isNotBlank),
            mediaPlaying = player != null && MediaControl.shownPlaying(player.isPlaying, mpris.pendingPlaying, mpris.pendingSince, now),
            lastSeen = prefs.getLong(org.desklink.mobile.helpers.PerPeerState.seenKey(device.deviceId), 0).takeIf { it > 0 },
        )
    }

    /** A "finished" alert was posted: the widget badges it until Terminals is opened. */
    fun noteFinished(context: Context, deviceId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit { putInt(org.desklink.mobile.helpers.PerPeerState.finishedKey(deviceId), prefs.getInt(org.desklink.mobile.helpers.PerPeerState.finishedKey(deviceId), 0) + 1) }
        refresh(context)
    }

    fun clearFinished(context: Context, deviceId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(org.desklink.mobile.helpers.PerPeerState.finishedKey(deviceId), 0) == 0) return
        prefs.edit { remove(org.desklink.mobile.helpers.PerPeerState.finishedKey(deviceId)) }
        refresh(context)
    }

    /** Something a tile or the widget shows changed: redraw, at most once a second. */
    fun refresh(context: Context) {
        // Never in the way of what changed: no surfaces (or no main looper, in tests) is fine.
        runCatching { scheduleRefresh(context.applicationContext) }
    }

    private fun scheduleRefresh(app: Context) {
        handler.post {
            if (pending) return@post
            pending = true
            handler.postDelayed({
                pending = false
                lastRefreshAt = SystemClock.elapsedRealtime()
                runCatching { DeskWidgetProvider.updateAll(app) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    listOf(LockDeskTileService::class.java, KeepAwakeTileService::class.java, RingDeskTileService::class.java,
                        org.desklink.mobile.plugins.clipboard.ClipboardTileService::class.java).forEach {
                        runCatching { TileService.requestListeningState(app, ComponentName(app, it)) }
                    }
                }
            }, DeskSurfaceRules.nextRefreshDelay(lastRefreshAt, SystemClock.elapsedRealtime()))
        }
    }

    /** The phone's own battery, for the widget. */
    fun phoneBattery(context: Context): Pair<Int, Boolean> {
        val manager = context.getSystemService(BatteryManager::class.java)
        val level = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && manager?.isCharging == true
        return level to charging
    }

    // ---- actions, shared by tiles and widget ------------------------------------------------------

    enum class Action { LOCK, RING, PLAY_PAUSE, KEEP_AWAKE }

    /**
     * Runs [action] on the current desk, or says why not: only while the desk is ready (Phase 1),
     * and only through the feature the person has on.
     */
    fun perform(context: Context, action: Action, deviceId: String? = null) {
        val device = deviceId?.let { DeskLinkApplication.getInstance().getDevice(it) }?.takeIf { it.isPaired } ?: currentDesk(context)
        fun tell(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        if (device == null) return tell(context.getString(R.string.surfaces_not_paired))
        if (!device.hasActiveWebRtcFeatureTransport) return tell(context.getString(R.string.settings_waiting_for, device.name))
        when (action) {
            Action.LOCK -> {
                if (device.getPlugin(org.desklink.mobile.plugins.lockdevice.LockDevicePlugin::class.java) == null) return tell(context.getString(R.string.home_feature_off))
                device.sendUserAction(NetworkPacket(DeskLinkProtocol.PACKET_TYPE_LOCK_REQUEST).apply { this["setLocked"] = true })
                tell(context.getString(R.string.home_locked, device.name))
            }
            Action.RING -> {
                if (device.getPlugin(org.desklink.mobile.plugins.findremotedevice.FindRemoteDevicePlugin::class.java) == null) return tell(context.getString(R.string.home_feature_off))
                device.sendUserAction(NetworkPacket(DeskLinkProtocol.PACKET_TYPE_FINDMYPHONE_REQUEST))
                tell(context.getString(R.string.home_rang, device.name))
            }
            Action.PLAY_PAUSE -> device.getPlugin(MprisPlugin::class.java)?.togglePlayPause() ?: tell(context.getString(R.string.home_feature_off))
            Action.KEEP_AWAKE -> {
                val plugin = device.getPlugin(DeskSettingsPlugin::class.java)
                val settings = plugin?.settings ?: return tell(context.getString(R.string.settings_waiting_for, device.name))
                if (!DeskSettingsPlugin.keepAwakeUsable(settings)) return tell(context.getString(R.string.settings_turn_on_at, device.name))
                val on = DeskSettingsPlugin.shown(DeskSettingsPlugin.KEEP_AWAKE, settings.keepAwake, plugin.pending)
                plugin.set(DeskSettingsPlugin.KEEP_AWAKE, !on)
            }
        }
        refresh(context)
    }
}
