/*
 * SPDX-FileCopyrightText: 2026 Youssef Al-Bor3y <youssefelbor3y@protonmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.inputdevicesreceiver

import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.R
import org.desklink.mobile.helpers.RateLimiter
import org.desklink.mobile.plugins.Plugin
import org.desklink.mobile.plugins.PluginFactory.LoadablePlugin
import org.desklink.mobile.plugins.mousereceiver.MouseReceiverService
import org.desklink.mobile.plugins.screen.SessionLiveNotification
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol

/**
 * F4 glide, KDE Connect's `shareinputdevices` edge protocol: the desk's pointer enters across an
 * edge (G1), its mousepad packets move the accessibility cursor (G2), and reaching the same edge
 * hands it back (G3). Only while "Let <desk> control this phone" is on ([Glide.allowed]).
 */
@LoadablePlugin
class InputDevicesReceiverPlugin : Plugin() {
    override val displayName: String
        get() = context.resources.getString(R.string.pref_plugin_inputdevicesreceiver)

    override val description: String
        get() = context.resources.getString(R.string.pref_plugin_inputdevicesreceiver_desc)

    /** Where MouseReceiverService last put the cursor (it sets these as it moves). */
    object Cursor {
        var x = 0
        var y = 0
    }

    private val metrics = DisplayMetrics()
    private lateinit var displayManager: DisplayManager
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) { resolveDisplaySize() }
    }
    private val enters = RateLimiter(Glide.MAX_ENTERS_PER_SECOND, 1000, TAG)

    override fun onCreate(): Boolean {
        // A rotation while the pointer is here changes the edges.
        displayManager = context.getSystemService(DisplayManager::class.java)
        resolveDisplaySize()
        displayManager.registerDisplayListener(displayListener, null)
        return true
    }

    override fun onDestroy() {
        displayManager.unregisterDisplayListener(displayListener)
        if (Glide.activeDesk == device.deviceId) stop(release = false)
        cancelAsk(context, device.deviceId)
    }

    /** Whether a glide could start now: the person's switch for this desk and the accessibility service that moves the pointer. */
    fun glideAllowed() = Glide.allowed(context, device.deviceId) && MouseReceiverService.instance != null

    /**
     * `desklink.shareinputdevices {allowed}`: a report of state, never a command, so the desk arms its edge only when a glide
     * could be taken. Sent on every feature-ready and whenever the switch or the service changes.
     */
    fun reportAllowed() {
        val ready = glideAllowed()
        // Locked: not now, and why (the desk says "Unlock <phone> to use Glide"); allowed again once unlocked (USER_PRESENT).
        val locked = ready && locked()
        // Allowed now, however it came about: the desk's ask is answered, its prompt goes.
        if (ready && !locked) cancelAsk(context, device.deviceId)
        device.sendPacket(statePacket(ready && !locked, entered = false, reason = if (locked) REASON_LOCKED else null))
    }

    /** "Not now" on the desk's ask: the desk hears it was declined (and stops waiting). Still a state report. */
    fun reportDeclined() {
        if (glideAllowed() && !locked()) return reportAllowed()
        device.sendPacket(statePacket(false, entered = false, reason = REASON_DECLINED))
    }

    /** No glide over the lock screen: the desk's pointer would reach its shade, tiles and notifications. */
    private fun locked() = org.desklink.mobile.helpers.LockedActions.isLocked(context)

    override fun onFeatureReady() = reportAllowed()

    /** The link is gone: the desk's own link-loss rule gives it its pointer back; here the overlay must not stay. */
    override fun onFeatureLost() {
        if (Glide.activeDesk == device.deviceId) stop(release = false)
    }

    private fun resolveDisplaySize() {
        @Suppress("DEPRECATION")
        displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.getRealMetrics(metrics)
    }

    private fun sendRelease(dx: Int, dy: Int) {
        device.sendPacket(releasePacket(dx, dy))
    }

    /** The glide ends here: the pointer hides, and (unless the desk ended it) the desk gets it back. */
    fun stop(release: Boolean) {
        if (release) {
            val (dx, dy) = Glide.handBack(Glide.enterEdge, Cursor.x, Cursor.y, Glide.edgeLength, metrics.widthPixels, metrics.heightPixels)
            sendRelease(dx, dy)
        }
        letGoOfThePointer()
    }

    /** What every way of ending a glide has in common: no drag left held, no pointer on screen, no state. */
    private fun letGoOfThePointer() {
        // A drag the desk started with a held button must not outlive the glide: it would swallow the next tap.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) runCatching { MouseReceiverService.instance?.stopSwipe() }
        Glide.end()
        MouseReceiverService.instance?.hide(0)
        SessionLiveNotification.refresh(context, device.deviceId)
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        when (np.type) {
            DeskLinkProtocol.PACKET_TYPE_SHARE_INPUT_DEVICES_REQUEST -> onEnter(np)
            DeskLinkProtocol.PACKET_TYPE_MOUSEPAD_REQUEST -> onMotion(np)
            DeskLinkProtocol.PACKET_TYPE_SETTINGS_REQUEST -> onAsked(np)
            else -> return false
        }
        return true
    }

    /**
     * The desk asks this phone to turn Glide on (its person pressed "Ask on phone", or just set Glide to this phone): a prompt
     * here, never a flipped switch. A heads-up notification with Allow and Not now, so it reaches the person whatever app is in
     * front (an activity started from the background is blocked on Android 10+ and ColorOS); one per desk, re-alerting when the
     * desk asks again, gone when answered, when Glide becomes allowed, or after [ASK_TIMEOUT_MS] (the desk's own wait).
     */
    private fun onAsked(np: NetworkPacket) {
        if (!asks.allow(np.type)) return
        val prompt = Glide.promptFor(Glide.asksToTurnOn(runCatching { np.getJSONObject("set") }.getOrNull()),
            Glide.allowed(context, device.deviceId), MouseReceiverService.instance != null)
        if (prompt == Glide.Prompt.NONE) return reportAllowed()
        Log.i(TAG, "The desk asks for Glide ($prompt)")
        @android.annotation.SuppressLint("MissingPermission")
        fun post() = androidx.core.app.NotificationManagerCompat.from(context)
            .notify(askTag(device.deviceId), ASK_NOTICE_ID, askNotice(context, device.deviceId, device.name, prompt, MouseReceiverService.instance != null))
        runCatching { post() }.onFailure { Log.w(TAG, "The Glide prompt could not be shown (${it.javaClass.simpleName})") }
    }

    /** At most one prompt every few seconds per desk, whatever the desk sends. */
    private val asks = RateLimiter(1, 5_000, TAG)

    /** G1. */
    private fun onEnter(np: NetworkPacket) {
        if (!enters.allow(np.type)) return
        val edge = np.getInt("exitEdge", Glide.NONE_EDGE)
        if (edge == Glide.NONE_EDGE) {
            // G4: the desk took the pointer back.
            if (Glide.activeDesk == device.deviceId) stop(release = false)
            return
        }
        val deltax = np.getDouble("deltax", 0.0)
        val deltay = np.getDouble("deltay", 0.0)
        val length = np.getDouble("edgeLength", 0.0).takeIf { it > 0 && it <= Glide.MAX_DESK_PX }
        // Not allowed, no accessibility service, a dark screen (nobody to see the pointer), or a nonsense edge: hand it straight back (G5).
        if (!Glide.validEdge(edge) || !Glide.allowed(context, device.deviceId) || MouseReceiverService.instance == null || !screenIsOn() || locked()) {
            Log.i(TAG, "Glide refused (allowed=${Glide.allowed(context, device.deviceId)}, service=${MouseReceiverService.instance != null}, locked=${locked()})")
            sendRelease(0, deltay.coerceIn(-Glide.MAX_DESK_PX, Glide.MAX_DESK_PX).toInt())
            // And the reason as state, so the desk stops arming the edge for nothing.
            reportAllowed()
            return
        }
        // Another desk was gliding: it gets its pointer back first.
        Glide.activeDesk?.takeIf { it != device.deviceId }?.let { other ->
            org.desklink.mobile.DeskLinkApplication.getInstance().getDevice(other)?.getPlugin(InputDevicesReceiverPlugin::class.java)?.stop(release = true)
        }
        resolveDisplaySize()
        Glide.begin(device.deviceId, edge, length)
        val (x, y) = Glide.entry(edge, deltax, deltay, length, metrics.widthPixels, metrics.heightPixels)
        MouseReceiverService.setPos(x, y)
        SessionLiveNotification.refresh(context, device.deviceId)
        // The accept (W6): the desk streams nothing until it hears this, and takes silence for a refusal after 1 s. Sent
        // only for an enter acted on, and before any hand-back this glide can produce.
        sendAccept()
    }

    /** `desklink.shareinputdevices {allowed: true, entered: true}`: a state report that also answers the enter. */
    private fun sendAccept() {
        device.sendPacket(statePacket(allowed = true, entered = true))
    }

    private fun screenIsOn() = context.getSystemService(android.os.PowerManager::class.java)?.isInteractive != false

    private var lockCheckedAt = -LOCK_CHECK_MS

    /** G2 motion: hand back once the pointer reaches the edge it came in by. */
    private fun onMotion(np: NetworkPacket) {
        if (!Glide.isGliding(device.deviceId)) return
        // The accessibility service was switched off or killed mid-glide: nothing can show or move the pointer, so
        // the desk gets it back now rather than typing into the void.
        if (MouseReceiverService.instance == null) return stop(release = true)
        // The phone locked mid-glide (the screen-off path covers the usual case): the pointer goes back. Looked at twice a second.
        val now = SystemClock.elapsedRealtime()
        if (now - lockCheckedAt >= LOCK_CHECK_MS) {
            lockCheckedAt = now
            if (locked()) {
                stop(release = true)
                return reportAllowed()
            }
        }
        Glide.lastPacketAt = now
        if (!np.has("dx") || np.getBoolean("scroll", false)) return
        Glide.release(Glide.enterEdge, Cursor.x, Cursor.y, Glide.edgeLength, metrics.widthPixels, metrics.heightPixels)?.let { (dx, dy) ->
            sendRelease(dx, dy)
            letGoOfThePointer()
        }
    }

    override val supportedPacketTypes: Array<String> = arrayOf(
        DeskLinkProtocol.PACKET_TYPE_MOUSEPAD_REQUEST,
        DeskLinkProtocol.PACKET_TYPE_SHARE_INPUT_DEVICES_REQUEST,
        // The desk asking for Glide to be turned on here: a prompt (onAsked), never a switch flipped for the person.
        DeskLinkProtocol.PACKET_TYPE_SETTINGS_REQUEST,
    )
    override val outgoingPacketTypes: Array<String> = arrayOf(DeskLinkProtocol.PACKET_TYPE_SHARE_INPUT_DEVICES)

    /** Allow and Not now on the desk's ask. Not exported: no other app may turn Glide on. */
    class AskReceiver : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            val deviceId = intent.getStringExtra(EXTRA_DEVICE) ?: return
            when (intent.action) {
                ACTION_ALLOW -> answered(context, deviceId, allow = true)
                ACTION_NOT_NOW -> answered(context, deviceId, allow = false)
            }
        }
    }

    companion object {
        private const val TAG = "DeskLink/Glide"
        private const val ASK_NOTICE_ID = 4417
        private const val LOCK_CHECK_MS = 500L

        /** `reason` of an `allowed: false` report: the phone is locked (glide_bodies.json). */
        const val REASON_LOCKED = "locked"

        /** `reason` of an `allowed: false` report: the person tapped "Not now" on the desk's ask (glide_bodies.json). */
        const val REASON_DECLINED = "declined"

        /** The prompt goes after this long: the desk stops waiting then too (`glide::ASK_WAIT`). */
        const val ASK_TIMEOUT_MS = 60_000L

        const val ACTION_ALLOW = "org.desklink.mobile.glide.ALLOW"
        const val ACTION_NOT_NOW = "org.desklink.mobile.glide.NOT_NOW"
        const val EXTRA_DEVICE = "deviceId"

        @JvmStatic
        fun askTag(deviceId: String) = "glide-ask-$deviceId"

        @JvmStatic
        fun cancelAsk(context: android.content.Context, deviceId: String) =
            androidx.core.app.NotificationManagerCompat.from(context).cancel(askTag(deviceId), ASK_NOTICE_ID)

        /**
         * The prompt for a desk's ask. Allow turns the switch on for that desk; when the accessibility service that moves the
         * pointer is not running, Allow opens its setup (an activity: a broadcast may not start one from a notification).
         */
        @JvmStatic
        fun askNotice(
            context: android.content.Context,
            deviceId: String,
            deskName: String,
            prompt: Glide.Prompt,
            serviceOn: Boolean,
        ): android.app.Notification {
            val code = askTag(deviceId).hashCode()
            val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            val open = android.app.PendingIntent.getActivity(
                context, code,
                android.content.Intent(context, org.desklink.mobile.ui.MainActivity::class.java)
                    .putExtra(org.desklink.mobile.ui.MainActivity.EXTRA_SETTINGS_PAGE, org.desklink.mobile.ui.compose.screen.settings.SettingsPage.CONTROL.route)
                    .putExtra(org.desklink.mobile.ui.MainActivity.EXTRA_DEVICE_ID, deviceId)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                flags,
            )
            fun answer(action: String) = android.app.PendingIntent.getBroadcast(
                context, code + action.hashCode(),
                android.content.Intent(context, AskReceiver::class.java).setAction(action).putExtra(EXTRA_DEVICE, deviceId),
                flags,
            )
            val allow = if (prompt == Glide.Prompt.TURN_ON && serviceOn) {
                answer(ACTION_ALLOW)
            } else {
                android.app.PendingIntent.getActivity(
                    context, code + 1,
                    android.content.Intent(context, org.desklink.mobile.plugins.mousereceiver.AccessibilitySetupActivity::class.java)
                        .putExtra(org.desklink.mobile.plugins.mousereceiver.AccessibilitySetupActivity.EXTRA_ALLOW_GLIDE, deviceId)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    flags,
                )
            }
            val (title, text) = askWords(context, prompt, deskName)
            return androidx.core.app.NotificationCompat.Builder(context, org.desklink.mobile.helpers.NotificationHelper.Channels.REQUESTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
                // Heads-up: the channel is high importance; this is the same for Android 7.
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(open)
                .addAction(
                    androidx.core.app.NotificationCompat.Action.Builder(0, context.getString(R.string.glide_ask_allow), allow)
                        // Never from the lock screen without unlocking: it lets the desk control this phone.
                        .setAuthenticationRequired(true)
                        .build(),
                )
                .addAction(0, context.getString(R.string.glide_ask_not_now), answer(ACTION_NOT_NOW))
                .setAutoCancel(true)
                .setTimeoutAfter(ASK_TIMEOUT_MS)
                .build()
        }

        /** The person answered the prompt for [deviceId]; [allow]: Glide is on for that desk now. */
        @JvmStatic
        fun answered(context: android.content.Context, deviceId: String, allow: Boolean) {
            cancelAsk(context, deviceId)
            if (allow) Glide.setAllowed(context, deviceId, true)
            val plugin = runCatching {
                org.desklink.mobile.DeskLinkApplication.getInstance().getDevice(deviceId)?.getPlugin(InputDevicesReceiverPlugin::class.java)
            }.getOrNull() ?: return
            org.desklink.mobile.helpers.ThreadHelper.execute {
                runCatching { if (allow) plugin.reportAllowed() else plugin.reportDeclined() }
            }
        }

        /** The prompt's title and text: "<computer> wants to use Glide. Turn on?" and what to do. */
        @JvmStatic
        fun askWords(context: android.content.Context, prompt: Glide.Prompt, desk: String): Pair<String, String> = when (prompt) {
            Glide.Prompt.ACCESSIBILITY -> context.getString(R.string.glide_ask_title, desk) to context.getString(R.string.settings_glide_accessibility, desk)
            else -> context.getString(R.string.glide_ask_title, desk) to context.getString(R.string.glide_ask_text, desk)
        }

        /** A state report (never a command): `entered` is only ever said together with `allowed`. Shared fixture glide_bodies.json. */
        @JvmStatic
        @JvmOverloads
        fun statePacket(allowed: Boolean, entered: Boolean, reason: String? = null): NetworkPacket =
            NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SHARE_INPUT_DEVICES).apply {
                this["allowed"] = allowed
                if (entered && allowed) this["entered"] = true
                if (!allowed && reason != null) this["reason"] = reason
            }

        /** The pointer reached the edge it came in by: where the desk's pointer goes on. */
        @JvmStatic
        fun releasePacket(dx: Int, dy: Int): NetworkPacket =
            NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SHARE_INPUT_DEVICES).apply {
                this["releaseDeltax"] = dx
                this["releaseDeltay"] = dy
            }

        /**
         * Ends whatever glide is on, whichever desk: the screen went off, or the accessibility service went away.
         * With [release] the desk is told to take its pointer back (best effort: the link may be asleep).
         */
        /** The switch or the accessibility service changed: every desk that has this plugin hears the new state. */
        @JvmStatic
        fun reportAllowedToAll() {
            val app = org.desklink.mobile.DeskLinkApplication.getInstance()
            app.devices.values.forEach { d -> runCatching { d.getPlugin(InputDevicesReceiverPlugin::class.java)?.takeIf { d.isPaired }?.reportAllowed() } }
        }

        @JvmStatic
        fun endActive(release: Boolean) {
            val id = Glide.activeDesk ?: return
            val plugin = org.desklink.mobile.DeskLinkApplication.getInstance().getDevice(id)?.getPlugin(InputDevicesReceiverPlugin::class.java)
            if (plugin != null) runCatching { plugin.stop(release) } else Glide.end()
        }
    }
}
