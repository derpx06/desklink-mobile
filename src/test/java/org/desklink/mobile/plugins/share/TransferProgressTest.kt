/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.plugins.share

import android.os.SystemClock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TransferProgressTest {
    @Test
    fun aSendPausedByALostLinkStaysOnHomeAsResumingNotFailed() {
        TransferProgress.update("d1", 42, true, "photo.jpg", 40, 1000)
        TransferProgress.resuming(42)
        val shown = TransferProgress.visibleFor("d1", now = SystemClock.elapsedRealtime() + 60_000)
        assertTrue(shown!!.resuming)
        assertFalse(shown.failed)
        assertFalse(shown.finished)
        // An ordinary finished one goes away after a few seconds.
        TransferProgress.update("d1", 43, true, "b.jpg", 100, 10)
        TransferProgress.finish(43, "b.jpg", false)
        assertNull(TransferProgress.visibleFor("d1", now = SystemClock.elapsedRealtime() + 60_000))
        TransferProgress.cancel(43)
    }
}
