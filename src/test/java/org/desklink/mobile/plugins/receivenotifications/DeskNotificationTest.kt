package org.desklink.mobile.plugins.receivenotifications

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.desklink.mobile.NetworkPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeskNotificationTest {
    private fun packet(vararg fields: Pair<String, Any>) = NetworkPacket("desklink.notification").apply {
        fields.forEach { (k, v) -> when (v) {
            is String -> set(k, v)
            is Boolean -> set(k, v)
            is Long -> set(k, v)
            else -> error("unsupported")
        } }
    }

    @Test
    fun readsTheDeskForm() {
        val note = DeskNotification.parse(packet(
            "id" to "desk:42", "appName" to "Files", "title" to "Copy finished", "text" to "3 files",
            "time" to 1_700_000_000_000L, "isClearable" to false, "silent" to true,
        ))!!
        assertEquals("desk:42", note.id)
        assertEquals("Copy finished", note.title)
        assertEquals("3 files", note.text)
        assertEquals(1_700_000_000_000L, note.time)
        assertEquals(false, note.clearable)
        assertTrue(note.silent)
    }

    @Test
    fun readsTheKdeFormAndRefusesEmptyOnes() {
        val note = DeskNotification.parse(packet("id" to "7", "appName" to "Mail", "ticker" to "New mail"))!!
        assertEquals("Mail", note.title)
        assertEquals("New mail", note.text)
        assertNull(DeskNotification.parse(packet("appName" to "Mail", "ticker" to "x")))
        assertNull(DeskNotification.parse(packet("id" to "desk:1")))
        assertTrue(DeskNotification.parse(packet("id" to "desk:1", "isCancel" to true))!!.cancel)
    }

    @Test
    fun aSnapshotDropsOnlyThatDesksMissingOnes() {
        val posted = listOf("deskA/desk:1", "deskA/desk:2", "deskB/desk:1", "other")
        assertEquals(listOf("deskA/desk:2"), DeskNotification.stale(posted, "deskA", setOf("desk:1")))
        assertEquals(listOf("deskA/desk:1", "deskA/desk:2"), DeskNotification.stale(posted, "deskA", emptySet()))
    }
}
