/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile

import android.content.Intent
import android.net.wifi.WifiManager
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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

/** The wiring: the receiver fires the report once per matching broadcast; the service registers it, reports on task removal and on destroy. */
@RunWith(RobolectricTestRunner::class)
class LeavingWiringTest {
    private val sent = mutableListOf<String>()
    private lateinit var saved: LeavingReport

    @Before
    fun swap() {
        saved = LeavingReport.shared
        var clock = 0L
        LeavingReport.shared = LeavingReport(now = { clock.also { clock += 10_000 } }, run = { it.run() }) { sent += it }
    }

    @After
    fun restore() { LeavingReport.shared = saved }

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `the receiver reports wifi switching off and airplane mode once each, and nothing else`() {
        val receiver = LeavingReceiver()
        receiver.onReceive(context, Intent(WifiManager.WIFI_STATE_CHANGED_ACTION).putExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_DISABLING))
        receiver.onReceive(context, Intent(WifiManager.WIFI_STATE_CHANGED_ACTION).putExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_DISABLED))
        receiver.onReceive(context, Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra(AIRPLANE_STATE_EXTRA, false))
        receiver.onReceive(context, Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra(AIRPLANE_STATE_EXTRA, true))
        assertEquals(listOf("wifi-off", "airplane"), sent)
    }

    @Test
    fun `an onDestroy report is waited for but only for a moment`() {
        val slow = LeavingReport(now = { 0 }, run = { work -> Thread(work).start() }) { Thread.sleep(2_000) }
        val started = System.nanoTime()
        assertTrue(slow.report("service-stopped", LeavingReport.DESTROY_WAIT_MS))
        assertTrue("bounded", (System.nanoTime() - started) / 1_000_000 < 1_000)
    }

    /** The service itself is too heavy to start here: its wiring is pinned by its source. */
    @Test
    fun `the service registers the receiver, unregisters it, and reports on task removal, destroy and a debounced network loss`() {
        val source = java.io.File("src/main/java/org/desklink/mobile/BackgroundService.kt").readText()
        assertTrue(source.contains("registerReceiver(") && source.contains("leavingReceiver,") && source.contains("IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION)"))
        assertTrue(source.contains("leavingReceiver?.let { runCatching { unregisterReceiver(it) } }"))
        assertTrue(Regex("override fun onTaskRemoved[^}]*LeavingReport.shared.report\\(\"task-removed\"\\)").containsMatchIn(source))
        assertTrue(source.contains("LeavingReport.shared.report(\"service-stopped\", LeavingReport.DESTROY_WAIT_MS)"))
        assertTrue(source.contains("leaseRecheck.postDelayed(networkLostReport, NETWORK_LOST_REPORT_DELAY_MS)"))
        // The report precedes the providers' shutdown in onDestroy.
        assertTrue(source.indexOf("\"service-stopped\"") < source.indexOf("linkProvider.onStop()"))
    }
}
