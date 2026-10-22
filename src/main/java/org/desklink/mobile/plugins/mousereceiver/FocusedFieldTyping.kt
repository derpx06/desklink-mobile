/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.mousereceiver

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol

/**
 * "Type on <phone>" (contract C2): the desk's keystrokes, sent as `desklink.mousepad.request` with
 * `target: "focused-field"`, typed into whatever field has focus on the phone through the
 * MouseReceiver accessibility service. Needs no screen session and no control lease, so it is off
 * per desk until the person turns on "Let <desk> type on this phone".
 *
 * The phone reports `desklink.mousepad.keyboardstate {state}`: whether a field has focus.
 */
object FocusedFieldTyping {
    const val TARGET_FOCUSED_FIELD = "focused-field"
    private const val TAG = "DeskLink/TypeOnPhone"

    /** Fields of a pointer packet: a focused-field packet carrying any is refused. */
    private val POINTER_FIELDS = listOf(
        "dx", "dy", "x", "y", "singleclick", "doubleclick", "middleclick", "rightclick",
        "forwardclick", "backclick", "singlehold", "singlerelease", "scroll",
    )

    fun targetsFocusedField(np: NetworkPacket): Boolean = np.getString("target", "") == TARGET_FOCUSED_FIELD

    /** Keys only: text (`key`) or a `specialKey`, with modifiers; never a pointer field. */
    fun keyboardOnly(np: NetworkPacket): Boolean =
        (np.has("key") || np.has("specialKey")) && POINTER_FIELDS.none(np::has)

    private fun key(deviceId: String) = "type_on_phone_$deviceId"

    fun allowed(context: Context, deviceId: String): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(key(deviceId), false)

    fun setAllowed(context: Context, deviceId: String, on: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit { putBoolean(key(deviceId), on) }
        lastState = null
        if (on) MouseReceiverService.instance?.let { report(context, focusedFieldIn(it), force = true) }
    }

    @Volatile private var lastState: Boolean? = null

    /** Whether the accessibility service sees an editable field with input focus. */
    fun focusedFieldIn(service: android.accessibilityservice.AccessibilityService): Boolean {
        val node = runCatching { service.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
            ?: return false
        return try {
            node.isEditable
        } finally {
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }

    private const val ASK_NOTIFICATION_ID = 0x7e1e
    private const val ASK_EVERY_MS = 30_000L
    @Volatile private var lastAsked = 0L

    /**
     * Keys came from a desk that may not type here: ask the person, with an Allow button, instead
     * of dropping them silently, and tell the desk why nothing is typed (`allowed: false`).
     */
    fun askToAllow(context: Context, device: org.desklink.mobile.Device) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastAsked != 0L && now - lastAsked < ASK_EVERY_MS) return
        lastAsked = now
        device.sendPacket(
            NetworkPacket(DeskLinkProtocol.PACKET_TYPE_MOUSEPAD_KEYBOARDSTATE).apply {
                this["state"] = false
                this["allowed"] = false
            },
        )
        val allow = android.app.PendingIntent.getBroadcast(
            context,
            ASK_NOTIFICATION_ID,
            android.content.Intent(context, TypeOnPhoneAllowReceiver::class.java).putExtra(TypeOnPhoneAllowReceiver.EXTRA_DEVICE_ID, device.deviceId),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val open = android.app.PendingIntent.getActivity(
            context,
            ASK_NOTIFICATION_ID,
            android.content.Intent(context, org.desklink.mobile.ui.MainActivity::class.java)
                .putExtra(org.desklink.mobile.ui.MainActivity.EXTRA_DEVICE_ID, device.deviceId)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = androidx.core.app.NotificationCompat.Builder(context, org.desklink.mobile.helpers.NotificationHelper.Channels.REQUESTS)
            .setSmallIcon(org.desklink.mobile.R.drawable.ic_notification)
            .setContentTitle(context.getString(org.desklink.mobile.R.string.type_on_phone_ask_title, device.name))
            .setContentText(context.getString(org.desklink.mobile.R.string.type_on_phone_ask_text))
            .setContentIntent(open)
            .addAction(0, context.getString(org.desklink.mobile.R.string.type_on_phone_ask_allow), allow)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(120_000L)
            .build()
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            androidx.core.app.NotificationManagerCompat.from(context).notify(ASK_NOTIFICATION_ID, notification)
        }
        Log.i(TAG, "${device.name} tried to type; asked to allow it")
    }

    fun dismissAsk(context: Context) {
        androidx.core.app.NotificationManagerCompat.from(context).cancel(ASK_NOTIFICATION_ID)
        lastAsked = 0L
    }

    /** Sends `keyboardstate` to every desk allowed to type here, when it changed (or [force]d). */
    fun report(context: Context, focused: Boolean, force: Boolean = false) {
        if (!force && lastState == focused) return
        lastState = focused
        DeskLinkApplication.getInstance().devices.values
            .filter { it.isPaired && allowed(context, it.deviceId) && it.hasActiveWebRtcFeatureTransport }
            .forEach { device ->
                Log.d(TAG, "keyboardstate $focused to ${device.name}")
                device.sendPacket(NetworkPacket(DeskLinkProtocol.PACKET_TYPE_MOUSEPAD_KEYBOARDSTATE).apply { this["state"] = focused })
            }
    }
}
