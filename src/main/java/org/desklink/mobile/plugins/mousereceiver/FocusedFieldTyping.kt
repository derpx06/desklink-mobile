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
