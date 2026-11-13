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
            image("b", now - 6000), image("a", now - 9000),
            image("before-switch", now - 90_000), image("too-old", now - GalleryRules.MAX_AGE_MS - 1),
            image("video", now - 6000, mime = "video/mp4"), image("pdf", now - 6000, mime = "application/pdf"),
            image("huge", now - 6000, size = GalleryRules.MAX_BYTES + 1), image("empty", now - 6000, size = 0),
            image("done", now - 1000), image("heic", now - 7000, mime = "image/heic"), image("still-writing", now - 1000),
        )
        assertEquals(listOf("a", "heic", "b"), GalleryRules.toSend(found, ledger, { now - 10_000 }, now = now).map { it.uri })
    }

    @Test
    fun theSameUriWithANewTimeIsANewFile() {
        val ledger = GalleryRules.Ledger(tmp.newFile("l2.json"))
        ledger.add("shot", now - 6500)
        assertTrue(GalleryRules.toSend(listOf(image("shot", now - 6500)), ledger, { 0 }, now).isEmpty())
        assertEquals(1, GalleryRules.toSend(listOf(image("shot", now - 6000)), ledger, { 0 }, now).size)
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

    @Test
    fun aFileStillBeingWrittenWaitsAndEachFoldersOwnClockCounts() {
        val ledger = GalleryRules.Ledger(tmp.newFile("l4.json"))
        val fresh = image("fresh", now - 1000)
        assertTrue("under five seconds old", GalleryRules.toSend(listOf(fresh), ledger, { 0 }, now).isEmpty())
        val inNewFolder = Found("n", "n", "image/png", 10, now - 60_000, folder = "new")
        val inOldFolder = Found("o", "o", "image/png", 10, now - 60_000, folder = "old")
        val since = mapOf("new" to now - 30_000, "old" to now - 90_000)
        assertEquals("a folder picked just now sends nothing that was in it", listOf("o"), GalleryRules.toSend(listOf(inNewFolder, inOldFolder), ledger, { since.getValue(it.folder) }, now).map { it.uri })
    }

    @Test
    fun aScanLooksAtTheNewestTwoHundredOfAFolder() {
        val many = (1..500).map { Found("u$it", "u$it", "image/png", 10, it.toLong()) }
        val newest = GalleryRules.newest(many)
        assertEquals(GalleryRules.SCAN_LIMIT, newest.size)
        assertEquals("u500", newest.first().uri)
        assertEquals("u301", newest.last().uri)
    }

    @Test
    fun theSwitchCanAlwaysBeTurnedOffAGrantIsLetGoOfAndTheFolderIsLookedAtAgainWithoutAnObserver() {
        val tab = java.io.File("src/main/java/org/desklink/mobile/ui/compose/screen/settings/SettingsTab.kt").readText()
        assertTrue("enabled while on, even when the computer is away or stopped accepting", tab.contains("enabled = accepted || on"))
        val watcher = java.io.File("src/main/java/org/desklink/mobile/plugins/gallery/GalleryWatcher.kt").readText()
        assertTrue("the previous folder's grant is released", watcher.substringAfter("fun setFolder").substringBefore("private fun inUse").contains("releaseUnlessUsed(context, old)"))
        assertTrue("a poll while a computer is taking pictures", watcher.contains("POLL_MS") && watcher.contains("if (watching)"))
        assertTrue("a failure nobody can explain gives the mark back, a few times", watcher.contains("ledger.remove(next.uri, next.mtime); retryLater(context, s, 60_000L)"))
        assertTrue("a burst of provider notifications is one pass", watcher.contains("kickSoon(context)"))
        assertTrue("the manager forgets stored gallery sends when the switch is off", java.io.File("src/main/java/org/desklink/mobile/webrtc/WebRtcFileTransferManager.kt").readText().contains("GalleryWatcher.mayResume("))
    }

    @Test
    fun theFallbackListingWaitsLongWithTheScreenOff() {
        assertEquals(60_000L, GalleryWatcher.pollAfter(screenOn = true))
        assertTrue("not every minute in a pocket", GalleryWatcher.pollAfter(screenOn = false) >= 15 * 60_000L)
    }
}
