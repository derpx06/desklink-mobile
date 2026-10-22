package org.desklink.mobile.helpers

import android.app.Application
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
class NotificationChannelsTest {
    @Test
    fun onlyChannelsNothingCreatesAreDeleted() {
        val keep = setOf("persistent", "requests")
        assertTrue(NotificationHelper.deprecated("old_channel", keep))
        assertFalse(NotificationHelper.deprecated("persistent", keep))
        // A running foreground service holds this one: deleting it threw and stopped the app starting.
        assertFalse(NotificationHelper.deprecated("desklink-screen-capture", keep))
        // Each desk's "From <desk>" channel keeps the person's sound/importance choices.
        assertFalse(NotificationHelper.deprecated("desk_notifications_abc123", keep))
        assertFalse(NotificationHelper.deprecated("miscellaneous", keep))
    }

    @Test
    fun oneChannelPerPurposeEachWithItsOwnHonestName() {
        val context: Application = ApplicationProvider.getApplicationContext()
        NotificationHelper.initializeChannels(context)
        val channels = NotificationManagerCompat.from(context).notificationChannelsCompat.filter { it.id in NotificationHelper.ALL_CHANNEL_IDS }
        assertEquals(NotificationHelper.ALL_CHANNEL_IDS.sorted(), channels.map { it.id }.sorted())
        val names = channels.map { it.name.toString() }
        assertEquals("two channels share a name: $names", names.size, names.toSet().size)
        assertTrue("no channel is called 'High priority'", names.none { it.contains("High priority", ignoreCase = true) })
        assertTrue(names.all { it.isNotBlank() })
    }

    @Test
    fun theOldMisusedChannelsAreDeletedAtStart() {
        val keep = NotificationHelper.ALL_CHANNEL_IDS.toSet()
        listOf("highpriority", "default", "receive").forEach { assertTrue(it, NotificationHelper.deprecated(it, keep)) }
        NotificationHelper.ALL_CHANNEL_IDS.forEach { assertFalse(it, NotificationHelper.deprecated(it, keep)) }
    }

    @Test
    fun theOldChannelsAreGoneFromTheSystemAfterAnUpgrade() {
        val context: Application = ApplicationProvider.getApplicationContext()
        val nm = NotificationManagerCompat.from(context)
        nm.createNotificationChannel(androidx.core.app.NotificationChannelCompat.Builder("highpriority", NotificationManagerCompat.IMPORTANCE_HIGH).setName("Pairing requests").build())
        NotificationHelper.initializeChannels(context)
        assertTrue(nm.notificationChannelsCompat.none { it.id == "highpriority" })
    }
}
