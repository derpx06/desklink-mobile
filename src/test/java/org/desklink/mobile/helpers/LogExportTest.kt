/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LogExportTest {
    @Test
    fun addressesAndKeysAreTakenOut() {
        assertEquals("link to <ip>:1716 from <ip>", LogExport.redact("link to 192.168.1.20:1716 from fe80:0:0:0:1:2:3:4"))
        assertEquals("<mac> seen", LogExport.redact("aa:bb:cc:dd:ee:ff seen"))
        assertFalse(LogExport.redact("fp " + "ab".repeat(32)).contains("ab".repeat(10)))
        assertEquals("plain line", LogExport.redact("plain line"))
    }

    @Test
    fun onlyThisProcessIsAskedFor() {
        val cmd = LogExport.command(4242)
        assertEquals("logcat", cmd[0])
        assertEquals(true, cmd.any { it == "--pid=4242" } || android.os.Build.VERSION.SDK_INT < 24)
    }
}
