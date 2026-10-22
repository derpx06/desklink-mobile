/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.notes

import org.desklink.mobile.NetworkPacket
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NotesIndexRevTest {
    private fun index(vararg items: Triple<String, Long, Boolean>, type: String = "desklink.notes") = NetworkPacket(type).apply {
        this["op"] = "index"
        this["items"] = JSONArray(items.map { JSONObject().put("id", it.first).put("modified", it.second).apply { if (it.third) put("deleted", true) } })
    }

    @Test
    fun twoCallsGiveTheSameRevEvenThoughEachPacketCarriesAFreshTimestampId() {
        val a = index(Triple("n1", 10L, false), Triple("n2", 20L, true))
        Thread.sleep(5)
        val b = index(Triple("n2", 20L, true), Triple("n1", 10L, false))
        assertNotEquals("the packets differ by their own id", a.serialize(), b.serialize())
        assertEquals(NotesPlugin.indexRev(listOf(a)), NotesPlugin.indexRev(listOf(b)))
    }

    @Test
    fun anEditADeletionOrANewItemChangesTheRev() {
        val base = NotesPlugin.indexRev(listOf(index(Triple("n1", 10L, false))))
        assertNotEquals(base, NotesPlugin.indexRev(listOf(index(Triple("n1", 11L, false)))))
        assertNotEquals(base, NotesPlugin.indexRev(listOf(index(Triple("n1", 10L, true)))))
        assertNotEquals(base, NotesPlugin.indexRev(listOf(index(Triple("n1", 10L, false), Triple("n2", 1L, false)))))
    }

    @Test
    fun theSameItemsSplitAcrossPacketsAreTheSameIndex() {
        val whole = NotesPlugin.indexRev(listOf(index(Triple("a", 1L, false), Triple("b", 2L, false))))
        val split = NotesPlugin.indexRev(listOf(index(Triple("b", 2L, false)), index(Triple("a", 1L, false))))
        assertEquals(whole, split)
    }
}
