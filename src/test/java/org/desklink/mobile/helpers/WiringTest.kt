/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.helpers

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Wiring that nothing exercised: the stale alarm, the one-time cleanup marker, the app-icon shortcuts. */
@RunWith(RobolectricTestRunner::class)
class WiringTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun theStaleAlarmOfTheRemovedAutomationsIsCancelled() {
        // The application's own start-up thread also runs runOnce(): the lock keeps it from cancelling the alarm under this test.
        synchronized(RemovedFeatures) { staleAlarm() }
    }

    private fun staleAlarm() {
        val intent = Intent().setClassName(app.packageName, RemovedFeatures.ALARM_RECEIVER).setAction(RemovedFeatures.ALARM_ACTION)
        val pending = PendingIntent.getBroadcast(app, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        app.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, System.currentTimeMillis() + 3_600_000, pending)
        assertNotNull("set", PendingIntent.getBroadcast(app, 0, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE))
        app.getSharedPreferences(RemovedFeatures.MARKER_PREFS, 0).edit().clear().commit()
        RemovedFeatures.runOnce(app)
        assertNull("cancelled", PendingIntent.getBroadcast(app, 0, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE))
    }

    @Test
    fun everyStaticShortcutPointsAtAnActivityThatExists() {
        fun xml(path: String) = listOf(File(path), File("desklink-mobile/$path")).first { it.exists() }.readText()
        for (file in listOf("src/main/res/xml/shortcuts.xml", "src/debug/res/xml/shortcuts.xml")) {
            val text = xml(file)
            val classes = Regex("""android:targetClass="([^"]+)"""").findAll(text).map { it.groupValues[1] }.toList()
            assertTrue("$file has targets", classes.isNotEmpty())
            classes.forEach { assertTrue("$it exists", runCatching { Class.forName(it) }.isSuccess) }
        }
        assertTrue("the Send clipboard shortcut", xml("src/main/res/xml/shortcuts.xml").contains("org.desklink.mobile.action.SEND_CLIPBOARD"))
        assertEquals("the activity honours that action", "org.desklink.mobile.action.SEND_CLIPBOARD",
            Regex("""ACTION_SEND_CLIPBOARD = "([^"]+)"""").find(xml("src/main/java/org/desklink/mobile/plugins/clipboard/ClipboardFloatingActivity.java"))!!.groupValues[1])
    }
}
