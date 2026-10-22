/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.folders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import kotlin.random.Random

/** Contract F8 P2–P5 on the phone: the desk's `folders.rs` tests, and both sides converging. */
class FolderSyncTest {
    private fun entry(sha: String, desk: Long, phone: Long) = Entry(sha.length.toLong(), 1_700_000_000_000, sha, false, Vv(desk, phone))
    private fun tomb(desk: Long, phone: Long) = Entry(0, 1_700_000_000_000, "", true, Vv(desk, phone))

    @Test
    fun dominanceTakesTiesDoNothingAndConcurrentEditsConflict() {
        val local = mapOf(
            "same" to entry("a", 1, 1), "older" to entry("a", 1, 0), "newer" to entry("b", 2, 0),
            "both" to entry("c", 2, 0), "deleted-there" to entry("d", 1, 0), "deleted-here" to tomb(2, 0),
        )
        val remote = mapOf(
            "same" to entry("a", 1, 1), "older" to entry("z", 1, 1), "newer" to entry("b0", 1, 0),
            "both" to entry("q", 1, 1), "deleted-there" to tomb(1, 1), "deleted-here" to entry("e", 1, 1),
            "new" to entry("n", 0, 1), "gone" to tomb(0, 2),
        )
        val steps = FolderSync.plan(Side.DESK, local, remote, "Pixel")
        assertTrue(Step.Download("older", entry("z", 1, 1)) in steps)
        assertTrue(Step.Delete("deleted-there", tomb(1, 1)) in steps)
        assertTrue(Step.Download("new", entry("n", 0, 1)) in steps)
        assertTrue(Step.Take("gone", tomb(0, 2)) in steps)
        assertTrue(Step.Conflict("both", "both (conflict from Pixel 2023-11-14)") in steps)
        assertTrue(Step.Download("deleted-here", entry("e", 2, 1)) in steps)
        assertFalse(steps.any { it is Step.Bump && it.path == "both" })
    }

    @Test
    fun thePhoneRenamesItsOwnVersionInAConflict() {
        val steps = FolderSync.plan(Side.PHONE, mapOf("notes.txt" to entry("p", 1, 2)), mapOf("notes.txt" to entry("d", 2, 1)), "Pixel 9")
        assertEquals(
            listOf(Step.Rename("notes.txt", "notes (conflict from Pixel 9 2023-11-14).txt"), Step.Download("notes.txt", entry("d", 3, 2))),
            steps,
        )
    }

    @Test
    fun renamesAndCopiesAreFoundByHash() {
        val local = mapOf("a/old.jpg" to entry("x", 1, 0), "keep.jpg" to entry("k", 1, 0))
        val remote = mapOf("a/old.jpg" to tomb(1, 1), "b/new.jpg" to entry("x", 0, 1), "dup.jpg" to entry("k", 0, 1))
        assertEquals(
            listOf(Step.Move("a/old.jpg", "b/new.jpg", entry("x", 0, 1), tomb(1, 1)), Step.Copy("keep.jpg", "dup.jpg", entry("k", 0, 1))),
            FolderSync.plan(Side.DESK, local, remote, "P"),
        )
    }

    @Test
    fun aScanBumpsChangesAndTombstonesWhatVanishedButNotADownloadOnItsWay() {
        val m = mutableMapOf("a" to entry("a", 0, 1), "b" to entry("b", 0, 1), "c" to entry("c", 0, 1), "coming" to entry("x", 3, 1))
        val found = mapOf("a" to Stat(1, 1_700_000_000_000), "b" to Stat(9, 5), "new" to Stat(1, 7))
        val changed = FolderSync.applyScan(Side.PHONE, m, found, { p -> if (p == "b") "b2" else p }, now = 1_700_000_100_000, keep = setOf("coming"))
        assertTrue(changed)
        assertEquals(Vv(0, 1), m["a"]!!.vv)
        assertEquals(Entry(9, 5, "b2", false, Vv(0, 2)), m["b"])
        assertTrue(m["c"]!!.deleted && m["c"]!!.vv == Vv(0, 2))
        assertEquals(Vv(0, 1), m["new"]!!.vv)
        assertFalse(m["coming"]!!.deleted)
    }

