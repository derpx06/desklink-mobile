package org.desklink.mobile.plugins.screen

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.desklink.mobile.Device
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `a refusal stops the automatic asking until the person asks again`() {
        plugin.onRemoteControlDenied("allow it on the computer")
        assertTrue(plugin.remoteControlRefused)
        plugin.askControlAgain()
        assertFalse(plugin.remoteControlRefused)
        plugin.onRemoteControlDenied(null)
        plugin.onRemoteControlGranted()
        assertFalse("a grant clears it", plugin.remoteControlRefused)
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

    /** The view attached its renderer twice per open: the first forwarder stayed on the track for good. */
    @Test
    fun `a renderer attached twice is on the track once and leaves it on detach`() {
        val track = mockk<org.webrtc.VideoTrack>(relaxed = true)
        every { track.id() } returns "desk"
        val renderer = mockk<org.webrtc.VideoSink>(relaxed = true)
        plugin.onRemoteVideoTrack(track)

        plugin.attachRemoteVideoSink(renderer)
        plugin.attachRemoteVideoSink(renderer)
        verify(exactly = 1) { track.addSink(any()) }

        plugin.detachRemoteVideoSink(renderer)
        val added = mutableListOf<org.webrtc.VideoSink>()
        val removed = mutableListOf<org.webrtc.VideoSink>()
        verify { track.addSink(capture(added)) }
        verify { track.removeSink(capture(removed)) }
        assertEquals("nothing left feeding a released renderer", added.toSet(), removed.toSet())
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
    fun `a refusal from the computer shows its own reason and ends the view`() {
        plugin.onRemoteScreenRequesting()
        plugin.onRemoteViewFailed("Screen sharing was declined on this computer")
        assertEquals("Screen sharing was declined on this computer", ScreenControlPlugin.latestStatus)
        assertFalse(plugin.remoteLive)
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

    /** Mirror and Extend share one gate: the buttons follow the grant, never the display mode. */
    @Test
    fun `left right keyboard and super follow the grant in mirror and extend alike`() {
        fun enabled(mode: org.desklink.mobile.plugins.mousepad.ControlMode) =
            org.desklink.mobile.plugins.mousepad.controlInputEnabled(plugin.remoteControlEnabled, mode)
        val touch = org.desklink.mobile.plugins.mousepad.ControlMode.Touch
        for (display in listOf(ScreenDisplay.MIRROR, ScreenDisplay.EXTEND)) {
            plugin.onRemoteScreenRequesting()
            plugin.onRemoteScreenReady(1920, 1080, display = display)
            assertEquals(display, plugin.remoteDisplay)
            assertFalse("$display: not requested", enabled(touch))
            plugin.onRemoteControlRequesting()
            assertFalse("$display: requested", enabled(touch))
            plugin.onRemoteControlDenied("view only")
            assertFalse("$display: denied", enabled(touch))
            assertEquals("view only", plugin.controlDenial)
            plugin.onRemoteControlGranted()
            assertTrue("$display: denied then granted", enabled(touch))
            assertNull("the notice's reason is gone with the grant", plugin.controlDenial)
            assertFalse("watch-only stays off", enabled(org.desklink.mobile.plugins.mousepad.ControlMode.View))
            plugin.onRemoteScreenReady(1280, 720, display = display)
            assertTrue("$display: a rotation's screen-ready keeps control", enabled(touch))
            plugin.onRemoteControlStopped()
            assertFalse("$display: granted then revoked", enabled(touch))
        }
    }

    @Test
    fun `a late or repeated denial neither undoes a grant nor repeats`() {
        plugin.onRemoteControlGranted()
        plugin.onRemoteControlDenied("answer to an earlier request")
        assertTrue(plugin.remoteControlEnabled)
        assertFalse(plugin.remoteControlRefused)
        assertNull(plugin.controlDenial)

        plugin.onRemoteControlStopped()
        plugin.onRemoteControlDenied("same")
        val shown = ScreenControlPlugin.latestStatus
        plugin.onRemoteControlDenied("same")
        assertEquals(shown, ScreenControlPlugin.latestStatus)
        assertEquals("same", plugin.controlDenial)
    }

    @Test
    fun `failing to ask is not a refusal and sticks to nothing`() {
        plugin.onRemoteControlUnavailable()
        assertFalse(plugin.remoteControlRefused)
        assertNull(plugin.controlDenial)
    }
}
