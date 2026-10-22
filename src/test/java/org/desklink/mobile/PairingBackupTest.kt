/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.desklink.mobile.helpers.TrustedDevices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PairingBackupTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val id = "0123456789abcdef0123456789abcdef"

    @Test
    fun aFailedReplaceCanPutTheOldPairingBack() {
        TrustedDevices.addTrustedDevice(context, id)
        TrustedDevices.getDeviceSettings(context, id).edit().putString("certificate", "CERT").putString("deviceName", "Work Laptop").putInt("protocolVersion", 9).commit()
        val backup = PairingBackup.capture(context, id)

        TrustedDevices.removeTrustedDevice(context, id) // the unpair a replace starts with
        assertFalse(TrustedDevices.isTrustedDevice(context, id))
        assertTrue(TrustedDevices.getDeviceSettings(context, id).all.isEmpty())

        backup.restore(context) // the scan failed
        assertTrue(TrustedDevices.isTrustedDevice(context, id))
        val prefs = TrustedDevices.getDeviceSettings(context, id)
        assertEquals("CERT", prefs.getString("certificate", null))
        assertEquals("Work Laptop", prefs.getString("deviceName", null))
        assertEquals(9, prefs.getInt("protocolVersion", 0))
    }
}
