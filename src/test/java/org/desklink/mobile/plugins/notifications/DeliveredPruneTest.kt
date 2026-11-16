/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

/** What a desk was sent is remembered per notification; a snapshot forgets the ones that are gone (they were kept forever). */
class DeliveredPruneTest {
    @Test
    fun aSnapshotForgetsWhatTheDeskWasSentForGoneNotifications() {
        val desk = "prune-desk"
        NotificationsPlugin.deliveredFor(desk).apply { clear(); put("old", "sig-old"); put("new", "sig-new") }
        NotificationsPlugin.forgetGone(desk, listOf("new"))
        assertEquals(setOf("new"), NotificationsPlugin.deliveredFor(desk).keys)
    }
}