    /** Two folders, random edits on both sides, then plan/apply in both directions until quiet. */
    @Test
    fun bothSidesConvergeAndNoEditIsLost() {
        val random = Random(7)
        val names = listOf("a.txt", "b.txt", "d/c.md", "d/e.md")
        repeat(300) { round ->
            val desk = Folder(Side.DESK); val phone = Folder(Side.PHONE)
            names.take(2).forEach { desk.files[it] = "v0-$it"; phone.files[it] = "v0-$it" }
            desk.scan(); phone.scan()
            sync(desk, phone)
            val base = desk.files.values.toSet()
            for (side in listOf(desk, phone)) repeat(random.nextInt(4)) {
                val name = names.random(random)
                when (random.nextInt(3)) {
                    0, 1 -> side.files[name] = "r$round-${side.side}-${random.nextInt(1000)}"
                    else -> side.files.remove(name)
                }
                side.clock++
            }
            val edits = (desk.files.values + phone.files.values).toSet() - base
            desk.scan(); phone.scan()
            repeat(4) { sync(desk, phone) }
            assertEquals("round $round converges", desk.files, phone.files)
            assertTrue("round $round keeps every edit", desk.files.values.containsAll(edits))
        }
    }

    private class Folder(val side: Side) {
        val files = sortedMapOf<String, String>()
        val manifest = mutableMapOf<String, Entry>()
        var clock = 1_700_000_000_000L

        fun scan() {
            FolderSync.applyScan(side, manifest, files.mapValues { (_, c) -> Stat(c.length.toLong(), c.hashCode().toLong()) }, { sha(files.getValue(it)) }, clock)
        }
    }

    private fun sync(desk: Folder, phone: Folder) {
        // The phone acts on the desk's manifest, then the desk on the phone's (as they would exchange them).
        apply(phone, desk)
        phone.scan()
        apply(desk, phone)
        desk.scan()
    }

    private fun apply(here: Folder, there: Folder) {
        for (step in FolderSync.plan(here.side, here.manifest, there.manifest, "phone")) when (step) {
            is Step.Take -> here.manifest[step.path] = step.entry
            is Step.Bump -> here.manifest[step.path] = here.manifest.getValue(step.path).copy(vv = step.vv)
            is Step.Delete -> { here.files.remove(step.path); here.manifest[step.path] = step.entry }
            is Step.Download -> place(here, step.path, there.files[step.path] ?: continue, step.entry)
            is Step.Move -> { here.files.remove(step.from); here.manifest[step.from] = step.tombstone; place(here, step.to, there.files[step.to] ?: continue, step.entry) }
            is Step.Copy -> place(here, step.to, there.files[step.to] ?: continue, step.entry)
            is Step.Rename -> here.files.remove(step.from)?.let { here.files[step.to] = it }
            is Step.Conflict -> Unit
        }
    }

    private fun place(here: Folder, path: String, content: String, entry: Entry) {
        if (sha(content) != entry.sha256) return // changed meanwhile: the next round settles it
        here.files[path] = content
        here.manifest[path] = entry.copy(size = content.length.toLong(), mtime = content.hashCode().toLong())
    }

    @Test
    fun pathsAndIgnoresFollowP2AndP5() {
        assertTrue(FolderSync.validRelpath("Photos/2026/a.jpg"))
        listOf("", "/abs", "a/../b", "a/./b", "a//b", "a\\b", "a/\u0000b", "x".repeat(FolderSync.MAX_SEGMENT_BYTES + 1))
            .forEach { assertFalse(it, FolderSync.validRelpath(it)) }
        assertFalse(FolderSync.validRelpath(List(FolderSync.MAX_DEPTH + 1) { "d" }.joinToString("/")))
        assertFalse(FolderSync.validRelpath(List(5) { "y".repeat(250) }.joinToString("/")))
        listOf("p/node_modules/x.js", ".git/HEAD", "a/b.part", "a/b.tmp", "a/b~", "a/.desklink-1.tmp", "a/.hidden").forEach { assertTrue(it, FolderSync.ignored(it, false)) }
        assertFalse(FolderSync.ignored("a/.hidden", includeHidden = true))
        assertFalse(FolderSync.ignored("docs/report.pdf", false))
        val long = FolderSync.conflictName("d/" + "s".repeat(250) + ".txt", "Pixel", 0)
        assertTrue(long.substringAfter('/').toByteArray().size <= FolderSync.MAX_SEGMENT_BYTES)
        assertTrue(long.endsWith(" (conflict from Pixel 1970-01-01).txt"))
    }


