/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins

import android.app.NotificationManager
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.plugins.battery.DndSync
import org.desklink.mobile.plugins.desksettings.DeskSettingsPlugin
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** W14: Do Not Disturb sync, the phone's half. */
class DndSyncTest {
    private fun settings(vararg extra: Pair<String, Boolean>) = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SETTINGS).apply {
        mapOf("keepAwake" to true, "clipboard" to false, "notifications" to true, "relayConfigured" to false, "relay" to false, "commandAlerts" to true, "unlockNearby" to true)
            .forEach { (k, v) -> this[k] = v }
        extra.forEach { (k, v) -> this[k] = v }
    }

    @Test
    fun theKeysAreReportedOnlyWhileTheComputersSwitchIsOn() {
        assertEquals(emptyMap<String, Boolean>(), DndSync.keys(syncOn = false, dnd = true, canSet = true))
        assertEquals(mapOf("dnd" to true, "dndCanSet" to false), DndSync.keys(syncOn = true, dnd = true, canSet = false))
    }

    @Test
    fun anythingButAllIsDoNotDisturb() {
        assertFalse(DndSync.isDnd(NotificationManager.INTERRUPTION_FILTER_ALL))
        assertFalse(DndSync.isDnd(NotificationManager.INTERRUPTION_FILTER_UNKNOWN))
        assertTrue(DndSync.isDnd(NotificationManager.INTERRUPTION_FILTER_PRIORITY))
        assertTrue(DndSync.isDnd(NotificationManager.INTERRUPTION_FILTER_ALARMS))
        assertTrue(DndSync.isDnd(NotificationManager.INTERRUPTION_FILTER_NONE))
    }

    @Test
    fun theSettingsReportCarriesTheSwitchAndTheValueOnlyWhileItIsOn() {
        assertNull(DeskSettingsPlugin.parse(settings())!!.dndSync)
        assertEquals(false, DeskSettingsPlugin.parse(settings("dndSync" to false, "dnd" to true))!!.dndSync)
        assertNull("a value without the switch is ignored", DeskSettingsPlugin.parse(settings("dndSync" to false, "dnd" to true))!!.dnd)
        val on = DeskSettingsPlugin.parse(settings("dndSync" to true, "dnd" to true))!!
        assertEquals(true, on.dndSync); assertEquals(true, on.dnd)
    }

    @Test
    fun theDesksChangeIsAppliedOnlyWhenItIsNewDifferentAndAllowed() {
        var now = 0L
        val loop = DndSync.Loop { now }
        assertNull("not allowed: nothing changes", loop.fromDesk(true, phoneDnd = false, canSet = false))
        assertNull("the same value again is not a change", loop.fromDesk(true, phoneDnd = false, canSet = true))
        assertEquals("a new value that differs", false, loop.fromDesk(false, phoneDnd = true, canSet = true))
        assertNull("the phone already has it", loop.fromDesk(true, phoneDnd = true, canSet = true))
        assertNull("no value from the desk", loop.fromDesk(null, phoneDnd = true, canSet = true))
    }

    @Test
    fun aChangeIsNotReportedBackToWhoSentItNorWhenItIsOurOwnWriteComingBack() {
        var now = 0L
        val loop = DndSync.Loop { now }
        assertTrue("a change made on the phone", loop.localChange(true))
        loop.fromDesk(false, phoneDnd = true, canSet = true) // the computer says off; the phone applies it
        now += 500
        assertFalse("the filter change we caused", loop.localChange(false))
        now += DndSync.APPLIED_ECHO_MS
        assertTrue("the person turns it on a moment later", loop.localChange(true))
        loop.fromDesk(true, phoneDnd = true, canSet = true)
        assertFalse("the computer already has on", loop.localChange(true))
        loop.reset()
        assertTrue("after the switch goes off nothing received counts", loop.localChange(true))
    }

    @Test
    fun theGalleryFlagIsReadFromTheSettingsReport() {
        assertNull(DeskSettingsPlugin.parse(settings())!!.galleryAccepted)
        assertEquals(true, DeskSettingsPlugin.parse(settings("galleryAccepted" to true))!!.galleryAccepted)
        assertEquals(false, DeskSettingsPlugin.parse(settings("galleryAccepted" to false))!!.galleryAccepted)
    }
}
