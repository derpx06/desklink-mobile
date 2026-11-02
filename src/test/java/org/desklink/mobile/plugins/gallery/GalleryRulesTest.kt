/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.gallery

import org.desklink.mobile.plugins.gallery.GalleryRules.Found
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GalleryRulesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val now = 1_730_000_000_000L
    private fun image(uri: String, mtime: Long, mime: String = "image/png", size: Long = 1000) = Found(uri, uri, mime, size, mtime)

    @Test
    fun onlyNewAllowedImagesOfTheRightSizeAndAgeAreSentOldestFirst() {
        val ledger = GalleryRules.Ledger(tmp.newFile("l.json"))
        ledger.add("done", now - 1000)
        val found = listOf(
            image("b", now - 2000), image("a", now - 5000),
            image("before-switch", now - 90_000), image("too-old", now - GalleryRules.MAX_AGE_MS - 1),
            image("video", now - 1000, mime = "video/mp4"), image("pdf", now - 1000, mime = "application/pdf"),
            image("huge", now - 1000, size = GalleryRules.MAX_BYTES + 1), image("empty", now - 1000, size = 0),
            image("done", now - 1000), image("heic", now - 3000, mime = "image/heic"),
        )
        assertEquals(listOf("a", "heic", "b"), GalleryRules.toSend(found, ledger, enabledAt = now - 10_000, now = now).map { it.uri })
    }

    @Test
    fun theSameUriWithANewTimeIsANewFile() {
        val ledger = GalleryRules.Ledger(tmp.newFile("l2.json"))
        ledger.add("shot", now - 1000)
        assertTrue(GalleryRules.toSend(listOf(image("shot", now - 1000)), ledger, 0, now).isEmpty())
        assertEquals(1, GalleryRules.toSend(listOf(image("shot", now - 500)), ledger, 0, now).size)
    }

    @Test
    fun theLedgerSurvivesARestartAndKeepsTheNewest2000() {
        val file = tmp.newFile("l3.json")
        val ledger = GalleryRules.Ledger(file)
        repeat(GalleryRules.LEDGER_SIZE + 5) { ledger.add("u$it", it.toLong()) }
        val again = GalleryRules.Ledger(file)
        assertFalse("the oldest fell out", again.has("u0", 0))
        assertTrue(again.has("u${GalleryRules.LEDGER_SIZE + 4}", (GalleryRules.LEDGER_SIZE + 4).toLong()))
        again.remove("u10", 10)
        assertFalse(GalleryRules.Ledger(file).has("u10", 10))
    }

    @Test
    fun twentyFilesOr200MbInARollingHourAndThenRoomAgain() {
        var t = 0L
        val budget = GalleryRules.Budget { t }
        repeat(19) { assertTrue(budget.allows(1)); budget.record(1); t += 1000 }
        assertTrue(budget.allows(1)); budget.record(1)
        assertFalse("the 21st in the hour", budget.allows(1))
        assertEquals(GalleryRules.HOUR_MS, budget.nextRoomAt())
        t = GalleryRules.HOUR_MS
        assertTrue("the first one has left the hour", budget.allows(1))

        t = 0
        val bytes = GalleryRules.Budget { t }
        bytes.record(150L * 1024 * 1024)
        assertFalse(bytes.allows(60L * 1024 * 1024))
        assertTrue(bytes.allows(50L * 1024 * 1024))
    }

    @Test
    fun theComputersRefusalsAreTold() {
        assertEquals(GalleryRules.Refusal.OFF, GalleryRules.refusalOf("gallery-off"))
        assertEquals(GalleryRules.Refusal.LIMIT, GalleryRules.refusalOf("refused: gallery-limit"))
        assertEquals(GalleryRules.Refusal.TYPE, GalleryRules.refusalOf("gallery-type"))
        assertEquals(GalleryRules.Refusal.NONE, GalleryRules.refusalOf("The file no longer exists."))
        assertEquals(GalleryRules.Refusal.NONE, GalleryRules.refusalOf(null))
    }
}
