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
        // The application's start-up thread runs PrivacyDefaults.grandfather() over every trusted device; settle it first so it
        // cannot write into this test's pairing (the lock waits for a run in progress, and a done marker stops a later one).
        org.desklink.mobile.helpers.PrivacyDefaults.grandfather(context, emptyList())
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

    @Test
    fun aFailedReplaceAlsoGetsBackThePhonesChoicesForThatComputer() {
        org.desklink.mobile.helpers.PrivacyDefaults.grandfather(context, emptyList())
        TrustedDevices.addTrustedDevice(context, id)
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putBoolean("glide_allowed_$id", true).putBoolean("private_notifications_$id", true).putBoolean("dynamic_colors", true).commit()
        val backup = PairingBackup.capture(context, id)

        org.desklink.mobile.helpers.PeerState.forget(context, id) // the unpair a replace starts with
        assertFalse(prefs.contains("glide_allowed_$id"))

        backup.restore(context) // the scan failed
        assertTrue(prefs.getBoolean("glide_allowed_$id", false))
        assertTrue(prefs.getBoolean("private_notifications_$id", false))
        assertTrue("and what is not that computer's is untouched", prefs.getBoolean("dynamic_colors", false))
    }
}
