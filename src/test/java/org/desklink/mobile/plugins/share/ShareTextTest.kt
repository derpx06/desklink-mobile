/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.share

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import org.desklink.mobile.Device
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.plugins.clipboard.ClipboardListener
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Text a desk shares lands on the clipboard as the desk's copy (never offered back to every desk), and a blank never empties it. */
@RunWith(RobolectricTestRunner::class)
class ShareTextTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun cleanup() = unmockkAll()

    @Test
    fun sharedTextGoesThroughTheListenerAndABlankIsIgnored() {
        val listener = spyk(ClipboardListener.instance(app))
        mockkObject(ClipboardListener.Companion)
        every { ClipboardListener.instance(any()) } returns listener
        val device = mockk<Device>(relaxed = true) { every { deviceId } returns "desk-share-text" }
        val plugin = SharePlugin().apply { setContext(app, device) }

        plugin.onPacketReceived(NetworkPacket("desklink.share.request").apply { this["text"] = "hello" })
        plugin.onPacketReceived(NetworkPacket("desklink.share.request").apply { this["text"] = "" })

        verify(exactly = 1) { listener.setText("hello") }
        verify(exactly = 0) { listener.setText("") }
    }
}
