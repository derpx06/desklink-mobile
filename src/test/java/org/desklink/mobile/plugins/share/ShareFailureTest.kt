/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.share

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.desklink.mobile.plugins.share.ShareFailure.Kind
import org.desklink.mobile.transport.TransportError
import org.desklink.mobile.transport.TransportErrorCode
import org.desklink.mobile.webrtc.TransferResumingException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShareFailureTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun onlyALinkThatWasNotReadyKeepsTheQueue() {
        assertEquals(Kind.RESUMING, ShareFailure.classify(TransferResumingException("wait"), cancelled = false))
        assertEquals(Kind.CANCELLED, ShareFailure.classify(IllegalStateException("x"), cancelled = true))
        assertEquals(Kind.NOT_READY, ShareFailure.classify(IllegalStateException("DeskLink WebRTC file transport is not ready"), false))
        assertEquals(Kind.NOT_READY, ShareFailure.classify(TransportError(TransportErrorCode.CLOSED, "closed"), false))
        assertEquals("a refusal", Kind.FAILED, ShareFailure.classify(IllegalStateException("The computer refused the file"), false))
        assertEquals(Kind.FAILED, ShareFailure.classify(null, false))
        assertTrue(ShareFailure.keepsEntries(Kind.NOT_READY))
        assertFalse(ShareFailure.keepsEntries(Kind.CANCELLED))
        assertFalse(ShareFailure.keepsEntries(Kind.FAILED))
    }

    @Test
    fun notReadyGivesUpAfterThreeTriesAndASuccessStartsOver() {
        ShareQueue.forgetDevice(app, "d")
        assertEquals(1, ShareQueue.notReady(app, "d"))
        assertEquals(2, ShareQueue.notReady(app, "d"))
        ShareQueue.sent(app, "d")
        assertEquals(1, ShareQueue.notReady(app, "d"))
        assertEquals(2, ShareQueue.notReady(app, "d"))
        assertEquals(ShareFailure.MAX_NOT_READY_ATTEMPTS, ShareQueue.notReady(app, "d"))
        ShareQueue.forgetDevice(app, "d")
        assertEquals("forgetting the desk resets the count", 1, ShareQueue.notReady(app, "d"))
    }
}
