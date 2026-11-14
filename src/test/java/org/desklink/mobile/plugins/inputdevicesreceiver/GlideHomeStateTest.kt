package org.desklink.mobile.plugins.inputdevicesreceiver

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.desklink.mobile.plugins.desksettings.Asks
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What Home's Glide row reads: a pending ask (from the one list of asks) and a refusal while locked. */
@RunWith(RobolectricTestRunner::class)
class GlideHomeStateTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun clear() {
        Asks.forget(app, "desk-ask", Glide.SETTING_KEY)
        Glide.setAllowed(app, "desk-ask", false)
        Glide.clearNotices("x")
    }

    @Test
    fun anAskIsShownOnHomeUntilAnswered() {
        assertFalse(Glide.asking("desk-ask"))
        Asks.onAsked(app, "desk-ask", "Workbench", Asks.Feature.of(Glide.SETTING_KEY)!!, on = false)
        assertTrue("Home's Glide row offers Allow", Glide.asking("desk-ask"))
        assertFalse("another desk is not asking", Glide.asking("other"))
        Glide.setAllowed(app, "desk-ask", true)
        assertFalse("turning it on answers it", Glide.asking("desk-ask"))
    }

    @Test
    fun aLockedRefusalFadesAndTheNextGlideClearsIt() {
        Glide.markRefusedLocked("x", now = 1_000L)
        assertTrue(Glide.refusedLocked("x", now = 1_000L + Glide.NOTICE_MS - 1))
        assertFalse(Glide.refusedLocked("x", now = 1_000L + Glide.NOTICE_MS))
        Glide.markRefusedLocked("x", now = 1_000L)
        Glide.begin("x", Glide.TOP_EDGE, null, now = 2_000L)
        Glide.end()
        assertFalse(Glide.refusedLocked("x", now = 2_000L))
    }
}
