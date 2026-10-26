package org.desklink.mobile.plugins

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.desklink.mobile.plugins.clipboard.ClipboardListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper

@RunWith(AndroidJUnit4::class)
class ClipboardListenerTest {
    private lateinit var context: Application
    private lateinit var listener: ClipboardListener
    private val sent = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** The fan-out to the desks runs off the main thread: wait for it. */
    private fun awaitSent(count: Int) {
        val end = System.currentTimeMillis() + 5_000
        while (sent.size < count && System.currentTimeMillis() < end) Thread.sleep(5)
    }
    private val observer = object : ClipboardListener.ClipboardObserver {
        override fun clipboardChanged(content: String) { sent += content }
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        listener = ClipboardListener.instance(context)
        listener.resetPacing()
        listener.registerObserver(observer)
        ShadowLooper.idleMainLooper()
    }

    @After
    fun cleanup() = listener.removeObserver(observer)

    /** Another app copies; the change listener may or may not be wired in this test's Robolectric app. */
    private fun copyElsewhere(text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("other app", text))
        ShadowLooper.idleMainLooper()
        listener.sync(force = false)
    }

    @Test
    fun aPhoneCopyGoesOutOnce() {
        copyElsewhere("from the phone")
        awaitSent(1)
        assertEquals(listOf("from the phone"), sent)
        // Refocusing DeskLink with the same copy sends nothing more.
        assertFalse(listener.sync(force = false))
        assertEquals(1, sent.size)
    }

    @Test
    fun theDesksCopyNeverBouncesBack() {
        copyElsewhere("an older phone copy")
        sent.clear()
        listener.setText("from the desk")
        ShadowLooper.idleMainLooper()
        assertFalse(listener.sync(force = false))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun anExplicitSendGoesEvenWhenUnchanged() {
        listener.setText("from the desk")
        ShadowLooper.idleMainLooper()
        assertTrue(listener.sync(force = true))
        awaitSent(1)
        assertEquals(listOf("from the desk"), sent)
    }

    @Test
    fun theLogWatchMatchesOnlyThisPackage() {
        val line = "E ClipboardService: Denying clipboard access to org.desklink.mobile, application is not in focus"
        assertTrue(ClipboardListener.deniesPackage(line, "org.desklink.mobile"))
        assertFalse(ClipboardListener.deniesPackage(line, "org.desklink.mobile.debug"))
        val debug = "E ClipboardService: Denying clipboard access to org.desklink.mobile.debug, application is not in focus"
        assertTrue(ClipboardListener.deniesPackage(debug, "org.desklink.mobile.debug"))
        assertFalse(ClipboardListener.deniesPackage(debug, "org.desklink.mobile"))
    }

    /** A cold start (or a grant made over adb) must never start logcat: Android 13+ would ask about device logs. */
    @Test
    fun theLogWatchStartsOnlyWhenThePersonAsksForIt() {
        val q = android.os.Build.VERSION_CODES.Q
        assertFalse("a granted READ_LOGS alone is not a request", ClipboardListener.shouldWatch(34, readLogsGranted = true, requestedByPerson = false))
        assertTrue(ClipboardListener.shouldWatch(34, readLogsGranted = true, requestedByPerson = true))
        assertFalse("without the grant nothing runs and nothing is asked", ClipboardListener.shouldWatch(34, readLogsGranted = false, requestedByPerson = true))
        assertFalse("below Android 10 there is nothing to watch", ClipboardListener.shouldWatch(q - 1, readLogsGranted = true, requestedByPerson = true))
        assertFalse("a new listener is not watching", listener.watching)
    }

    @Test
    fun aCopyTheListenerCouldNotReadStillProtectsItFromTheDesksOlderCopy() {
        val before = listener.newestLocalCopy()
        Thread.sleep(5)
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("other app", "copied in the background"))
        ShadowLooper.idleMainLooper()
        listener.clipChangedForTest() // the system's change callback
        assertTrue("a copy newer than the desk's reconnect packet wins", listener.newestLocalCopy() > before)
    }
}
