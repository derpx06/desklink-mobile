/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.plugins.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TransferHistoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val day = 24L * 60 * 60 * 1000
    private var now = 100 * day

    private fun record(id: String, device: String = "desk", time: Long = now, name: String = "$id.txt") =
        TransferRecord(id, device, "arch-desk", name, 10, sent = id.startsWith("s"), time = time, uri = "file:///tmp/$name", mime = null)

    private fun store() = TransferHistoryStore(folder.root.resolve("h.tsv")) { now }

    @Test
    fun appendsNewestFirstAndSurvivesARestart() {
        store().apply {
            append(record("a", time = now - 2))
            append(record("s-b", time = now - 1))
        }
        val reopened = store().all()
        assertEquals(listOf("s-b", "a"), reopened.map { it.id })
        assertEquals(true, reopened[0].sent)
        assertEquals("s-b.txt", reopened[0].name)
    }

    @Test
    fun keepsTabsNewlinesAndUnicodeInNames() {
        store().append(record("x", name = "a\tb\nc ✓.png"))
        assertEquals("a\tb\nc ✓.png", store().all().single().name)
    }

    @Test
    fun prunesOlderThanThirtyDays() {
        store().apply {
            append(record("old", time = now - 31 * day))
            append(record("new", time = now - 29 * day))
        }
        now += 1
        assertEquals(listOf("new"), store().all().map { it.id })
    }

    @Test
    fun capsTheNumberOfRecords() {
        val s = store()
        repeat(TransferHistoryStore.MAX_RECORDS + 20) { s.append(record("r$it", time = now - 1000 + it)) }
        val kept = store().all()
        assertEquals(TransferHistoryStore.MAX_RECORDS, kept.size)
        assertEquals("r${TransferHistoryStore.MAX_RECORDS + 19}", kept.first().id)
    }

    @Test
    fun filtersByComputerAndRemoves() {
        store().apply {
            append(record("a", device = "desk"))
            append(record("b", device = "laptop"))
            append(record("c", device = "desk"))
            remove("c")
        }
        assertEquals(listOf("a"), store().forDevice("desk").map { it.id })
        assertEquals(listOf("b"), store().forDevice("laptop").map { it.id })
    }

    @Test
    fun skipsLinesItCannotRead() {
        folder.root.resolve("h.tsv").writeText("garbage\n" + TransferHistoryStore.encode(record("ok")) + "\n")
        assertEquals(listOf("ok"), store().all().map { it.id })
        assertNull(TransferHistoryStore.decode("2\tfuture\tformat"))
    }

    @Test
    fun previewKindFromMimeOrName() {
        assertEquals(PreviewKind.IMAGE, previewKind("image/png", "x"))
        assertEquals(PreviewKind.VIDEO, previewKind(null, "clip.MP4"))
        assertEquals(PreviewKind.VIDEO, previewKind("application/octet-stream", "clip.mkv"))
        assertEquals(PreviewKind.AUDIO, previewKind("audio/ogg", "x"))
        assertEquals(PreviewKind.PDF, previewKind(null, "report.pdf"))
        assertEquals(PreviewKind.TEXT, previewKind("text/x-rust", "main.rs"))
        assertEquals(PreviewKind.ARCHIVE, previewKind(null, "backup.tar.gz"))
        assertEquals(PreviewKind.OTHER, previewKind(null, "Makefile"))
    }
}
