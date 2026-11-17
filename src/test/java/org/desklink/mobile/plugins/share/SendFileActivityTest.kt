/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.share

import android.content.ClipData
import android.net.Uri
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The notification's "Send files": the picker's read grant lives as long as this activity, and a picker never opens twice. */
@RunWith(RobolectricTestRunner::class)
class SendFileActivityTest {
    private val a = Uri.parse("content://picker/a")
    private val b = Uri.parse("content://picker/b")

    private fun clip(vararg uris: Uri) = ClipData("files", arrayOf("*/*"), ClipData.Item(uris.first())).also { clip ->
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
    }

    /** A picker that returns the first file as `data` and again in the clip sent it twice. */
    @Test
    fun aFileInDataAndInTheClipIsSentOnce() {
        assertEquals(listOf(a, b), SendFileActivity.uniqueUris(a, clip(a, b)))
        assertEquals(listOf(a), SendFileActivity.uniqueUris(null, clip(a, a)))
        assertEquals(listOf(a), SendFileActivity.uniqueUris(a, null))
        assertEquals(emptyList<Uri>(), SendFileActivity.uniqueUris(null, null))
    }

    /** A rotation or recreation while the picker is up must not launch a second picker. */
    @Test
    fun theChooserIsLaunchedOnlyForAFreshActivity() {
        val intent = android.content.Intent().putExtra("deviceId", "desk")
        val controller = Robolectric.buildActivity(SendFileActivity::class.java, intent).create()
        assertNotNull("a fresh activity opens the picker", shadowOf(controller.get()).nextStartedActivityForResult)
        val recreated = Robolectric.buildActivity(SendFileActivity::class.java, intent).create(Bundle())
        assertNull("a recreated one does not", shadowOf(recreated.get()).nextStartedActivityForResult)
    }

    /** The activity finishes when the reads are done, not at once: finishing ends the grant the reads run under. */
    @Test
    fun theActivityStaysUntilTheReadsAreDone() {
        val intent = android.content.Intent().putExtra("deviceId", "desk")
        val activity = Robolectric.buildActivity(SendFileActivity::class.java, intent).create().get()
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val release = java.util.concurrent.CountDownLatch(1)
        SendFileActivity.sendThenFinish(activity, java.util.concurrent.Executor { it.run() }) {
            assertEquals("still alive while the reads run", false, activity.isFinishing)
            release.countDown()
            finished.set(true)
        }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(true, finished.get())
        assertEquals("finished after them", true, activity.isFinishing)
    }
}
