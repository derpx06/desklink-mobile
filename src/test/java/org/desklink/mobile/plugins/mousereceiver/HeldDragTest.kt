/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.mousereceiver

import io.mockk.mockk
import org.desklink.mobile.Device
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import android.app.Application
import androidx.test.core.app.ApplicationProvider

/** A drag the desk was holding must never outlive its session: no finger left down for the next pointer move to drag. */
@RunWith(RobolectricTestRunner::class)
class HeldDragTest {
    private lateinit var service: MouseReceiverService

    @Before
    fun connect() {
        service = Robolectric.buildService(MouseReceiverService::class.java).create().get()
        MouseReceiverService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service)
    }

    @After
    fun clean() { MouseReceiverService.instance = null }

    private fun holding(): Boolean = MouseReceiverService::class.java.getDeclaredMethod("isSwiping").apply { isAccessible = true }.invoke(service) as Boolean

    @Test
    fun releaseHeldLetsGoOfADragInProgress() {
        MouseReceiverService.longClickSwipe(20, 30)
        assertTrue("a drag is held", holding())
        MouseReceiverService.releaseHeld()
        assertFalse("and it is let go", holding())
        assertTrue("the next session can start a drag", MouseReceiverService.longClickSwipe(40, 50) || holding())
    }

    @Test
    fun theLinkGoingAwayOrThePluginBeingUnloadedLetsGo() {
        val plugin = MouseReceiverPlugin().also { it.setContext(ApplicationProvider.getApplicationContext<Application>(), mockk<Device>(relaxed = true)) }
        MouseReceiverService.longClickSwipe(20, 30)
        plugin.onFeatureLost()
        assertFalse(holding())
        MouseReceiverService.longClickSwipe(20, 30)
        plugin.onDestroy()
        assertFalse(holding())
    }

    @Test
    fun withNoServiceThereIsNothingToLetGo() {
        MouseReceiverService.instance = null
        MouseReceiverService.releaseHeld()
    }
}
