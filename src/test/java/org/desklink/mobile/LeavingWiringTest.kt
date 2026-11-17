/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile

import android.app.Application
import android.content.Intent
import android.net.wifi.WifiManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
        assertTrue(source.contains("reportsOnDestroy(wasInitialized)) LeavingReport.shared.report(\"service-stopped\", LeavingReport.DESTROY_WAIT_MS)"))
        // A never-started service (refused foreground start -> stopSelf) says nothing.
        assertFalse(reportsOnDestroy(false))
        assertTrue(reportsOnDestroy(true))
        assertTrue(source.indexOf("if (safeStart) return super.onDestroy()") < source.indexOf("val wasInitialized"))
        assertTrue(source.contains("leaseRecheck.postDelayed(networkLostReport, NETWORK_LOST_REPORT_DELAY_MS)"))
        // The report precedes the providers' shutdown in onDestroy.
        assertTrue(source.indexOf("\"service-stopped\"") < source.indexOf("linkProvider.onStop()"))
    }
}
