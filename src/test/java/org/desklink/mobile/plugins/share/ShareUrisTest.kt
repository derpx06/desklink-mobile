/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.share

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.desklink.mobile.helpers.FilesHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** A share from another app may not name this app's private data, this app's own provider, or a device to send to unasked. */
@RunWith(RobolectricTestRunner::class)
class ShareUrisTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun fileUrisAndOurOwnProviderAreRefusedFromOutsideAndOtherContentIsKept() {
        val prefs = File(app.dataDir, "shared_prefs/secret.xml").apply { parentFile!!.mkdirs(); writeText("<key/>") }
        val hostile = listOf(
            Uri.fromFile(prefs),
            Uri.parse("file:///sdcard/Download/x.txt"),
            Uri.parse("content://${app.packageName}.fileprovider/external_files/Documents/private.pdf"),
            null,
        )
        val ok = Uri.parse("content://com.android.providers.media.documents/document/image%3A1")
        assertEquals(listOf(ok), ShareUris.fromOutside(app, hostile + ok))
        assertFalse(ShareUris.isAllowedFromOutside(app, Uri.parse("http://example.com/a")))
    }

    @Test
    fun aFileInsideTheAppsOwnStorageIsInsideEvenWithDotDotAndNeverSent() {
        val prefs = File(app.dataDir, "shared_prefs/secret.xml").apply { parentFile!!.mkdirs(); writeText("<key/>") }
        assertTrue(ShareUris.isInsideAppData(app, prefs))
        assertTrue(ShareUris.isInsideAppData(app, File(app.filesDir, "../shared_prefs/secret.xml")))
        assertFalse(ShareUris.isInsideAppData(app, File("/sdcard/Download/x.txt")))
        assertNull("the sender reads nothing from it", FilesHelper.uriToNetworkPacket(app, Uri.fromFile(prefs), "desklink.share.request"))
    }

    @Test
    fun aDeviceNamedInTheIntentIsOnlyASuggestionAndOnlyForAPairedOne() {
        val paired = setOf("desk-1")
        assertEquals("desk-1", ShareEntry.suggestedDevice("desk-1", null, paired))
        assertEquals("desk-1", ShareEntry.suggestedDevice(null, "desk-1", paired))
        assertNull("an unknown id is ignored: the list is shown", ShareEntry.suggestedDevice("attacker", "x", paired))
        assertNull(ShareEntry.suggestedDevice(null, null, paired))
    }

    @Test
    fun theConfirmationSaysWhatIsSent() {
        val text = ShareEntry.summary(app, "https://example.org/a", emptyList(), 0)
        assertTrue(text, "https://example.org/a" in text)
        val none = ShareEntry.summary(app, null, emptyList(), 0)
        assertTrue(none, none.isNotBlank())
        val files = ShareEntry.summary(app, null, emptyList(), 2)
        assertTrue(files, "2" in files)
    }
}
