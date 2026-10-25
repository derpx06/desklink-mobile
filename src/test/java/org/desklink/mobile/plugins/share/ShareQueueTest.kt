/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.share

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShareQueueTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun theFilesAfterTheOneInFlightSurviveADropAndAreTakenOffOneByOne() {
        ShareQueue.forgetDevice(app, "d")
        ShareQueue.add(app, "d", listOf("content://a/1", "content://a/2", "content://a/3"))
        ShareQueue.remove(app, "d", "content://a/1") // sent, or handed to the transfer manager
        assertEquals(listOf("content://a/2", "content://a/3"), ShareQueue.pending(app, "d")) // a new process sees the same
        assertEquals("a repeated share does not double an entry", 0, ShareQueue.add(app, "d", listOf("content://a/2")))
        assertEquals(2, ShareQueue.pending(app, "d").size)
    }

    @Test
    fun theQueueIsBoundedAndForgottenWithTheDesk() {
        ShareQueue.forgetDevice(app, "e")
        val tooMany = (1..ShareQueue.MAX_PER_DEVICE + 5).map { "content://a/$it" }
        assertEquals(5, ShareQueue.add(app, "e", tooMany))
        assertEquals(ShareQueue.MAX_PER_DEVICE, ShareQueue.pending(app, "e").size)
        ShareQueue.forgetDevice(app, "e")
        assertEquals(emptyList<String>(), ShareQueue.pending(app, "e"))
    }
}
