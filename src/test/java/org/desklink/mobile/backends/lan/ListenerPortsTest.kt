/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.backends.lan

import java.io.IOException
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The TCP listener walks the whole port range, the last port included, and reports when none is free. */
class ListenerPortsTest {
    /** Three adjacent free ports: found by binding and releasing until a run of three exists. */
    private fun freeRun(): Int {
        for (base in 20_000..60_000 step 7) {
            val held = ArrayList<ServerSocket>()
            try {
                for (p in base..base + 2) held += ServerSocket(p)
                held.forEach { it.close() }
                return base
            } catch (_: IOException) {
                held.forEach { it.close() }
            }
        }
        error("no three adjacent free ports")
    }

    @Test
    fun theLastPortOfTheRangeIsTried() {
        val base = freeRun()
        val first = ServerSocket(base)
        val second = ServerSocket(base + 1)
        try {
            val server = LanLinkProvider.openServerSocketOnFreePort(base, base + 2)
            assertEquals("the last port, which the old loop never tried", base + 2, server.localPort)
            server.close()
        } finally {
            first.close(); second.close()
        }
    }

    @Test
    fun aRangeWithNoFreePortThrowsAnIoExceptionForTheCallerToReport() {
        val base = freeRun()
        val held = (base..base + 2).map { ServerSocket(it) }
        try {
            LanLinkProvider.openServerSocketOnFreePort(base, base + 2)
            fail("every port was taken")
        } catch (error: IOException) {
            assertTrue(error.message != null)
        } finally {
            held.forEach { it.close() }
        }
    }
}
