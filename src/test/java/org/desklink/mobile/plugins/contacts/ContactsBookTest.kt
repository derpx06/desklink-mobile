/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.plugins.contacts

import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Contract F5 K1–K3 on the phone's side. Robolectric for android.util.Base64 (the photo). */
@RunWith(RobolectricTestRunner::class)
class ContactsBookTest {

    @Test
    fun aVCardCarriesTheFieldsTheDeskReadsEscaped() {
        val card = ContactsBook.vcard(
            ContactCard(
                uid = "7",
                displayName = "Ada, Countess; of Lovelace",
                name = listOf("Lovelace", "Ada", "", "Lady", ""),
                phones = listOf("CELL" to "+44 20 7946 0000", "WORK" to "020 7946 0001"),
                emails = listOf("ada@example.org"),
                org = listOf("Analytical; Engines", "R&D"),
                note = "Line one\r\nline two \\ backslash\u0007",
                photo = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01),
                updatedAt = 1_761_130_500_000,
            ),
        )
        val lines = card.split("\r\n")
        assertEquals("BEGIN:VCARD", lines.first())
        assertEquals("VERSION:3.0", lines[1])
        assertEquals("END:VCARD", lines.last())
        assertTrue("FN:Ada\\, Countess\\; of Lovelace" in lines)
        assertTrue("N:Lovelace;Ada;;Lady;" in lines)
        assertTrue("TEL;TYPE=CELL:+44 20 7946 0000" in lines)
        assertTrue("TEL;TYPE=WORK:020 7946 0001" in lines)
        assertTrue("EMAIL;TYPE=INTERNET:ada@example.org" in lines)
        assertTrue("ORG:Analytical\\; Engines;R&D" in lines)
        // CRLF is one escaped newline; a backslash doubles; other controls are dropped.
        assertTrue("NOTE:Line one\\nline two \\\\ backslash" in lines)
        assertTrue("PHOTO;ENCODING=b;TYPE=JPEG:/9j/AQ==" in lines)
        assertTrue("REV:20251022T105500Z" in lines)
        // No raw line break inside any value: every line is a property.
        assertTrue(lines.all { it.contains(':') })
    }

    @Test
    fun withoutADisplayNameFnComesFromTheStructuredNameAndEmptyFieldsAreLeftOut() {
        val lines = ContactsBook.vcard(ContactCard("1", null, name = listOf("Hopper", "Grace", "Brewster", "Rear Admiral", ""))).split("\r\n")
        assertTrue("FN:Rear Admiral Grace Brewster Hopper" in lines)
        assertFalse(lines.any { it.startsWith("ORG") || it.startsWith("NOTE") || it.startsWith("PHOTO") || it.startsWith("TEL") || it.startsWith("REV") })
    }

    @Test
    fun theSnapshotIsCompleteWithTimestampsAsDecimalStrings() {
        val packet = ContactsBook.snapshotPacket(mapOf("12" to 1_700_000_000_123, "3" to 5))
        assertEquals(DeskLinkProtocol.PACKET_TYPE_CONTACTS_RESPONSE_UIDS_TIMESTAMPS, packet.type)
        assertEquals(listOf("12", "3"), packet.getStringList("uids"))
        assertEquals("1700000000123", packet.getString("12"))
        assertEquals("5", packet.getString("3"))
        assertTrue(packet.getBoolean("sharing"))
        assertTrue(packet.serialize().length < ContactsBook.PACKET_BUDGET)
    }

    @Test
    fun aBookTooBigForOnePacketKeepsItsMostRecentlyChanged() {
        val stamps = (1..10_000).associate { it.toString() to 1_700_000_000_000L + it }
        val packet = ContactsBook.snapshotPacket(stamps)
        val uids = packet.getStringList("uids")!!
        assertTrue(uids.size in 2_000 until 10_000)
        assertEquals("10000", uids.first())
        assertTrue(packet.serialize().toByteArray().size <= ContactsBook.PACKET_BUDGET)
    }

    @Test
    fun turnedOffIsAnEmptySnapshotThatSaysSo() {
        val packet = ContactsBook.notSharingPacket()
        assertEquals(DeskLinkProtocol.PACKET_TYPE_CONTACTS_RESPONSE_UIDS_TIMESTAMPS, packet.type)
        assertEquals(emptyList<String>(), packet.getStringList("uids"))
        assertFalse(packet.getBoolean("sharing"))
    }

    @Test
    fun vCardRepliesSplitUnderTheBudgetAndKeepEveryCard() {
        val cards = (1..50).associate { it.toString() to "BEGIN:VCARD\r\nFN:${"x".repeat(3_000)}\r\nEND:VCARD" }
        val packets = ContactsBook.vcardPackets(cards, budget = 20_000)
        assertTrue(packets.size > 1)
        packets.forEach {
            assertEquals(DeskLinkProtocol.PACKET_TYPE_CONTACTS_RESPONSE_VCARDS, it.type)
            assertTrue(it.serialize().toByteArray().size <= 20_000)
        }
        val sent = packets.flatMap { p -> p.getStringList("uids")!!.map { it to p.getString(it) } }.toMap()
        assertEquals(cards, sent)
        assertTrue(ContactsBook.vcardPackets(emptyMap()).isEmpty())
    }

    @Test
    fun photosShrinkToNinetySixOnTheLongSideAndNeverGrow() {
        assertEquals(96 to 72, ContactsBook.photoSize(720, 540))
        assertEquals(54 to 96, ContactsBook.photoSize(540, 960))
        assertEquals(96 to 96, ContactsBook.photoSize(96, 96))
        assertEquals(40 to 30, ContactsBook.photoSize(40, 30))
        assertEquals(96 to 1, ContactsBook.photoSize(4000, 10))
    }

    @Test
    fun androidPhoneTypesMapToVCardTypes() {
        assertEquals("CELL", ContactsBook.phoneType(2))
        assertEquals("HOME", ContactsBook.phoneType(1))
        assertEquals("WORK", ContactsBook.phoneType(3))
        assertEquals("FAX", ContactsBook.phoneType(4))
        assertEquals("VOICE", ContactsBook.phoneType(0))
    }
}
