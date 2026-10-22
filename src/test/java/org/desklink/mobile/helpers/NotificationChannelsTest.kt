package org.desklink.mobile.helpers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationChannelsTest {
    @Test
    fun onlyChannelsNothingCreatesAreDeleted() {
        val keep = setOf("persistent", "highpriority")
        assertTrue(NotificationHelper.deprecated("old_channel", keep))
        assertFalse(NotificationHelper.deprecated("persistent", keep))
        // A running foreground service holds this one: deleting it threw and stopped the app starting.
        assertFalse(NotificationHelper.deprecated("desklink-screen-capture", keep))
        // Each desk's "From <desk>" channel keeps the person's sound/importance choices.
        assertFalse(NotificationHelper.deprecated("desk_notifications_abc123", keep))
        assertFalse(NotificationHelper.deprecated("miscellaneous", keep))
    }
}
