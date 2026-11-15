/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.desklink.mobile.Device
import org.desklink.mobile.session.DeviceManager
import org.desklink.mobile.session.PairingState
import org.desklink.mobile.transport.SessionTransport
import org.desklink.mobile.webrtc.terminal.TerminalClient
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * F42: the phone's sound starting held the coordinator lock in `setAudioRecording`, which waits for libwebrtc's signalling
 * thread; a heartbeat arriving on that thread then waited for the lock, and the app's main thread hung (ANR). The liveness
 * path must finish while another thread holds the lock.
 */
@RunWith(RobolectricTestRunner::class)
class LivenessLockTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun aHeartbeatIsHandledWhileTheCoordinatorLockIsHeld() {
        val device = mockk<Device>(relaxed = true)
        every { device.deviceId } returns "desk-liveness-lock"
        val sessions = DeviceManager()
        val binding = sessions.register("desk-liveness-lock", mockk<SessionTransport>(relaxed = true), PairingState.PAIRED).binding
        val coordinator = WebRtcSessionCoordinator(app, device, sessions, terminal = mockk<TerminalClient>(relaxed = true), onStateChanged = { _, _ -> })
        val lock = WebRtcSessionCoordinator::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(coordinator)!!

        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread {
            synchronized(lock) {
                held.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }.apply { start() }
        held.await(5, TimeUnit.SECONDS)

        val handled = CountDownLatch(1)
        Thread {
            coordinator.onLivenessEnvelope(binding, mockk(relaxed = true))
            handled.countDown()
        }.start()
        val done = handled.await(2, TimeUnit.SECONDS)
        release.countDown()
        holder.join(5_000)
        assertTrue("the liveness path waited for the coordinator lock", done)
    }
}
