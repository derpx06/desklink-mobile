package org.desklink.mobile.plugins.screen

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.desklink.mobile.Device
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The state the screen UI maps touches through. A tap is sent only when the
 * desktop has reported its own coordinate space, and none of that state may
 * outlive the view it belongs to.
 */
class ScreenControlPluginRemoteViewTest {
    private lateinit var plugin: ScreenControlPlugin

    @Before
    fun setUp() {
        plugin = ScreenControlPlugin()
        val context = mockk<Context> {
            every { getSharedPreferences(any(), any()) } returns mockk<SharedPreferences>()
            every { getString(any()) } returns "status"
            every { getString(any(), any()) } returns "status"
        }
        val device = mockk<Device> { every { deviceId } returns "device-id" }
        plugin.setContext(context, device)
    }

    @Test
    fun `no geometry is known until the desktop reports it`() {
        assertEquals(0, plugin.remoteScreenWidth)
        assertEquals(0, plugin.remoteScreenHeight)
        assertFalse(plugin.remoteLive)
    }

    @Test
    fun `the desktop's reported size is kept for mapping`() {
        plugin.onRemoteScreenReady(1920, 1080)

        assertEquals(1920, plugin.remoteScreenWidth)
        assertEquals(1080, plugin.remoteScreenHeight)
        assertFalse("a report is not a picture", plugin.remoteLive)
    }

    @Test
    fun `a report without a size leaves what was known alone`() {
        plugin.onRemoteScreenReady(1920, 1080)
        plugin.onRemoteScreenReady(null, null)

        assertEquals(1920, plugin.remoteScreenWidth)
    }

    @Test
    fun `a new request forgets the previous desktop's geometry and control`() {
        plugin.onRemoteScreenReady(1920, 1080)
        plugin.onRemoteControlGranted()
        assertTrue(plugin.remoteControlEnabled)

        plugin.onRemoteScreenRequesting()

        assertEquals(0, plugin.remoteScreenWidth)
        assertFalse(plugin.remoteControlEnabled)
    }

    @Test
    fun `ending the session clears geometry and control`() {
        plugin.onRemoteScreenReady(2560, 1440)
        plugin.onRemoteControlGranted()

        plugin.onRemoteSessionEnded()

        assertEquals(0, plugin.remoteScreenWidth)
        assertEquals(0, plugin.remoteScreenHeight)
        assertFalse(plugin.remoteControlEnabled)
        assertFalse(plugin.remoteLive)
    }

    @Test
    fun `a failed view is an ended view`() {
        plugin.onRemoteScreenReady(1920, 1080)
        plugin.onRemoteViewFailed("Desktop screen/control permission was revoked locally")

        assertEquals(0, plugin.remoteScreenWidth)
        assertFalse(plugin.remoteControlEnabled)
    }

    /** A control request nobody answered must not take the picture with it. */
    @Test
    fun `a control timeout keeps the view and its geometry`() {
        plugin.onRemoteScreenReady(1920, 1080)
        plugin.onRemoteControlRequesting()

        plugin.onRemoteRequestTimedOut(control = true)

        assertEquals(1920, plugin.remoteScreenWidth)
        assertFalse(plugin.remoteControlEnabled)
    }

    @Test
    fun `a view timeout ends the view`() {
        plugin.onRemoteScreenRequesting()
        plugin.onRemoteRequestTimedOut(control = false)

        assertEquals(0, plugin.remoteScreenWidth)
        assertFalse(plugin.remoteLive)
    }

    @Test
    fun `a pause revokes control but the view stays`() {
        plugin.onRemoteScreenReady(1920, 1080)
        plugin.onRemoteControlGranted()

        plugin.onRemotePaused()

        assertFalse(plugin.remoteControlEnabled)
        assertEquals(1920, plugin.remoteScreenWidth)
    }
}
