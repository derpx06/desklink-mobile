/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.protocol

import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.R
import org.desklink.mobile.plugins.desksettings.DeskSettingsPlugin
import org.desklink.mobile.plugins.deskunlock.DeskUnlockPlugin
import org.desklink.mobile.plugins.inputdevicesreceiver.Glide
import org.desklink.mobile.plugins.telephony.CallFlags
import org.desklink.mobile.plugins.telephony.CallRequests
import org.desklink.mobile.plugins.telephony.CallTracker
import org.desklink.mobile.plugins.telephony.CallControls
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Body values shared with the desk (`shared/wire_values.json`, an identical copy of the desk's
 * `testdata/wire_values.json`, pinned there by `the_wire_values_are_the_desks`).
 */
class WireValuesTest {
    private val f = JSONObject(javaClass.getResourceAsStream("/shared/wire_values.json")!!.bufferedReader().readText())
    private fun strings(array: JSONArray) = (0 until array.length()).map { array.getString(it) }

    @Test
    fun `settings reports need the desk's keys and requests carry only the settable ones`() {
        val keys = strings(f.getJSONObject("settings").getJSONArray("reportKeys"))
        fun report(vararg present: String) = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SETTINGS).apply {
            present.forEach { this[it] = false }
        }
        assertNotNull(DeskSettingsPlugin.parse(report(*keys.toTypedArray())))
        for (key in keys) assertNull(key, DeskSettingsPlugin.parse(report(*(keys - key).toTypedArray())))
        assertEquals(strings(f.getJSONObject("settings").getJSONArray("settable")).toSet(), DeskSettingsPlugin.SETTABLE)
    }

    @Test
    fun `the desk's glide ask is read as one`() {
        assertTrue(Glide.asksToTurnOn(f.getJSONObject("glideAsk").getJSONObject("set")))
    }

    @Test
    fun `every unlock refusal the desk sends has its own words`() {
        for (code in strings(f.getJSONArray("unlockRefusals"))) {
            assertEquals(code, code == "failed", DeskUnlockPlugin.refusalText(code) == R.string.unlock_refused_other)
        }
    }

    @Test
    fun `call states, actions, errors and emergency numbers are the desk's`() {
        val t = f.getJSONObject("telephony")
        assertEquals(
            strings(t.getJSONArray("callStates")).toSet(),
            setOf(CallTracker.STATE_RINGING, CallTracker.STATE_DIALING, CallTracker.STATE_TALKING, CallTracker.STATE_HELD),
        )
        assertEquals(
            strings(t.getJSONArray("errors")).toSet(),
            setOf(CallRequests.ERROR_PERMISSION, CallRequests.ERROR_NO_CALL, CallRequests.ERROR_FAILED, CallRequests.ERROR_EMERGENCY),
        )
        val controls = object : CallControls {
            override fun canAnswer() = true
            override fun canDial() = true
            override fun acceptRingingCall() = true
            override fun endCall() = true
            override fun notificationAction(hangUp: Boolean) = true
            override fun muteRinger() = true
            override fun setMicrophoneMute(mute: Boolean) = true
            override fun placeCall(number: String) = true
            override fun askToPlaceCall(number: String) = true
            override fun openMessages(number: String) = true
        }
        // With no call, a known action is refused for that (or done); only an unknown one "failed".
        for (action in strings(t.getJSONArray("actions"))) {
            val tracker = CallTracker({ 0L }, null) { "c" }
            assertNotEquals(action, CallRequests.ERROR_FAILED, CallRequests.handle(action, "5550100", tracker, controls, CallFlags()))
        }
        for (number in strings(t.getJSONArray("emergency"))) {
            for (dialled in listOf(number, "+$number", "00$number")) assertTrue(dialled, CallRequests.isEmergency(dialled))
        }
    }
}
