/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.folders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.MessageDigest

/** W0 / F8c: the Bucket's limits, paths and folder behaviour, one test per edge-case row where it can be pure. */
@RunWith(RobolectricTestRunner::class)
class BucketRulesTest {
    private val gib = 1L shl 30

    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    // ---- a two-sided model with folders --------------------------------------------------------------------

    private class Side2(val side: Side) {
        val files = sortedMapOf<String, String>()
        val dirs = sortedSetOf<String>()
        val manifest = mutableMapOf<String, Entry>()
        var clock = 1_700_000_000_000L
        var transfers = 0

        fun stats(): Map<String, Stat> = files.mapValues { (_, c) -> Stat(c.length.toLong(), c.hashCode().toLong()) } + dirs.associateWith { Stat(0, 0, dir = true) }

        fun scan() {
            FolderSync.applyScan(side, manifest, stats(), { path -> MessageDigest.getInstance("SHA-256").digest(files.getValue(path).toByteArray()).joinToString("") { "%02x".format(it) } }, clock)
        }
    }

    private fun sync(desk: Side2, phone: Side2) {
        apply(phone, desk); phone.scan()
        apply(desk, phone); desk.scan()
    }

    private fun apply(here: Side2, there: Side2) {
        for (step in FolderSync.plan(here.side, here.manifest, there.manifest, "phone")) when (step) {
            is Step.Take -> here.manifest[step.path] = step.entry
            is Step.Bump -> here.manifest[step.path] = here.manifest.getValue(step.path).copy(vv = step.vv)
            is Step.Delete -> {
                val old = here.manifest[step.path]
                if (old?.dir == true) {
                    if (here.files.keys.none { it.startsWith(step.path + "/") } && here.dirs.none { it.startsWith(step.path + "/") }) { here.dirs.remove(step.path); here.manifest[step.path] = step.entry }
                    else here.manifest[step.path] = old.copy(vv = old.vv.max(step.entry.vv).bumped(here.side))
                } else { here.files.remove(step.path); here.manifest[step.path] = step.entry }
            }
            is Step.Download -> if (step.entry.dir) { here.dirs += step.path; here.manifest[step.path] = step.entry } else place(here, there, step.path, step.entry)
            is Step.Move -> {
                here.files.remove(step.from)?.let { c ->
                    here.files[step.to] = c
                    here.manifest[step.from] = step.tombstone
                    here.manifest[step.to] = step.entry.copy(size = c.length.toLong(), mtime = c.hashCode().toLong())
                }
            }
            is Step.Copy -> place(here, there, step.to, step.entry)
            is Step.Rename -> here.files.remove(step.from)?.let { here.files[step.to] = it }
            is Step.Conflict -> Unit
        }
        // A folder implies its parents.
        here.files.keys.forEach { f -> var d = f.substringBeforeLast('/', ""); while (d.isNotEmpty()) { here.dirs += d; d = d.substringBeforeLast('/', "") } }
    }

    private fun place(here: Side2, there: Side2, path: String, entry: Entry) {
        val c = there.files[path] ?: return
        if (MessageDigest.getInstance("SHA-256").digest(c.toByteArray()).joinToString("") { "%02x".format(it) } != entry.sha256) return
        here.transfers++
        here.files[path] = c
        here.manifest[path] = entry.copy(size = c.length.toLong(), mtime = c.hashCode().toLong())
    }

    private fun pair(): Pair<Side2, Side2> = Side2(Side.DESK) to Side2(Side.PHONE)

    @Test
    fun aNestedTreeWithAnEmptyFolderRoundTrips() {
        val (desk, phone) = pair()
        desk.dirs += listOf("Trip", "Trip/Day1", "Empty")
        desk.files["Trip/Day1/a.jpg"] = "photo a"; desk.files["Trip/notes.txt"] = "notes"; desk.files["top.txt"] = "top"
        desk.scan()
        repeat(3) { sync(desk, phone) }
        assertEquals(desk.files, phone.files)
        assertEquals(setOf("Trip", "Trip/Day1", "Empty"), phone.dirs)
        assertTrue("the empty folder exists on the phone", "Empty" in phone.dirs)
        repeat(2) { sync(desk, phone) }
        assertEquals("settled", 3, phone.transfers)
    }

