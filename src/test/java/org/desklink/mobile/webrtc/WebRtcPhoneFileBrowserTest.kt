/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
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

    @Test
    fun `a folder too big for one reply is served in pages that each fit an envelope`() {
        val folder = firstRoot.resolve("big").apply { mkdirs() }
        // Long names make the reply, not the entry count, the limit.
        val names = (0 until 400).map { "%04d-".format(it) + "n".repeat(240) }
        names.forEach { folder.resolve(it).writeText("x") }
        val folderId = childId(rootId(), "big")

        val seen = mutableListOf<String>()
        var offset = 0
        var pages = 0
        while (true) {
            val reply = request("list", "entryId" to folderId, "offset" to offset)
            assertTrue(reply.optString("error"), reply.getBoolean("ok"))
            assertTrue(
                "a page must fit the ${WebRtcEnvelope.MAX_PAYLOAD_BYTES}-byte envelope payload",
                reply.toString().toByteArray().size <= WebRtcEnvelope.MAX_PAYLOAD_BYTES,
            )
            val result = reply.getJSONObject("result")
            assertEquals(offset, result.getInt("offset"))
            val page = result.getJSONArray("entries")
            assertTrue("every page makes progress", page.length() > 0)
            repeat(page.length()) { seen += page.getJSONObject(it).getString("name") }
            pages++
            if (!result.has("nextOffset")) break
            assertEquals(offset + page.length(), result.getInt("nextOffset"))
            offset = result.getInt("nextOffset")
        }

        assertTrue("400 long names cannot fit one page", pages > 1)
        assertEquals("no entry skipped or repeated", names.sorted(), seen)
    }

    @Test
    fun `a small folder is one page with no next offset`() {
        val result = request("list", "entryId" to rootId()).getJSONObject("result")
        assertEquals(0, result.getInt("offset"))
        assertFalse(result.has("nextOffset"))
    }

    @Test
    fun `an offset past the end is an empty last page and a negative one is refused`() {
        val id = rootId()
        val past = request("list", "entryId" to id, "offset" to 50).getJSONObject("result")
        assertEquals(0, past.getJSONArray("entries").length())
        assertFalse(past.has("nextOffset"))

        assertFalse(request("list", "entryId" to id, "offset" to -1).getBoolean("ok"))
    }

    @Test
    fun `listing a folder again revokes the ids it handed out before`() {
        val rootId = rootId()
        val first = childId(rootId, "note.txt")

        val second = childId(rootId, "note.txt")

        assertFalse("the old id is stale once the folder is re-listed", first == second)
        assertFalse(request("metadata", "entryId" to first).getBoolean("ok"))
        assertTrue(request("metadata", "entryId" to second).getBoolean("ok"))
    }

    @Test
    fun `later pages do not revoke the ids of earlier ones`() {
        val folder = firstRoot.resolve("paged").apply { mkdirs() }
        (0 until 400).forEach { folder.resolve("%04d-".format(it) + "n".repeat(240)).writeText("x") }
        val folderId = childId(rootId(), "paged")
        val page = request("list", "entryId" to folderId).getJSONObject("result")
        val firstId = page.getJSONArray("entries").getJSONObject(0).getString("entryId")

        request("list", "entryId" to folderId, "offset" to page.getInt("nextOffset"))

        assertTrue(request("metadata", "entryId" to firstId).getBoolean("ok"))
    }

    private fun rootId(): String = request("roots").getJSONObject("result")
        .getJSONArray("entries").getJSONObject(0).getString("entryId")

    private fun childId(parentId: String, name: String): String {
        var offset = 0
        while (true) {
            val result = request("list", "entryId" to parentId, "offset" to offset)
                .getJSONObject("result")
            val page = result.getJSONArray("entries")
            for (i in 0 until page.length()) {
                if (page.getJSONObject(i).getString("name") == name) {
                    return page.getJSONObject(i).getString("entryId")
                }
            }
            offset = result.optInt("nextOffset", -1)
            check(offset >= 0) { "$name is not in the folder" }
        }
    }

    private fun request(action: String, vararg fields: Pair<String, Any>): JSONObject {
        val value = JSONObject()
            .put("browserVersion", 1)
            .put("requestId", "request-$action-${System.nanoTime()}")
            .put("action", action)
        fields.forEach { (key, fieldValue) -> value.put(key, fieldValue) }
        return JSONObject(String(browser.handle(value.toString().toByteArray()), Charsets.UTF_8))
    }
}
