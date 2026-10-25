/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.share

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.desklink.mobile.Device
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.async.BackgroundJob
import org.desklink.mobile.async.BackgroundJobHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The whole upload job against a fake link: which failures leave files queued for the next feature-ready, and which end them. */
@RunWith(RobolectricTestRunner::class)
class CompositeUploadFileJobTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val uris = (1..3).map { "content://other.app/f$it" }

    private fun run(failWith: Throwable?, fresh: Boolean = true): Pair<CompositeUploadFileJob, Int> {
        if (fresh) ShareQueue.forgetDevice(app, "desk")
        ShareQueue.add(app, "desk", uris)
        var sent = 0
        val device = mockk<Device>(relaxed = true) {
            every { context } returns app
            every { deviceId } returns "desk"
            every { name } returns "Desk"
            every { sendPacketBlocking(any(), any(), any()) } answers {
                val callback = secondArg<Device.SendPacketStatusCallback>()
                if (failWith != null && sent == 0) {
                    callback.onFailure(failWith)
                    false
                } else {
                    sent++
                    callback.onSuccess()
                    true
                }
            }
        }
        val callback = object : BackgroundJob.Callback<Void?> {
            override fun onResult(job: BackgroundJob<*, *>, result: Void?) = Unit
            override fun onError(job: BackgroundJob<*, *>, error: Throwable) = Unit
        }
        val job = CompositeUploadFileJob(device, callback)
        job.setBackgroundJobHandler(mockk<BackgroundJobHandler>(relaxed = true))
        uris.forEach { text ->
            val packet = NetworkPacket("desklink.share.request")
            packet["filename"] = text.substringAfterLast('/')
            TransferHistory.rememberSource(packet, Uri.parse(text))
            job.addNetworkPacket(packet)
        }
        job.run()
        return job to sent
    }

    @Test
    fun aLinkThatIsNotReadyKeepsEveryFileForTheNextFeatureReady() {
        run(IllegalStateException("DeskLink WebRTC file transport is not ready"))
        assertEquals(uris, ShareQueue.pending(app, "desk"))
    }

    @Test
    fun aRefusalEndsTheShareAndNothingIsSentAgainByItself() {
        run(IllegalStateException("The computer refused the file"))
        assertEquals(emptyList<String>(), ShareQueue.pending(app, "desk"))
    }

    @Test
    fun aSuccessfulShareLeavesNothingQueued() {
        val (_, sent) = run(null)
        assertEquals(3, sent)
        assertTrue(ShareQueue.pending(app, "desk").isEmpty())
    }

    @Test
    fun theThirdNotReadyInARowGivesUpWithOneCard() {
        ShareQueue.forgetDevice(app, "desk")
        repeat(2) { ShareQueue.notReady(app, "desk") }
        run(IllegalStateException("DeskLink WebRTC file transport is not ready"), fresh = false) // the third
        assertTrue("the entries are dropped", ShareQueue.pending(app, "desk").isEmpty())
        assertEquals(1, SendAgain.list(app).count { it.deviceId == "desk" })
    }
}
