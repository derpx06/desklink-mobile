/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.folders

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.desklink.mobile.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The phone has no trash: the removal dialog says what is true on each side. */
@RunWith(RobolectricTestRunner::class)
class BucketRemoveWordsTest {
    @Test
    fun theRemoveDialogDoesNotPromiseATrashOnThePhone() {
        val text = ApplicationProvider.getApplicationContext<Application>().getString(R.string.bucket_remove_body, "Desk")
        assertTrue(text, "from this phone for good" in text)
        assertTrue(text, "on Desk it goes to the trash" in text)
        assertFalse("here and on", "here and on" in text)
    }
}
