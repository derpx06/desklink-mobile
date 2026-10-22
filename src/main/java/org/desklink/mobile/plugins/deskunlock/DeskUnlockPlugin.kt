/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.plugins.deskunlock

import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.R
import org.desklink.mobile.helpers.NotificationHelper
import org.desklink.mobile.helpers.LifecycleHelper
import org.desklink.mobile.plugins.Plugin
import org.desklink.mobile.plugins.PluginFactory.LoadablePlugin
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol

/** The desk's offer to be unlocked by this phone: its single-use nonce, and why it asked. */
data class UnlockOffer(val nonce: String, val reason: String, val receivedAt: Long) {
    /** The desk honours a nonce for 60 s; a little less here, so a late tap is not wasted. */
    fun isFresh(now: Long): Boolean = now - receivedAt < FRESH_MS

    companion object {
        const val REASON_BUTTON = "button"
        const val REASON_PHONE = "phone"
        const val FRESH_MS = 55_000L

        /** A nonce is 32 lowercase hex characters; anything else is not from the desk's unlock gate. */
        fun parse(np: NetworkPacket, now: Long): UnlockOffer? {
            val nonce = np.getStringOrNull("nonce") ?: return null
            if (!nonce.matches(Regex("[0-9a-f]{32}"))) return null
            val reason = np.getString("reason", REASON_BUTTON).takeIf { it == REASON_BUTTON || it == REASON_PHONE } ?: REASON_BUTTON
            return UnlockOffer(nonce, reason, now)
        }
    }
}

/**
 * Unlock the desk with this phone (off by default; switched on at the desk only).
 *
 * The desk sends `desklink.unlock.request {nonce, reason}`, after its lock-screen button or after
 * this phone's `{ask: true}`. The person confirms with biometrics or the device credential
 * ([UnlockActivity]), and only then does the phone answer `desklink.lock.request {setLocked: false,
 * nonce}`. A nonce is used once. The desk refuses anything else with `desklink.lock {unlockRefused}`.
 */
@LoadablePlugin
class DeskUnlockPlugin : Plugin() {
    var offer by mutableStateOf<UnlockOffer?>(null)
        private set

    /** The desk's last refusal code (`not-lan`, `pref-off`…), until the person has seen it. */
    var refusal by mutableStateOf<String?>(null)

    override val displayName: String
        get() = context.getString(R.string.pref_plugin_desk_unlock)

    override val description: String
        get() = context.getString(R.string.pref_plugin_desk_unlock_desc)

    override fun onPacketReceived(np: NetworkPacket): Boolean = when (np.type) {
        DeskLinkProtocol.PACKET_TYPE_UNLOCK_REQUEST -> {
            UnlockOffer.parse(np, SystemClock.elapsedRealtime())?.let(::offered)
            true
        }
        // The desk's lock *report*; only its refusal concerns this plugin (LockDevicePlugin has the rest).
        DeskLinkProtocol.PACKET_TYPE_LOCK -> {
            np.getStringOrNull("unlockRefused")?.let { code ->
                refusal = code
                clear()
                val message = context.getString(refusalText(code), device.name)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                }
            }
            np.has("unlockRefused")
        }
        else -> false
    }

    // Without notification permission the sheet still opens in front; runCatching covers the refusal.
    @android.annotation.SuppressLint("MissingPermission")
    private fun offered(next: UnlockOffer) {
        offer = next
        refusal = null
        val open = UnlockActivity.intent(context, device.deviceId)
        // Asked from this phone, or the app is in front: straight to the prompt. Otherwise (the
        // lock-screen button) a high-priority notification opens it.
        if (next.reason == UnlockOffer.REASON_PHONE || LifecycleHelper.isInForeground) {
            runCatching { context.startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        val pending = PendingIntent.getActivity(context, device.deviceId.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, NotificationHelper.Channels.REQUESTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.unlock_title, device.name))
            .setContentText(context.getString(if (next.reason == UnlockOffer.REASON_BUTTON) R.string.unlock_body_button else R.string.unlock_body_phone))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setTimeoutAfter(UnlockOffer.FRESH_MS)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notificationTag(), NOTIFICATION_ID, notification) }
    }

    /** "Unlock desk": the desk answers with a nonce if it may be unlocked now, else with its reason. */
    fun ask(): Boolean {
        // Only with the link up: a nonce asked for and delivered late would be spent by nobody.
        if (!device.hasActiveWebRtcFeatureTransport) return false
        refusal = null
        device.sendUserAction(NetworkPacket(DeskLinkProtocol.PACKET_TYPE_UNLOCK_REQUEST).apply { this["ask"] = true })
        return true
    }

    /** After the biometric prompt succeeded: spends the nonce. False when it has expired here. */
    fun approve(): Boolean {
        val current = offer ?: return false
        clear()
        if (!current.isFresh(SystemClock.elapsedRealtime())) return false
        device.sendUserAction(approvalPacket(current.nonce))
        return true
    }

    /** "Not me · keep locked", or the prompt cancelled: nothing is sent; the nonce just expires. */
    fun decline() = clear()

    private fun clear() {
        offer = null
        NotificationManagerCompat.from(context).cancel(notificationTag(), NOTIFICATION_ID)
    }

    private fun notificationTag() = "unlock:${device.deviceId}"

    override val supportedPacketTypes: Array<String> =
        arrayOf(DeskLinkProtocol.PACKET_TYPE_UNLOCK_REQUEST, DeskLinkProtocol.PACKET_TYPE_LOCK)
    override val outgoingPacketTypes: Array<String> =
        arrayOf(DeskLinkProtocol.PACKET_TYPE_UNLOCK_REQUEST, DeskLinkProtocol.PACKET_TYPE_LOCK_REQUEST)

    companion object {
        private const val NOTIFICATION_ID = 7310

        /** The desk's refusal codes (exact strings), each with what the person can do about it. */
        fun refusalText(code: String): Int = when (code) {
            "pref-off" -> R.string.unlock_refused_pref_off
            "not-lan" -> R.string.unlock_refused_not_lan
            "not-locked" -> R.string.unlock_refused_not_locked
            "not-ready" -> R.string.unlock_refused_not_ready
            "expired", "no-nonce", "replayed" -> R.string.unlock_refused_expired
            "wrong-device" -> R.string.unlock_refused_wrong_device
            else -> R.string.unlock_refused_other
        }

        fun approvalPacket(nonce: String) = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_LOCK_REQUEST).apply {
            this["setLocked"] = false
            this["nonce"] = nonce
        }
    }
}
