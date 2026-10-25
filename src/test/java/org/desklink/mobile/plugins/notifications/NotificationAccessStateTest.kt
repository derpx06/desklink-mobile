/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationAccessStateTest {
    @Test
    fun theSnapshotSaysWhetherAndroidLetsThePhoneReadNotifications() {
        assertEquals(false, NotificationsPlugin.snapshotPacket(emptyList(), accessGranted = false).getBoolean("accessGranted", true))
        assertEquals(true, NotificationsPlugin.snapshotPacket(listOf("a"), accessGranted = true).getBoolean("accessGranted", false))
        assertFalse("an old phone says nothing", NotificationsPlugin.snapshotPacket(listOf("a")).has("accessGranted"))
        assertTrue(NotificationsPlugin.snapshotPacket(emptyList(), true).getBoolean("snapshot", false))
    }
}
