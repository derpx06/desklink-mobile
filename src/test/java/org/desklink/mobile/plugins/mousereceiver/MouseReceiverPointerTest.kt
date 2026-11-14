/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.mousereceiver

import android.os.Looper
import android.view.View
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/** The service's pointer: moved by the sum of the desk's deltas, drawn on frames, and a hide is never undone by a late frame. */
@RunWith(RobolectricTestRunner::class)
class MouseReceiverPointerTest {
    private lateinit var service: MouseReceiverService

    @Before
    fun connect() {
        service = Robolectric.buildService(MouseReceiverService::class.java).create().get()
        MouseReceiverService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service)
    }

    @After
    fun clean() {
        MouseReceiverService.instance = null
    }

    private fun cursor() = MouseReceiverService::class.java.getDeclaredField("cursorView").apply { isAccessible = true }.get(service) as View

    private fun frames() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

    @Test
    fun theDesksFractionalDeltasAddUp() {
        MouseReceiverService.setPos(200, 200)
        repeat(8) { MouseReceiverService.move(0.25, -0.125) }
        assertEquals(202, service.x)
        assertEquals(199, service.y)
    }

    @Test
    fun aMoveShowsThePointerOnTheNextFrame() {
        MouseReceiverService.setPos(300, 300)
        frames()
        assertEquals(View.VISIBLE, cursor().visibility)
    }

    @Test
    fun aHideIsNotUndoneByAFrameAskedForBeforeIt() {
        MouseReceiverService.setPos(300, 300)
        repeat(10) { MouseReceiverService.move(2.0, 0.0) } // a frame is asked for, not drawn yet
        service.hide(0) // the glide ended
        frames()
        assertEquals(View.GONE, cursor().visibility)
    }
}
