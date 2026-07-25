/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class WebRtcPhoneFileBrowserTest {
    private lateinit var context: Application
    private lateinit var firstRoot: File
    private lateinit var secondRoot: File
    private lateinit var browser: WebRtcPhoneFileBrowser

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        firstRoot = context.cacheDir.resolve("phone-files-a").apply {
            deleteRecursively()
            mkdirs()
        }
        secondRoot = context.cacheDir.resolve("phone-files-b").apply {
            deleteRecursively()
            mkdirs()
        }
        firstRoot.resolve("note.txt").writeText("DeskLink")
        browser = WebRtcPhoneFileBrowser(context) {
            listOf(
                PhoneFileRoot("First", Uri.fromFile(firstRoot)),
                PhoneFileRoot("Second", Uri.fromFile(secondRoot)),
            )
        }
    }

    @After
    fun tearDown() {
        firstRoot.deleteRecursively()
        secondRoot.deleteRecursively()
    }

    @Test
    fun `roots and listing expose opaque ids without paths`() {
        val roots = request("roots")
        assertTrue(roots.getBoolean("ok"))
        assertFalse(roots.toString().contains(firstRoot.absolutePath))
        val rootId = roots.getJSONObject("result").getJSONArray("entries")
            .getJSONObject(0).getString("entryId")

        val listing = request("list", "entryId" to rootId)
        val entry = listing.getJSONObject("result").getJSONArray("entries").getJSONObject(0)
        assertTrue(entry.getString("name") == "note.txt")
        assertFalse(entry.toString().contains("file:"))
    }

    @Test
    fun `invalid names and cross root moves are rejected`() {
        val roots = request("roots").getJSONObject("result").getJSONArray("entries")
        val firstId = roots.getJSONObject(0).getString("entryId")
        val secondId = roots.getJSONObject(1).getString("entryId")
        val fileId = request("list", "entryId" to firstId)
            .getJSONObject("result").getJSONArray("entries")
            .getJSONObject(0).getString("entryId")

        assertFalse(request("rename", "entryId" to fileId, "name" to "../escape").getBoolean("ok"))
        assertFalse(request("move", "entryId" to fileId, "destinationId" to secondId).getBoolean("ok"))
        assertTrue(firstRoot.resolve("note.txt").isFile)
    }

    @Test
    fun `root refresh revokes old opaque ids`() {
        val oldRoot = request("roots").getJSONObject("result").getJSONArray("entries")
            .getJSONObject(0).getString("entryId")
        request("roots")
        assertFalse(request("list", "entryId" to oldRoot).getBoolean("ok"))
    }

    @Test
    fun `download returns metadata for an authorized file`() {
        val roots = request("roots").getJSONObject("result").getJSONArray("entries")
        val rootId = roots.getJSONObject(0).getString("entryId")
        val fileId = request("list", "entryId" to rootId)
            .getJSONObject("result").getJSONArray("entries")
            .getJSONObject(0).getString("entryId")

        val result = request(
            "download",
            "entryId" to fileId,
            "transferId" to "transfer-download-1",
        )
        assertTrue(result.getBoolean("ok"))
        assertTrue(result.getJSONObject("result").getLong("size") > 0)
        assertTrue(result.getJSONObject("result").getString("transferId") == "transfer-download-1")
    }

    private fun request(action: String, vararg fields: Pair<String, String>): JSONObject {
        val value = JSONObject()
            .put("browserVersion", 1)
            .put("requestId", "request-$action-${System.nanoTime()}")
            .put("action", action)
        fields.forEach { (key, fieldValue) -> value.put(key, fieldValue) }
        return JSONObject(String(browser.handle(value.toString().toByteArray()), Charsets.UTF_8))
    }
}
