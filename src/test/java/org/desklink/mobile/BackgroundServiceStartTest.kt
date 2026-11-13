/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile

import android.app.Application
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Android 12+ refuses a foreground-service start when the app is not in front at that instant (an activity starting behind the keyguard): that is not a crash. */
@RunWith(RobolectricTestRunner::class)
class BackgroundServiceStartTest {
    private val refusing = object : ContextWrapper(ApplicationProvider.getApplicationContext<Application>()) {
        override fun startForegroundService(service: Intent): ComponentName =
            throw IllegalStateException("startForegroundService() not allowed due to mAllowStartForeground false")
    }

    @Test
    fun aRefusedStartDoesNotThrow() {
        BackgroundService.Start(refusing)
        BackgroundService.ForceRefreshConnections(refusing)
    }
}
