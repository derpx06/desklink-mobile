/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.share

import android.content.Intent
import org.desklink.mobile.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** A share that cannot be read is a failure the activity can tell the person about, never a silent return to Home. */
@RunWith(RobolectricTestRunner::class)
class ShareActivityFailureTest {
    private val intent = Intent(Intent.ACTION_SEND)

    @Test fun aShareThatWasHandedOnIsNotAFailure() = assertTrue(ShareActivity.runShare({ true }, intent))

    @Test fun nothingToShareIsAFailure() = assertFalse(ShareActivity.runShare({ false }, intent))

    @Test fun aRevokedGrantIsAFailureNotACrash() = assertFalse(ShareActivity.runShare({ throw SecurityException("grant revoked") }, intent))

    @Test fun aFileThatCannotBeReadSaysSoAndATextShareDoesNot() {
        val file = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, android.net.Uri.parse("content://other.app/x"))
        assertEquals(R.string.share_unreadable_toast, ShareActivity.failureMessage(file))
        assertEquals(R.string.share_unreadable_toast, ShareActivity.failureMessage(Intent(Intent.ACTION_SEND_MULTIPLE)))
        assertEquals(R.string.share_nothing_toast, ShareActivity.failureMessage(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "")))
    }

    @Test fun everyMessageSaysWhatToDoNext() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(
            "Couldn't read that file to share it. Try sharing it again or send it from DeskLink's Files page.",
            context.getString(R.string.share_unreadable_toast),
        )
        assertEquals("This computer can't receive shares right now. Try again in a moment.", context.getString(R.string.share_unavailable_toast))
        assertEquals("There was nothing in that to share.", context.getString(R.string.share_nothing_toast))
    }
}
