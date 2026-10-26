/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.helpers

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.desklink.mobile.plugins.clipboard.ClipboardPlugin
import org.desklink.mobile.plugins.notifications.NotificationsPlugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A new pairing shares neither the clipboard nor notifications until asked; an old one keeps what it had. */
@RunWith(RobolectricTestRunner::class)
class PrivacyDefaultsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun bothPluginsAreOffByDefault() {
        assertFalse(ClipboardPlugin().isEnabledByDefault)
        assertFalse(NotificationsPlugin().isEnabledByDefault)
    }

    @Test
    fun aComputerPairedBeforeKeepsItsSharingAndIsNeverAskedAndANewOneIsAsked() {
        // The application's own start-up thread also runs grandfather(): the lock keeps it from interleaving with this test.
        synchronized(PrivacyDefaults) { body() }
    }

    private fun body() {
        app.getSharedPreferences(PrivacyDefaults.MARKER_PREFS, 0).edit().clear().commit()
        val old = "aaaaaaaa-0000-0000-0000-000000000001"
        val explicitOff = "aaaaaaaa-0000-0000-0000-000000000002"
        TrustedDevices.getDeviceSettings(app, explicitOff).edit().putBoolean(PrivacyDefaults.clipboardKey(), false).commit()
        PrivacyDefaults.grandfather(app, listOf(old, explicitOff))
        assertEquals(true, TrustedDevices.getDeviceSettings(app, old).getBoolean(PrivacyDefaults.clipboardKey(), false))
        assertEquals(true, TrustedDevices.getDeviceSettings(app, old).getBoolean(PrivacyDefaults.notificationsKey(), false))
        assertEquals("an explicit choice is never overwritten", false, TrustedDevices.getDeviceSettings(app, explicitOff).getBoolean(PrivacyDefaults.clipboardKey(), true))
        assertFalse(FirstShare.needed(app, old))
        assertTrue("a new pairing is asked", FirstShare.needed(app, "bbbbbbbb-0000-0000-0000-000000000003"))
        // Once only: a later pairing is not grandfathered.
        PrivacyDefaults.grandfather(app, listOf("cccccccc-0000-0000-0000-000000000004"))
        assertTrue(FirstShare.needed(app, "cccccccc-0000-0000-0000-000000000004"))
    }

    @Test
    fun unpairingForgetsTheQuestionSoARePairIsAskedAgain() {
        val id = "dddddddd-0000-0000-0000-000000000005"
        FirstShare.markDone(app, id)
        assertFalse(FirstShare.needed(app, id))
        PeerState.forget(app, id)
        assertTrue(FirstShare.needed(app, id))
    }

    @Test
    fun grandfatheringIsDoneBeforeAnyPluginCanLoad() {
        fun source(path: String) = listOf(java.io.File(path), java.io.File("desklink-mobile/$path")).first { it.exists() }.readText()
        val app = source("src/main/java/org/desklink/mobile/DeskLinkApplication.kt")
        val grandfather = app.indexOf("PrivacyDefaults.grandfather(this)")
        assertTrue("grandfather is called", grandfather > 0)
        assertTrue("before the remembered devices (and so their plugins) load", grandfather < app.indexOf("loadRememberedDevicesFromSettings() }"))
        assertEquals("and only there, not on a background thread", 1, Regex("PrivacyDefaults\\.grandfather\\(this\\)").findAll(app).count())
    }
}
