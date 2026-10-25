/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.protocol

import org.desklink.mobile.plugins.contacts.ContactCard
import org.desklink.mobile.plugins.contacts.ContactsBook
import org.desklink.mobile.plugins.inputdevicesreceiver.InputDevicesReceiverPlugin
import org.desklink.mobile.plugins.notes.Merged
import org.desklink.mobile.plugins.notes.Note
import org.desklink.mobile.plugins.notes.Shelf
import org.desklink.mobile.plugins.screen.ScreenControlPlugin
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The vectors the desktop wrote (workspace protocol/testdata/desklink_v9/, byte-identical copies): the phone agrees with each. */
class DeskSharedFixturesTest {
    private fun fixture(name: String) = JSONObject(javaClass.getResourceAsStream("/shared/$name.json")!!.bufferedReader().readText())
    private fun cases(f: JSONObject): List<JSONObject> = f.getJSONArray("cases").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    @Test
    fun glideBodiesTheDeskReadsAreWhatThePhoneSends() {
        val ours = mapOf(
            "allowed on" to InputDevicesReceiverPlugin.statePacket(true, false),
            "allowed and entered" to InputDevicesReceiverPlugin.statePacket(true, true),
            "allowed off" to InputDevicesReceiverPlugin.statePacket(false, false),
            "entered needs allowed" to InputDevicesReceiverPlugin.statePacket(false, true),
        )
        for (case in cases(fixture("glide_bodies"))) {
            val expected = ours[case.getString("name")] ?: continue
            val body = case.getJSONObject("body")
            // The phone says only what the fixture's body says (allowed, and entered when true).
            assertEquals(case.getString("name"), body.optBoolean("allowed"), expected.getBoolean("allowed", !body.optBoolean("allowed")))
            if (case.getString("name") == "entered needs allowed") assertTrue("entered never without allowed", !expected.has("entered"))
            else assertEquals(case.getString("name"), body.has("entered"), expected.has("entered"))
        }
        val release = cases(fixture("glide_bodies")).first { it.getString("name") == "release beats allowed" }.getJSONObject("body")
        val sent = InputDevicesReceiverPlugin.releasePacket(release.getInt("releaseDeltax"), release.getInt("releaseDeltay"))
        assertEquals(release.getInt("releaseDeltax"), sent.getInt("releaseDeltax"))
        assertEquals(release.getInt("releaseDeltay"), sent.getInt("releaseDeltay"))
        assertTrue("a release carries no allowed", !sent.has("allowed"))
    }

    @Test
    fun theScreenRequestStaysInsideTheRangesTheDeskServes() {
        val f = fixture("control_vectors")
        val dims = f.getJSONObject("ranges").getJSONArray("maxDimension")
        val fps = f.getJSONObject("ranges").getJSONArray("fps")
        val packet = ScreenControlPlugin.createScreenRequestPacket("viewer", ScreenControlPlugin.DEFAULT_MAX_DIMENSION, ScreenControlPlugin.DEFAULT_FPS, 80)
        assertTrue(packet.getInt("maxDimension") in dims.getInt(0)..dims.getInt(1))
        assertTrue(packet.getInt("fps") in fps.getInt(0)..fps.getInt(1))
        assertEquals(f.getJSONObject("defaults").getInt("maxDimension"), ScreenControlPlugin.DEFAULT_MAX_DIMENSION)
    }

    private fun note(o: JSONObject): Note = Note(
        id = o.getString("id"), body = o.getString("body"), created = o.getLong("modified"), modified = o.getLong("modified"),
        parent = o.optLong("parent", 0), deleted = o.optBoolean("deleted", false),
        ancestors = o.optJSONArray("ancestors")?.let { a: JSONArray -> (0 until a.length()).map { a.getLong(it) } } ?: emptyList(),
    )

    @Test
    fun notesMergeGivesTheSameOutcomesAsTheDesk() {
        for (case in cases(fixture("notes_merge"))) {
            val shelf = Shelf()
            case.optJSONObject("local")?.let { shelf.notes[it.getString("id")] = note(it) }
            val outcome = shelf.mergeNote(note(case.getJSONObject("incoming")))
            val expected = when (case.getString("outcome")) {
                "taken" -> Merged.TAKEN
                "keptOurs" -> Merged.KEPT_OURS
                "same" -> Merged.SAME
                "conflict" -> Merged.CONFLICT
                else -> error("unknown outcome")
            }
            assertEquals(case.getString("name"), expected, outcome)
        }
    }

    @Test
    fun theVcardsThePhoneWritesCarryWhatTheDeskReads() {
        for (case in cases(fixture("contacts_bodies"))) {
            val phones = case.getJSONArray("phones").let { a -> (0 until a.length()).map { a.getJSONArray(it) } }
            val typeOf = mapOf("Mobile" to "CELL", "Home" to "HOME", "Work" to "WORK")
            val card = ContactsBook.vcard(
                ContactCard(
                    uid = case.getString("uid"), displayName = case.getString("name"),
                    phones = phones.map { typeOf.getValue(it.getString(0)) to it.getString(1) },
                    emails = case.getJSONArray("emails").let { a -> (0 until a.length()).map { a.getString(it) } },
                ),
            )
            val lines = card.split("\r\n")
            assertTrue(case.getString("case"), "FN:${case.getString("name")}" in lines)
            phones.forEach { p -> assertTrue(case.getString("case"), "TEL;TYPE=${typeOf.getValue(p.getString(0))}:${p.getString(1)}" in lines) }
            for (i in 0 until case.getJSONArray("emails").length()) assertTrue(lines.any { it.startsWith("EMAIL") && it.endsWith(":" + case.getJSONArray("emails").getString(i)) })
            assertTrue(lines.first() == "BEGIN:VCARD" && lines.last() == "END:VCARD")
        }
    }
}