    @Test
    fun renamingAFolderMovesItsFilesWithoutResending() {
        val (desk, phone) = pair()
        desk.dirs += "Old"; desk.files["Old/a.txt"] = "aaa"; desk.files["Old/b.txt"] = "bbb"
        desk.scan(); repeat(3) { sync(desk, phone) }
        val before = phone.transfers
        // Renamed on the desk.
        desk.dirs.remove("Old"); desk.dirs += "New"
        desk.files.remove("Old/a.txt"); desk.files.remove("Old/b.txt"); desk.files["New/a.txt"] = "aaa"; desk.files["New/b.txt"] = "bbb"
        desk.scan(); repeat(3) { sync(desk, phone) }
        assertEquals(desk.files, phone.files)
        assertEquals(desk.dirs, phone.dirs)
        assertEquals("nothing was sent again", before, phone.transfers)
        assertFalse("Old" in phone.dirs)
    }

    @Test
    fun deletingAFolderRemovesItAndItsFilesOnTheOtherSide() {
        val (desk, phone) = pair()
        desk.dirs += listOf("A", "A/B"); desk.files["A/B/x.txt"] = "x"; desk.files["A/y.txt"] = "y"
        desk.scan(); repeat(3) { sync(desk, phone) }
        phone.files.clear(); phone.dirs.clear(); phone.scan()
        repeat(4) { sync(desk, phone) }
        assertTrue(desk.files.isEmpty())
        assertTrue("both folders are gone", desk.dirs.isEmpty())
    }

    @Test
    fun aFolderThatStillHoldsAFileOnTheOtherSideIsKeptAndWinsBack() {
        val (desk, phone) = pair()
        desk.dirs += "A"; desk.files["A/x.txt"] = "x"
        desk.scan(); repeat(3) { sync(desk, phone) }
        // The phone removes the whole folder; meanwhile the desk adds a file in it.
        phone.files.clear(); phone.dirs.clear(); phone.scan()
        desk.files["A/new.txt"] = "new"; desk.scan()
        repeat(4) { sync(desk, phone) }
        assertEquals("the edit wins and the folder stays", desk.files, phone.files)
        assertTrue("A" in desk.dirs && "A" in phone.dirs)
        assertTrue("new.txt" in phone.files.keys.map { it.substringAfterLast('/') })
    }

    @Test
    fun bothSidesMakingTheSameFolderMergeIntoOne() {
        val (desk, phone) = pair()
        desk.dirs += "Same"; phone.dirs += "Same"
        desk.scan(); phone.scan()
        repeat(3) { sync(desk, phone) }
        assertEquals(setOf("Same"), desk.dirs)
        assertEquals(setOf("Same"), phone.dirs)
        assertEquals(1, desk.manifest.keys.count { it == "Same" })
    }

    @Test
    fun theSameFileEditedOnBothSidesKeepsBothAsAConflictCopy() {
        val (desk, phone) = pair()
        desk.files["a.txt"] = "base"; desk.scan(); repeat(3) { sync(desk, phone) }
        desk.files["a.txt"] = "desk edit"; phone.files["a.txt"] = "phone edit"; desk.scan(); phone.scan()
        repeat(4) { sync(desk, phone) }
        assertEquals(desk.files, phone.files)
        assertEquals("desk edit", phone.files["a.txt"])
        val copy = phone.files.entries.single { it.key.startsWith("a (conflict from phone ") }
        assertEquals("phone edit", copy.value)
    }

    @Test
    fun aDeletedFileComesBackWhenTheOtherSideEditedIt() {
        val (desk, phone) = pair()
        desk.files["a.txt"] = "base"; desk.scan(); repeat(3) { sync(desk, phone) }
        phone.files.remove("a.txt"); desk.files["a.txt"] = "edited"; phone.scan(); desk.scan()
        repeat(4) { sync(desk, phone) }
        assertEquals("edited", phone.files["a.txt"])
        assertEquals("edited", desk.files["a.txt"])
    }

