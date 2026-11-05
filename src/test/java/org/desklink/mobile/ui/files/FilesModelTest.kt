/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.files

import org.desklink.mobile.plugins.share.TransferRecord
import org.desklink.mobile.ui.compose.screen.transfers.BucketBreakdown
import org.desklink.mobile.ui.compose.screen.transfers.FileFilter
import org.desklink.mobile.ui.compose.screen.transfers.dayStart
import org.desklink.mobile.ui.compose.screen.transfers.groupByDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesModelTest {
    private fun rec(name: String, time: Long = 1_700_000_000_000L, mime: String? = null) =
        TransferRecord(name + time, "d", "desk", name, 10, true, time, "file:///$name", mime)

    @Test fun eachChipTakesItsKindsAndAllTakesEverything() {
        val files = listOf("a.jpg", "b.mp4", "c.pdf", "d.docx", "e.txt", "f.zip", "g.mp3", "h.bin").map { rec(it) }
        fun names(f: FileFilter) = files.filter(f::matches).map { it.name }
        assertEquals(files.map { it.name }, names(FileFilter.ALL))
        assertEquals(listOf("a.jpg", "b.mp4"), names(FileFilter.IMAGES))
        assertEquals(listOf("c.pdf", "d.docx", "e.txt"), names(FileFilter.DOCUMENTS))
        assertEquals(listOf("f.zip"), names(FileFilter.ARCHIVES))
    }

    @Test fun aGivenMimeBeatsTheExtension() {
        assertTrue(FileFilter.IMAGES.matches(rec("scan", mime = "image/png")))
        assertFalse(FileFilter.DOCUMENTS.matches(rec("scan", mime = "image/png")))
    }

    @Test fun daysAreNewestFirstAndKeepTheOrderInsideADay() {
        val day = 24L * 3_600_000
        val now = 1_700_000_000_000L
        val a = rec("a", now); val b = rec("b", now - 1000); val c = rec("c", now - 2 * day)
        val groups = groupByDay(listOf(a, b, c))
        assertEquals(2, groups.size)
        assertTrue(groups[0].first > groups[1].first)
        assertEquals(listOf("a", "b"), groups[0].second.map { it.name })
        assertEquals(dayStart(now), groups[0].first)
    }

    @Test fun theBucketBarSplitsBytesByKind() {
        val b = BucketBreakdown.of(listOf("a.jpg" to 100L, "b.pdf" to 40L, "c.bin" to 7L, "d.png" to 50L))
        assertEquals(BucketBreakdown(150, 40, 7), b)
        assertEquals(197, b.total)
    }

    @Test fun theViewCountsEveryMatchButShowsOnlyTheLimit() {
        val now = 1_700_000_000_000L
        val files = (1..5).map { rec("p$it.jpg", now - it) } + rec("a.zip", now)
        val view = org.desklink.mobile.ui.compose.screen.transfers.FilesView.of(files, FileFilter.IMAGES, 3)
        assertEquals(5, view.matching)
        assertEquals(3, view.groups.sumOf { it.second.size })
        assertEquals(1, org.desklink.mobile.ui.compose.screen.transfers.FilesView.of(files, FileFilter.ARCHIVES, 12).matching)
    }
}