    /** A pair at its 20,000-file bound: the manifest splits into parts that each cross the data channel. */
    @Test
    fun aFullManifestSplitsIntoPartsThatCross() {
        val manifest = (0 until FolderSync.MAX_FILES).associate { "folder-$it/${"n".repeat(60)}-$it.jpg" to entry(sha("$it"), it.toLong(), 1) }
        val packets = BucketEngine.manifestPackets("pair-1", manifest)
        assertTrue(packets.size in 2..FolderSync.MAX_PARTS)
        org.desklink.mobile.webrtc.assertAllCross(packets)
        assertEquals(FolderSync.MAX_FILES, packets.sumOf { it.getJSONArray("entries")!!.length() })
    }

    /** P6 review fix: the desk's entries the phone's own rules would skip are dropped; F8b: files only. */
    @Test
    fun incomingEntriesAreFilteredByThePhonesOwnRules() {
        assertTrue(FolderSync.takesIncoming("report.pdf", dir = false))
        listOf(".hidden", "x.tmp", "x.part", "x~", ".desklink-1.tmp", "node_modules", ".git").forEach { assertFalse(it, FolderSync.takesIncoming(it, dir = false)) }
        assertFalse("no folders in the Bucket", FolderSync.takesIncoming("docs/a.txt", dir = false))
        assertFalse("no dir entries", FolderSync.takesIncoming("docs", dir = true))
    }

    /** P6 review fix: a Bucket that had files and suddenly scans empty is held, never emptied on the other side. */
    @Test
    fun aSuddenlyEmptyFolderIsHeldUntilConfirmed() {
        val had = mapOf("a.txt" to entry("a", 0, 1))
        assertTrue(FolderSync.holdEmpty(had, emptyMap(), confirmed = false))
        assertFalse(FolderSync.holdEmpty(had, emptyMap(), confirmed = true))
        assertFalse(FolderSync.holdEmpty(had, mapOf("b.txt" to Stat(1, 1)), confirmed = false))
        assertFalse("nothing to lose", FolderSync.holdEmpty(mapOf("a.txt" to tomb(0, 2)), emptyMap(), confirmed = false))
    }

    @Test
    fun incomingFilesNeedRoomAndTheSizeBound() {
        val mib = 1024L * 1024
        assertTrue(FolderSync.enoughSpace(free = 600 * mib, size = 100 * mib))
        assertFalse(FolderSync.enoughSpace(free = 599 * mib, size = 100 * mib))
        assertFalse(FolderSync.enoughSpace(free = Long.MAX_VALUE, size = FolderSync.MAX_FILE_BYTES + 1))
    }

    @Test
    fun textAndLinksBecomeFilesWithinTheirBound() {
        val (text, body) = FolderSync.textItem("  hello  ", 0)!!
        assertTrue(text.startsWith("Text ") && text.endsWith(".txt"))
        assertEquals("hello\n", String(body))
        val (link, desktop) = FolderSync.textItem("https://www.example.org/a?b=1", 0)!!
        assertTrue(link.startsWith("example.org ") && link.endsWith(".url"))
        assertTrue(String(desktop).contains("Type=Link") && String(desktop).contains("URL=https://www.example.org/a?b=1"))
        assertEquals(null, FolderSync.textItem("x".repeat(FolderSync.MAX_TEXT_BYTES), 0))
        assertEquals(null, FolderSync.textItem("   ", 0))
    }

    @Test
    fun sharedNamesBecomeTopLevelFileNames() {
        assertEquals("a.txt", BucketEngine.safeName("../../a.txt"))
        assertEquals("hidden", BucketEngine.safeName(".hidden"))
        assertEquals("File", BucketEngine.safeName("  "))
        assertTrue(BucketEngine.safeName("é".repeat(300)).toByteArray().size <= FolderSync.MAX_SEGMENT_BYTES)
    }
}

private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