    @Test
    fun aFolderRenamedOnOneSideWhileAFileInsideChangesOnTheOtherFollowsTheRename() {
        val (desk, phone) = pair()
        desk.dirs += "Old"; desk.files["Old/a.txt"] = "aaa"; desk.files["Old/b.txt"] = "bbb"
        desk.scan(); repeat(3) { sync(desk, phone) }
        // The desk renames the folder; the phone edits a.txt inside it.
        desk.dirs.remove("Old"); desk.dirs += "New"
        desk.files.remove("Old/a.txt"); desk.files.remove("Old/b.txt"); desk.files["New/a.txt"] = "aaa"; desk.files["New/b.txt"] = "bbb"
        phone.files["Old/a.txt"] = "aaa edited"
        desk.scan(); phone.scan()
        repeat(6) { sync(desk, phone) }
        assertEquals(desk.files, phone.files)
        assertTrue("no edit is lost", phone.files.values.contains("aaa edited") && desk.files.values.contains("aaa edited"))
    }

    @Test
    fun turningTheBucketBackOnReconcilesIdenticalFilesWithoutResending() {
        val (desk, phone) = pair()
        // Files exist on both sides from before, with no shared history.
        listOf("a.txt" to "aaa", "b.txt" to "bbb").forEach { (n, c) -> desk.files[n] = c; phone.files[n] = c }
        desk.scan(); phone.scan()
        repeat(3) { sync(desk, phone) }
        assertEquals("identical files are not re-sent", 0, phone.transfers + desk.transfers)
        assertEquals(desk.files, phone.files)
    }

    // ---- paths, depth and names -------------------------------------------------------------------------------

    @Test
    fun depthThreeNamesAndOddCharactersAreSkippedWithAReason() {
        assertNull(FolderSync.skipReason("f.txt", false))
        assertNull(FolderSync.skipReason("A/B/f.txt", false))
        assertNull(FolderSync.skipReason("A/B", true))
        assertEquals(Skip.TOO_DEEP, FolderSync.skipReason("A/B/C/f.txt", false))
        assertEquals(Skip.TOO_DEEP, FolderSync.skipReason("A/B/C", true))
        assertEquals(Skip.NAME_TOO_LONG, FolderSync.skipReason("x".repeat(256), false))
        assertNull(FolderSync.skipReason("é".repeat(127), false))
        assertEquals(Skip.NAME_TOO_LONG, FolderSync.skipReason("é".repeat(128), false))
        listOf("a:b.txt", "a\\b.txt", "trailing.", "trailing ", "bad\u0001.txt", "A/..").forEach { assertEquals(it, Skip.INVALID_NAME, FolderSync.skipReason(it, false)) }
        // Incoming entries follow the same rule, and ignored names stay out.
        assertTrue(FolderSync.takesIncoming("A/B/x.txt", false))
        assertTrue(FolderSync.takesIncoming("A/B", true))
        assertFalse(FolderSync.takesIncoming("A/B/C/x.txt", false))
        assertFalse(FolderSync.takesIncoming(".hidden", false))
        assertFalse(FolderSync.takesIncoming("A/node_modules", true))
    }

    @Test
    fun filesDifferingOnlyInCaseAreReportedAndTheLaterOnesNotSynced() {
        assertEquals("one name of a clashing pair keeps its place, the other is not synced", 1, FolderSync.caseClashes(listOf("a.txt", "a.TXT", "b.txt")).size)
        assertEquals(emptySet<String>(), FolderSync.caseClashes(listOf("a.txt", "A/a.txt")))
        assertEquals("A (2).txt", BucketEngine.uniqueName("A.txt", setOf("a.txt")))
        assertEquals("A.txt", BucketEngine.uniqueName("A.txt", emptySet()))
    }

    @Test
    fun aCaseOnlyRenameIsARenameByHash() {
        val (desk, phone) = pair()
        desk.files["a.txt"] = "same"; desk.scan(); repeat(3) { sync(desk, phone) }
        val before = phone.transfers
        desk.files.remove("a.txt"); desk.files["A.txt"] = "same"; desk.scan(); repeat(3) { sync(desk, phone) }
        assertEquals(setOf("A.txt"), phone.files.keys)
        assertEquals(before, phone.transfers)
    }

    // ---- limits --------------------------------------------------------------------------------------------------

    @Test
    fun aFileOver1GibIsRefusedAndOneAt1GibIsTaken() {
        val r = FolderSync.admit(listOf(gib, gib + 1), bytes = 0, items = 0)
        assertEquals(listOf(0), r.accepted)
        assertEquals(1, r.tooLarge)
        assertFalse(FolderSync.enoughSpace(Long.MAX_VALUE, gib + 1))
        assertTrue(FolderSync.enoughSpace(Long.MAX_VALUE, gib))
    }

