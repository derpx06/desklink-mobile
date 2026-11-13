/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.notifications

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.desklink.mobile.Device
import org.desklink.mobile.NetworkPacket
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** A desk dismisses only what this phone mirrored to it, never another app's notification by a guessed key. */
@RunWith(RobolectricTestRunner::class)
class NotificationsPluginDismissTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun plugin(): NotificationsPlugin {
        val device = mockk<Device>(relaxed = true) { every { deviceId } returns "desk-dismiss-1" }
        return NotificationsPlugin().apply { setContext(app, device) }
    }

    private fun cancel(key: String) = NetworkPacket("desklink.notification.request").apply { this["cancel"] = key }

    @Test
    fun aKeyThatWasNeverMirroredIsNotCancelled() {
        shadowOf(app).clearStartedServices()
        plugin().onPacketReceived(cancel("0|com.bank|1|null|10123"))
        assertNull("the listener is not asked to cancel anything", shadowOf(app).nextStartedService)
    }

    @Test
    fun aMirroredKeyIsCancelled() {
        shadowOf(app).clearStartedServices()
        val p = plugin()
        p.currentNotifications.add("0|com.chat|1|null|10123")
        p.onPacketReceived(cancel("0|com.chat|1|null|10123"))
        assertNotNull(shadowOf(app).nextStartedService)
    }
}
