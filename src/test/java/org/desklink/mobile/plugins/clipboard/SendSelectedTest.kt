/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.clipboard

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SendSelectedTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun processText(text: CharSequence?, action: String = Intent.ACTION_PROCESS_TEXT) =
        Intent(action).setType("text/plain").putExtra(Intent.EXTRA_PROCESS_TEXT, text).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)

    @Test
    fun onlyTheSelectionOfAProcessTextIntentIsTaken() {
        assertEquals("hello world", SendSelected.textOf(processText("hello world")))
        assertNull("another action carrying the extra", SendSelected.textOf(processText("x", Intent.ACTION_SEND)))
        assertNull("a blank selection", SendSelected.textOf(processText("  \n")))
        assertNull("no selection", SendSelected.textOf(Intent(Intent.ACTION_PROCESS_TEXT)))
    }

    @Test
    fun theSheetDependsOnHowManyComputersCanTakeIt() {
        assertEquals(SendSelected.Plan.NO_TEXT, SendSelected.plan(null, 2))
        assertEquals(SendSelected.Plan.NO_COMPUTER, SendSelected.plan("a", 0))
        assertEquals(SendSelected.Plan.ONE, SendSelected.plan("a", 1))
        assertEquals(SendSelected.Plan.CHOOSE, SendSelected.plan("a", 2))
    }

    @Test
    fun theTextSelectionMenuReachesTheActivityAndNothingElseOfOursTakesProcessText() {
        val found = app.packageManager.queryIntentActivities(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"), 0)
            .filter { it.activityInfo.packageName == app.packageName }
        assertEquals(listOf(SendSelectedTextActivity::class.java.name), found.map { it.activityInfo.name })
        assertTrue(found.single().activityInfo.exported)
    }

    @Test
    fun aSelectionIsStampedLikeACopyAndNeverBehindOneAlreadySent() {
        val listener = ClipboardListener.instance(app)
        val first = listener.nextCopyTime()
        val second = listener.nextCopyTime()
        assertTrue(second >= first)
        assertEquals(second, listener.lastCopiedAtSent)
    }
}