    @Test
    fun theBucketRefusesAdditionsPast10GbAndWarnsFrom8() {
        assertEquals(8L shl 30, FolderSync.WARN_BUCKET_BYTES)
        assertEquals(10L shl 30, FolderSync.MAX_BUCKET_BYTES)
        val almostFull = FolderSync.MAX_BUCKET_BYTES - 100
        val r = FolderSync.admit(listOf(100, 101), almostFull, 0)
        assertEquals(listOf(0), r.accepted)
        assertEquals(1, r.bucketFull)
    }

    @Test
    fun oneAddTakesAtMost500ItemsOrFiveGb() {
        val many = FolderSync.admit(List(501) { 1L }, 0, 0)
        assertEquals(500, many.accepted.size)
        assertEquals(1, many.overAddLimit)
        val big = FolderSync.admit(List(6) { gib }, 0, 0)
        assertEquals(5, big.accepted.size)
        assertEquals(1, big.overAddLimit)
    }

    @Test
    fun theBucketHolds10000ItemsAtMost() {
        val r = FolderSync.admit(listOf(1, 1), 0, FolderSync.MAX_BUCKET_ITEMS - 1)
        assertEquals(1, r.accepted.size)
        assertEquals(1, r.bucketFull)
    }

    @Test
    fun phoneStorageBelowTheReserveRefusesIncomingFiles() {
        val mib = 1024L * 1024
        assertTrue(FolderSync.enoughSpace(free = 600 * mib, size = 100 * mib))
        assertFalse(FolderSync.enoughSpace(free = 599 * mib, size = 100 * mib))
    }

    @Test
    fun onlyTwoTransfersAreInFlightPerDirection() {
        val waiting = List(5) { "f$it" }
        assertEquals(listOf("f0"), FolderSync.nextBatch(waiting, 0))
        assertEquals("a slot stays free for a share", true, FolderSync.MAX_IN_FLIGHT < org.desklink.mobile.webrtc.FileV2.ACTIVE_PER_DIRECTION)
        assertEquals(emptyList<String>(), FolderSync.nextBatch(waiting, 1))
        assertEquals(emptyList<String>(), FolderSync.nextBatch(waiting, 7))
    }

    @Test
    fun textAndLinksAreCappedAt1MiB() {
        assertNull(FolderSync.textItem("x".repeat(FolderSync.MAX_TEXT_BYTES), 0))
        assertTrue(FolderSync.textItem("x".repeat(FolderSync.MAX_TEXT_BYTES - 16), 0) != null)
        val (link, desktop) = FolderSync.textItem("https://www.example.org/a?b=1", 0)!!
        assertTrue(link.startsWith("example.org ") && link.endsWith(".url"))
        assertTrue(String(desktop).contains("Type=Link"))
    }

    @Test
    fun aFileIsSentOnlyOnceItsSizeAndTimeHaveHeldStillForTwoSeconds() {
        val a = Stat(100, 5)
        assertFalse("first sighting", FolderSync.settled(null, a, 10_000))
        assertFalse("changed since", FolderSync.settled(Stat(90, 5) to 5_000, a, 10_000))
        assertFalse("still within 2 s", FolderSync.settled(a to 9_000, a, 10_500))
        assertTrue(FolderSync.settled(a to 8_000, a, 10_000))
        assertTrue("folders need no waiting", FolderSync.settled(null, Stat(0, 0, dir = true), 0))
    }

    @Test
    fun anEmptyScanOfABucketThatHadFilesIsHeld() {
        val had = mapOf("a.txt" to Entry(1, 1, "a", false, Vv(0, 1)))
        assertTrue(FolderSync.holdEmpty(had, emptyMap(), confirmed = false))
        assertFalse(FolderSync.holdEmpty(had, emptyMap(), confirmed = true))
        assertFalse(FolderSync.holdEmpty(mapOf("a.txt" to Entry(0, 1, "", true, Vv(0, 2))), emptyMap(), confirmed = false))
    }

    // ---- multi-select ----------------------------------------------------------------------------------------------

