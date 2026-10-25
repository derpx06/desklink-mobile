/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.findmyphone

import android.app.Application
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RingVolumeTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun aVolumeLeftByARingThatDiedIsPutBackAtTheNextStart() {
        RingVolume.remember(app, 3)
        assertEquals(3, RingVolume.pending(app))
        val audio = mockk<AudioManager>(relaxed = true)
        RingVolume.restoreIfLeft(app, audio)
        verify { audio.setStreamVolume(AudioManager.STREAM_ALARM, 3, 0) }
        assertNull("only once", RingVolume.pending(app))
    }

    @Test
    fun aRingThatEndedNormallyLeavesNothingToRestore() {
        RingVolume.remember(app, 5)
        RingVolume.forget(app)
        val audio = mockk<AudioManager>(relaxed = true)
        RingVolume.restoreIfLeft(app, audio)
        verify(exactly = 0) { audio.setStreamVolume(any(), any(), any()) }
    }

    @Test
    fun aRingEndsByItselfAfterAMinute() {
        assertEquals(60_000L, RingVolume.RING_LIMIT_MS)
        assertTrue(RingVolume.RING_LIMIT_MS <= 5 * 60_000L)
    }
}
