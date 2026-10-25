/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.share

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShareIntakeTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val pkg = app.packageName

    @Test
    fun ourOwnProviderIsRefusedHoweverTheAuthorityIsSpelled() {
        listOf(
            "content://$pkg.fileprovider/x", "content://0@$pkg.fileprovider/x", "content://USER:pw@${pkg.uppercase()}.FILEPROVIDER:80/x",
            "content://$pkg.other/x", "content:///x",
        ).forEach { assertFalse(it, ShareUris.isAllowedFromOutside(app, Uri.parse(it))) }
        assertTrue(ShareUris.isAllowedFromOutside(app, Uri.parse("content://com.android.providers.media.documents/document/1")))
        assertFalse("a file path is never taken", ShareUris.isAllowedFromOutside(app, Uri.parse("file:///data/x")))
    }

    @Test
    fun aBucketShareIsOnlyAProposalAndIsBounded() {
        val many = ArrayList((1..700).map { Uri.parse("content://other.app/$it") })
        val multi = Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, many)
        assertEquals(ShareIntake.MAX_ITEMS, ShareIntake.forBucket(app, multi).uris.size)
        val own = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, Uri.parse("content://$pkg.fileprovider/secret"))
        assertTrue("the own provider gives nothing, and no text either", ShareIntake.forBucket(app, own).isEmpty)
        val text = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "x".repeat(ShareIntake.MAX_TEXT_CHARS + 50))
        assertEquals(ShareIntake.MAX_TEXT_CHARS, ShareIntake.forBucket(app, text).text!!.length)
        assertTrue(ShareIntake.forBucket(app, Intent("other")).isEmpty)
    }

    @Test
    fun aNotesShareNeedsSomethingInIt() {
        assertNull(ShareIntake.forNotes(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "  ")))
        assertNull(ShareIntake.forNotes(Intent("other").putExtra(Intent.EXTRA_TEXT, "hi")))
        assertEquals("s" to "hi", ShareIntake.forNotes(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "hi").putExtra(Intent.EXTRA_SUBJECT, "s")))
    }
}