    @Test
    fun movingIntoAFolderIsRefusedPerItemWhenItWouldPassTheDepthLimit() {
        // A folder "A" with a subfolder "A/B" cannot go into "X/Y" (A/B would become X/Y/A/B: 4 levels).
        assertNull(FolderSync.moveInto("X/Y", "A", true, listOf("A/B" to true)))
        // Into "X" a folder holding only files fits (X/A/f.txt), but one holding a subfolder does not (X/A/B).
        assertEquals("X/A", FolderSync.moveInto("X", "A", true, listOf("A/f.txt" to false)))
        assertNull(FolderSync.moveInto("X", "A", true, listOf("A/B" to true)))
        // A file goes into a level-2 folder.
        assertEquals("X/Y/f.txt", FolderSync.moveInto("X/Y", "f.txt", false, emptyList()))
        // Not into itself or below, and not where it already is.
        assertNull(FolderSync.moveInto("A/B", "A", true, emptyList()))
        assertNull(FolderSync.moveInto("A", "A", true, emptyList()))
        assertNull(FolderSync.moveInto("", "f.txt", false, emptyList()))
    }

    private fun item(path: String, dir: Boolean = false, size: Long = 10, state: BucketItem.State = BucketItem.State.SYNCED, progress: Progress? = null) =
        BucketItem(path.substringAfterLast('/'), size, 1, Side.DESK, state, null, progress, path, dir)

    @Test
    fun folderTilesRollUpCountSizeStateAndProgress() {
        val items = listOf(
            item("A", dir = true), item("A/B", dir = true), item("A/B/x.mp4", size = 100, state = BucketItem.State.RECEIVING, progress = Progress(40, 100, 5, 10)),
            item("A/y.txt", size = 5), item("top.txt", size = 7),
        )
        val top = BucketLevel.tiles(items, "")
        assertEquals(listOf("A", "top.txt"), top.map { it.item.path })
        val a = top.first()
        assertEquals(3, a.count)
        assertEquals(105L, a.bytes)
        assertEquals(BucketItem.State.RECEIVING, a.state)
        assertEquals(40L, a.progress!!.done)
        assertEquals(listOf("A/B", "A/y.txt"), BucketLevel.tiles(items, "A").map { it.item.path })
        val withConflict = items + item("A/z.txt", state = BucketItem.State.CONFLICT)
        assertEquals(BucketItem.State.CONFLICT, BucketLevel.tiles(withConflict, "").first().state)
    }

    @Test
    fun breadcrumbsAndFolderCreationFollowTheDepthLimit() {
        assertEquals(listOf("Bucket" to "", "A" to "A", "B" to "A/B"), BucketLevel.crumbs("A/B", "Bucket"))
        assertTrue(BucketLevel.canMakeFolder(""))
        assertTrue(BucketLevel.canMakeFolder("A"))
        assertFalse("hidden at level 2", BucketLevel.canMakeFolder("A/B"))
        val items = listOf(item("A", dir = true), item("A/B", dir = true), item("C", dir = true), item("f.txt"))
        assertEquals(listOf("C"), BucketLevel.targets(items, listOf("A")))
        assertTrue("the top is offered to a nested item", "" in BucketLevel.targets(items, listOf("A/B")))
        assertFalse("not into itself or below", BucketLevel.targets(items, listOf("A")).any { it == "A" || it.startsWith("A/") })
    }

    @Test
    fun theComputersFolderNameIsAValidSingleName() {
        assertEquals("manas-82xf", SafTree.folderName("manas-82xf"))
        assertEquals("a b", SafTree.folderName("a/b".replace("/", " ")))
        assertEquals("Computer", SafTree.folderName("///"))
        assertTrue(SafTree.folderName("é".repeat(300)).toByteArray().size <= FolderSync.MAX_SEGMENT_BYTES)
        assertEquals("pc", SafTree.folderName("..pc.."))
    }

    @Test
    fun aFileThatCannotBeSetAsideIsAnErrorNotASilentReturn() {
        val dir = java.nio.file.Files.createTempDirectory("bucket-stash").toFile()
        try {
            org.junit.Assert.assertThrows(java.io.IOException::class.java) { BucketEngine.setAside(java.io.File(dir, "gone"), java.io.File(dir, "stash")) }
            val part = java.io.File(dir, "part").apply { writeText("x") }
            BucketEngine.setAside(part, java.io.File(dir, "stash"))
            org.junit.Assert.assertTrue(java.io.File(dir, "stash").exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
