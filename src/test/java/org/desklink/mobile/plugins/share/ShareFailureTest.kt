/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.share

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A share that cannot be read is a failure the activity can tell the person about, never a silent return to Home. */
@RunWith(RobolectricTestRunner::class)
class ShareFailureTest {
    private val intent = Intent(Intent.ACTION_SEND)

    @Test fun aShareThatWasHandedOnIsNotAFailure() = assertTrue(ShareActivity.runShare({ true }, intent))

    @Test fun nothingToShareIsAFailure() = assertFalse(ShareActivity.runShare({ false }, intent))

    @Test fun aRevokedGrantIsAFailureNotACrash() = assertFalse(ShareActivity.runShare({ throw SecurityException("grant revoked") }, intent))

    @Test fun theMessageNamesTheNextStep() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val text = context.getString(org.desklink.mobile.R.string.share_unreadable_toast)
        assertTrue(text, text.contains("Try sharing it again") && text.contains("Files page"))
    }
}
