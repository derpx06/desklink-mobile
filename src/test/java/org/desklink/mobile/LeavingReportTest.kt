/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile

import android.content.Intent
import android.net.wifi.WifiManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's `leaving` report: which broadcasts mean it, and that each trigger sends it once. */
class LeavingReportTest {
    @Test
    fun `wifi switching off and airplane mode are leaving, nothing else is`() {
        val wifi = WifiManager.WIFI_STATE_CHANGED_ACTION
        assertEquals("wifi-off", leavingReasonFor(wifi, WifiManager.WIFI_STATE_DISABLING, false))
        for (state in listOf(WifiManager.WIFI_STATE_DISABLED, WifiManager.WIFI_STATE_ENABLING, WifiManager.WIFI_STATE_ENABLED, WifiManager.WIFI_STATE_UNKNOWN)) {
            assertNull("wifi state $state", leavingReasonFor(wifi, state, false))
        }
        assertEquals("airplane", leavingReasonFor(Intent.ACTION_AIRPLANE_MODE_CHANGED, -1, true))
        assertNull("airplane mode off", leavingReasonFor(Intent.ACTION_AIRPLANE_MODE_CHANGED, -1, false))
        assertNull(leavingReasonFor(Intent.ACTION_SCREEN_OFF, WifiManager.WIFI_STATE_DISABLING, true))
        assertNull(leavingReasonFor(null, WifiManager.WIFI_STATE_DISABLING, true))
    }

    /** Each trigger (service end, task removed, network lost, wifi off, airplane) calls `report` once: one write each. */
    @Test
    fun `every trigger sends the report once`() {
        val sent = mutableListOf<String>()
        var clock = 0L
        val report = LeavingReport(now = { clock }, run = { it.run() }) { sent += it }
        for ((i, reason) in listOf("wifi-off", "airplane", "service-stopped", "task-removed", "network-lost").withIndex()) {
            clock = i * 10_000L
            assertTrue(reason, report.report(reason))
        }
        assertEquals(listOf("wifi-off", "airplane", "service-stopped", "task-removed", "network-lost"), sent)
    }

    /** Wi-Fi off brings airplane mode, a lost network and the service's end within a moment: one report. */
    @Test
    fun `a burst of triggers is one report`() {
        val sent = mutableListOf<String>()
        var clock = 100L
        val report = LeavingReport(now = { clock }, run = { it.run() }) { sent += it }
        assertTrue(report.report("wifi-off"))
        clock += 50
        assertFalse(report.report("network-lost"))
        clock += LeavingReport.COALESCE_MS - 100
        assertFalse(report.report("service-stopped"))
        clock += 200
        assertTrue("the burst is over", report.report("task-removed"))
        assertEquals(listOf("wifi-off", "task-removed"), sent)
    }

    @Test
    fun `the report is a state report on the heartbeat, not a command`() {
        val packet = heartbeatPacket(reply = true, interactive = true, leaving = "airplane")
        assertEquals(org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol.PACKET_TYPE_HEARTBEAT, packet.type)
        assertTrue(packet.getBoolean("reply", false))
        assertTrue(packet.getBoolean("leaving", false))
        assertEquals("airplane", packet.getString("reason"))
        assertFalse("awake", packet.getBoolean("dozing", true))
    }
}
