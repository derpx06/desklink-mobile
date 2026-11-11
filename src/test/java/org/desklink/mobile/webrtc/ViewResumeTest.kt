package org.desklink.mobile.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewResumeTest {
    private val resumes = WebRtcSessionCoordinator.Companion::resumesAfterLinkLoss

    @Test
    fun aViewAskedForOrShownIsAskedForAgainWhenTheLinkReturns() {
        for (state in listOf(
            WebRtcRemoteSessionState.REQUESTING_VIEW,
            WebRtcRemoteSessionState.VIEWING,
            WebRtcRemoteSessionState.REQUESTING_CONTROL,
            WebRtcRemoteSessionState.CONTROLLING,
            WebRtcRemoteSessionState.PAUSED_LOCKED,
            WebRtcRemoteSessionState.RECONNECTING,
        )) {
            assertTrue("$state", resumes(WebRtcScreenDirection.DESKTOP_TO_PHONE, state))
        }
    }

    @Test
    fun nothingElseIsAskedForAgain() {
        assertFalse(resumes(WebRtcScreenDirection.PHONE_TO_DESKTOP, WebRtcRemoteSessionState.REQUESTING_VIEW))
        assertFalse(resumes(null, WebRtcRemoteSessionState.VIEWING))
        for (state in listOf(WebRtcRemoteSessionState.IDLE, WebRtcRemoteSessionState.FAILED)) {
            assertFalse("$state", resumes(WebRtcScreenDirection.DESKTOP_TO_PHONE, state))
        }
    }

    @Test
    fun aFailedRequestIsAskedAgainOnlyWhenThereWasNoUsableLink() {
        val f = WebRtcSessionCoordinator.Companion::resumesAfterViewFailure
        assertTrue(f(true, false))
        assertFalse("an error on a link that is up is not a missing link", f(true, true))
        assertFalse("another kind of error", f(false, false))
    }

    private val source = java.io.File("src/main/java/org/desklink/mobile/webrtc/WebRtcSessionCoordinator.kt").readText()

    @Test
    fun closingTheViewerClearsTheReaskInsideTheLockAfterTheSessionIsStopped() {
        val stop = source.substringAfter("fun stopRemoteSession()").substringBefore("override fun onControlPacket")
        val tail = stop.substringAfter("stopPhoneScreenCapture()")
        assertTrue(Regex("""finally\s*\{[^}]*resumeScreen\.set\(false\)""").containsMatchIn(tail))
        assertEquals(1, Regex("""synchronized\(lock\)""").findAll(stop).count())
    }

    /** F7: the desk sends nothing back for the phone's own Release, so ending the view here must end it in the UI. */
    @Test
    fun endingTheViewHereClearsTheLiveViewAndRefreshesItsSurfaces() {
        val stop = source.substringAfter("fun stopRemoteSession()").substringBefore("override fun onControlPacket")
        val now = stop.substringBefore("remoteActionExecutor.execute")
        assertTrue("on the caller's thread", now.contains("getPlugin(ScreenControlPlugin::class.java)?.onRemoteSessionEnded()"))
        assertTrue("Home and the live notification redraw", now.contains("publish("))
    }
}
