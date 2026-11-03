/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import org.desklink.mobile.Device
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.helpers.PerPeerState
import org.desklink.mobile.plugins.battery.BatteryPlugin
import org.desklink.mobile.plugins.battery.DndSync
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** The Do Not Disturb the computer set is put back, once, and never over a filter the person chose. */
@RunWith(AndroidJUnit4::class)
class DndRestoreTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val id = "0123456789abcdef0123456789abcdef"
    private val all = NotificationManager.INTERRUPTION_FILTER_ALL
    private val priority = NotificationManager.INTERRUPTION_FILTER_PRIORITY
    private val none = NotificationManager.INTERRUPTION_FILTER_NONE

    private fun note() = context.getSharedPreferences(PerPeerState.DND_RESTORE, Context.MODE_PRIVATE).getString(id, null)

    @Before
    fun setup() {
        shadowOf(manager).setNotificationPolicyAccessGranted(true)
        manager.setInterruptionFilter(all)
        DndSync.Restore.restoreNow(context, id)
    }

    @After
    fun cleanup() {
        DndSync.Restore.restoreNow(context, id)
    }

    @Test
    fun anOffWithNothingSavedLeavesNoNoteToPushThePhoneBackLater() {
        manager.setInterruptionFilter(priority) // the person's own Do Not Disturb
        assertTrue(DndSync.apply(context, id, dnd = false))
        assertEquals(all, manager.currentInterruptionFilter)
        assertFalse(DndSync.Restore.has(context, id))
        assertNull(note())
    }

    @Test
    fun onThenOffPutsTheFirstFilterBackAndForgetsTheNote() {
        assertTrue(DndSync.apply(context, id, dnd = true))
        assertEquals(priority, manager.currentInterruptionFilter)
        assertTrue(DndSync.Restore.has(context, id))
        assertTrue(DndSync.apply(context, id, dnd = false))
        assertEquals(all, manager.currentInterruptionFilter)
        assertNull(note())
    }

    @Test
    fun aFilterThePersonChoseInBetweenIsNotRestoredOver() {
        DndSync.apply(context, id, dnd = true)
        manager.setInterruptionFilter(none) // the person's own choice
        DndSync.apply(context, id, dnd = false)
        assertEquals(none, manager.currentInterruptionFilter)
        assertFalse(DndSync.Restore.has(context, id))
    }

    @Test
    fun aSecondOnAfterThePersonChangedItRemembersTheirFilterNotTheOldOne() {
        DndSync.apply(context, id, dnd = true) // before = all
        manager.setInterruptionFilter(none)
        DndSync.apply(context, id, dnd = true) // before must now be none
        assertEquals(priority, manager.currentInterruptionFilter)
        DndSync.Restore.restoreNow(context, id)
        assertEquals(none, manager.currentInterruptionFilter)
    }

    @Test
    fun theNoteSurvivesAProcessEndAndIsReadFromDiskBeforeAnythingResumed() {
        DndSync.apply(context, id, dnd = true)
        DndSync.Restore.dropMemoryForTest()
        assertTrue(DndSync.Restore.has(context, id))
        assertTrue(DndSync.Restore.restoreNow(context, id)) // an unpair before the start-up pass ran
        assertEquals(all, manager.currentInterruptionFilter)
        assertNull(note())
    }

    @Test
    fun resumingAfterARestartKeepsTheNoteUntilTheComputerIsGoneForAMinute() {
        DndSync.apply(context, id, dnd = true)
        DndSync.Restore.dropMemoryForTest()
        DndSync.Restore.resumeAfterRestart(context)
        assertTrue(DndSync.Restore.has(context, id))
        DndSync.Restore.cancel(id) // the link came back within the minute
        assertTrue(DndSync.Restore.has(context, id))
        assertEquals(priority, manager.currentInterruptionFilter)
    }

    @Test
    fun aFirstOffFromTheComputerAfterTheLinkWasDownPutsThePhoneBack() {
        DndSync.apply(context, id, dnd = true)
        DndSync.Restore.dropMemoryForTest() // the process restarted while the computer ended its Do Not Disturb
        val device = mockk<Device>(relaxed = true) { every { deviceId } returns id }
        val battery = BatteryPlugin().apply { setContext(context, device) }
        battery.deskDndChanged(syncOn = true, deskDnd = false)
        assertEquals(all, manager.currentInterruptionFilter)
        assertNull(note())
    }

    @Test
    fun aFirstOffDoesNotTouchAFilterThePersonSetThemselves() {
        manager.setInterruptionFilter(none)
        val device = mockk<Device>(relaxed = true) { every { deviceId } returns id }
        val battery = BatteryPlugin().apply { setContext(context, device) }
        battery.deskDndChanged(syncOn = true, deskDnd = false)
        assertEquals(none, manager.currentInterruptionFilter)
    }
}
