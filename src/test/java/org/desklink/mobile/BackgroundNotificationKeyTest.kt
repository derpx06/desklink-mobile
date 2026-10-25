/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackgroundNotificationKeyTest {
    @Test
    fun theForegroundNotificationIsOnlyRepostedWhenWhatItSaysChanges() {
        val a = androidx.core.app.NotificationCompat.Builder(androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>(), "c")
            .setContentTitle("Connected").setContentText("Laptop").build()
        val same = androidx.core.app.NotificationCompat.Builder(androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>(), "c")
            .setContentTitle("Connected").setContentText("Laptop").setWhen(5).build()
        val other = androidx.core.app.NotificationCompat.Builder(androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>(), "c")
            .setContentTitle("Connected").setContentText("Laptop, Desk").build()
        assertEquals(BackgroundService.visibleKey(a), BackgroundService.visibleKey(same))
        assertFalse(BackgroundService.visibleKey(a) == BackgroundService.visibleKey(other))
    }
}
