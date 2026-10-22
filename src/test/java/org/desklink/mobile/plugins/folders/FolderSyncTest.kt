/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.folders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import kotlin.random.Random

class FolderSyncTest {
    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** A side's files and when each last changed. */
    private class Side(val files: MutableMap<String, String> = sortedMapOf(), val times: MutableMap<String, Long> = HashMap())

    private var clock = 1_000L

    private fun manifest(side: Side, base: Map<String, Entry>): Map<String, Entry> {
        val out = HashMap<String, Entry>()
        side.files.forEach { (p, c) -> out[p] = Entry(p, c.length.toLong(), side.times[p] ?: 0, sha(c)) }
        base.forEach { (p, e) -> if (p !in side.files) out[p] = if (e.deleted) e else Entry(p, 0, side.times[p] ?: clock, null, deleted = true) }
        return out
    }

    private fun apply(ops: List<Op>, local: Side, remote: Side) {
        for (op in ops) when (op) {
            is Op.Upload -> remote.files[op.relpath] = local.files.getValue(op.relpath)
            is Op.Download -> local.files[op.relpath] = remote.files.getValue(op.relpath)
            is Op.DeleteHere -> local.files.remove(op.relpath)
            is Op.DeleteThere -> remote.files.remove(op.relpath)
            is Op.RenameHere -> local.files[op.relpath] = local.files.remove(op.from)!!
            is Op.RenameThere -> remote.files[op.relpath] = remote.files.remove(op.from)!!
            is Op.Conflict -> {
                val (winner, loser) = if (op.keepLocal) local.files.getValue(op.relpath) to remote.files.getValue(op.relpath)
                else remote.files.getValue(op.relpath) to local.files.getValue(op.relpath)
                local.files[op.relpath] = winner; remote.files[op.relpath] = winner
                local.files[op.copyName] = loser; remote.files[op.copyName] = loser
            }
        }
    }

    private fun sync(local: Side, remote: Side, base: Map<String, Entry>, mode: Mode = Mode.TWO_WAY): Pair<List<Op>, Map<String, Entry>> {
        val ops = FolderSync.diff(base, manifest(local, base), manifest(remote, base), mode, "desk", "phone")
        apply(ops, local, remote)
        return ops to manifest(local, emptyMap())
    }

    @Test
    fun randomEditsOnBothSidesConvergeWithoutLosingAnEdit() {
        val random = Random(42)
        val names = listOf("a.txt", "b.txt", "dir/c.md", "dir/d.md", "e.bin")
        repeat(300) { round ->
            val local = Side(); val remote = Side()
            names.take(3).forEach { local.files[it] = "v0-$it"; remote.files[it] = "v0-$it" }
            var base = manifest(local, emptyMap())
            val baseContents = local.files.values.toSet()
            // Random edits on each side.
            for (side in listOf(local, remote)) repeat(random.nextInt(4)) {
                val name = names.random(random)
                clock++
                when (random.nextInt(4)) {
                    0, 1 -> { val c = "r$round-$clock"; side.files[name] = c; side.times[name] = clock }
                    2 -> side.files.remove(name)
                    else -> side.files.remove(name)?.let { c -> val to = "moved/$name"; side.files[to] = c; side.times[to] = clock }
                }
            }
            val edits = (local.files.values + remote.files.values).toSet() - baseContents
            val (_, agreed) = sync(local, remote, base)
            assertEquals("round $round converges", local.files, remote.files)
            // No edit is lost: whatever either side wrote and still had is still somewhere.
            assertTrue("round $round keeps every edit", local.files.values.containsAll(edits))
            base = agreed
            val (again, _) = sync(local, remote, base)
            assertTrue("round $round: a second sync does nothing, got $again", again.isEmpty())
        }
    }

    @Test
    fun concurrentEditsKeepBothAndADeleteNeverBeatsAnEdit() {
        val local = Side(sortedMapOf("n.txt" to "base")); val remote = Side(sortedMapOf("n.txt" to "base"))
        val base = manifest(local, emptyMap())
        local.files["n.txt"] = "phone"; local.times["n.txt"] = 10
        remote.files["n.txt"] = "desk"; remote.times["n.txt"] = 20
        val (ops, _) = sync(local, remote, base)
        val conflict = ops.single() as Op.Conflict
        assertFalse(conflict.keepLocal)
        assertEquals("desk", local.files["n.txt"])
        assertTrue(conflict.copyName.startsWith("n (conflict from phone ") && conflict.copyName.endsWith(").txt"))
        assertEquals("phone", remote.files[conflict.copyName])

        val l2 = Side(sortedMapOf("k.txt" to "x")); val r2 = Side(sortedMapOf("k.txt" to "x"))
        val b2 = manifest(l2, emptyMap())
        l2.files.remove("k.txt")
        r2.files["k.txt"] = "edited"; r2.times["k.txt"] = 5
        sync(l2, r2, b2)
        assertEquals("edited", l2.files["k.txt"])
    }

    @Test
    fun aMoveIsARenameNotATransfer() {
        val local = Side(sortedMapOf("old.jpg" to "pixels")); val remote = Side(sortedMapOf("old.jpg" to "pixels"))
        val base = manifest(local, emptyMap())
        local.files["new.jpg"] = local.files.remove("old.jpg")!!
        val (ops, _) = sync(local, remote, base)
        assertEquals(listOf(Op.RenameThere("old.jpg", "new.jpg")), ops)
    }

    @Test
    fun oneWayMirrorsButNeverDeletesWhatTheSourceNeverHad() {
        val local = Side(sortedMapOf("mine.txt" to "phone only")); val remote = Side(sortedMapOf("desk.txt" to "d"))
        sync(local, remote, emptyMap(), Mode.DESK_TO_PHONE)
        assertEquals("phone only", local.files["mine.txt"])
        assertEquals("d", local.files["desk.txt"])
    }

    @Test
    fun pathsAreBoundedAndSafe() {
        assertTrue(FolderSync.validRelpath("Photos/2026/a.jpg"))
        listOf("", "/abs", "a/../b", "a/./b", "a//b", "a\\b", "a/\u0000b", "x".repeat(FolderSync.MAX_SEGMENT_BYTES + 1))
            .forEach { assertFalse(it, FolderSync.validRelpath(it)) }
        assertFalse(FolderSync.validRelpath(List(FolderSync.MAX_DEPTH + 1) { "d" }.joinToString("/")))
        assertFalse(FolderSync.validRelpath(List(20) { "y".repeat(250) }.joinToString("/")))
        assertTrue(FolderSync.ignored("project/node_modules/x.js"))
        assertTrue(FolderSync.ignored(".git/HEAD"))
        assertTrue(FolderSync.ignored("a/download.crdownload"))
        assertTrue(FolderSync.ignored("a/.hidden"))
        assertFalse(FolderSync.ignored("a/.hidden", includeHidden = true))
        assertFalse(FolderSync.ignored("docs/report.pdf"))
        val long = FolderSync.conflictName("d/" + "s".repeat(250) + ".txt", "desk", 0)
        assertTrue(long.substringAfter('/').toByteArray().size <= FolderSync.MAX_SEGMENT_BYTES)
        assertTrue(long.endsWith(").txt"))
    }

    @Test
    fun tombstonesExpire() {
        val day = 24 * 3600 * 1000L
        val entries = mapOf("a" to Entry("a", 0, 0, null, deleted = true), "b" to Entry("b", 1, 0, "x"))
        assertEquals(setOf("b"), FolderSync.prune(entries, FolderSync.TOMBSTONE_DAYS * day + 1).keys)
    }
}
