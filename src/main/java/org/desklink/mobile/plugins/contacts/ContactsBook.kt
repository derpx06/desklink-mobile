/*
 * SPDX-FileCopyrightText: 2018 Simon Redman <simon@ergotech.com>
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.contacts

import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import java.text.SimpleDateFormat
import android.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One contact as the desk shows it (contract F5 K3). */
data class ContactCard(
    val uid: String,
    val displayName: String?,
    /** Family, given, middle, prefix, suffix. */
    val name: List<String> = emptyList(),
    /** (vCard TYPE, number). */
    val phones: List<Pair<String, String>> = emptyList(),
    val emails: List<String> = emptyList(),
    val org: List<String> = emptyList(),
    val note: String? = null,
    /** A JPEG at most [ContactsBook.PHOTO_SIZE] px on its long side. */
    val photo: ByteArray? = null,
    val updatedAt: Long = 0,
)

/** The pure half of contacts (F5): packets, vCards and sizes. Unit-tested. */
object ContactsBook {
    const val PHOTO_SIZE = 96

    /** One name part, number, email or organisation: longer is junk, and it keeps cards small. */
    const val MAX_FIELD_CHARS = 256
    /** One packet's serialized size: the WebRTC payload bound is 128 KiB, with room for the frame. */
    const val PACKET_BUDGET = 100 * 1024

    /** K1: the complete snapshot. Most recently changed first, so a book too big for one packet loses its oldest. */
    fun snapshotPacket(stamps: Map<String, Long>): NetworkPacket {
        val packet = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_CONTACTS_RESPONSE_UIDS_TIMESTAMPS)
        // ponytail: one packet holds ~3,000 contacts (uid = Contacts._ID); a bigger book needs a
        // multi-part snapshot, which the contract does not have yet.
        var size = 64
        val uids = stamps.entries.sortedByDescending { it.value }
            .takeWhile { (uid, stamp) -> (size + 2 * uid.length + stamp.toString().length + 12).also { size = it } <= PACKET_BUDGET }
            .map { it.key }
        packet["uids"] = uids
        uids.forEach { packet[it] = stamps.getValue(it).toString() }
        packet["sharing"] = true
        return packet
    }

    /** Sharing is off (or there is no permission): the desk deletes its whole cache for this phone. */
    fun notSharingPacket() = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_CONTACTS_RESPONSE_UIDS_TIMESTAMPS).apply {
        this["uids"] = emptyList<String>()
        this["sharing"] = false
    }

    /** K3: the vCards, split so each packet stays within [budget]. */
    fun vcardPackets(cards: Map<String, String>, budget: Int = PACKET_BUDGET): List<NetworkPacket> {
        val packets = mutableListOf<NetworkPacket>()
        var batch = mutableListOf<Pair<String, String>>()
        var size = 0
        fun flush() {
            if (batch.isEmpty()) return
            packets += NetworkPacket(DeskLinkProtocol.PACKET_TYPE_CONTACTS_RESPONSE_VCARDS).apply {
                this["uids"] = batch.map { it.first }
                batch.forEach { (uid, card) -> this[uid] = card }
            }
            batch = mutableListOf()
            size = 0
        }
        for ((uid, card) in cards) {
            // UTF-8 bytes; JSON escapes CR/LF as two characters each.
            val cost = 2 * uid.length + card.toByteArray().size + card.count { it == '\n' || it == '\r' } + 16
            if (cost > budget) continue // ponytail: one oversized card is skipped; the desk asks again after the next snapshot
            if (size + cost > budget - 64) flush()
            batch += uid to card
            size += cost
        }
        flush()
        return packets
    }

    /** A vCard 3.0 (RFC 2426) with the fields the desk reads. */
    fun vcard(card: ContactCard): String = buildString {
        fun line(text: String) = append(text).append("\r\n")
        line("BEGIN:VCARD")
        line("VERSION:3.0")
        val fn = card.displayName?.takeIf(String::isNotBlank)
            ?: card.name.let { n -> listOf(3, 1, 2, 0, 4).mapNotNull { n.getOrNull(it)?.takeIf(String::isNotBlank) }.joinToString(" ") }
        line("FN:${escape(fn)}")
        line("N:${(0 until 5).joinToString(";") { escape(card.name.getOrNull(it).orEmpty()) }}")
        card.phones.forEach { (type, number) -> line("TEL;TYPE=$type:${escape(number)}") }
        card.emails.forEach { line("EMAIL;TYPE=INTERNET:${escape(it)}") }
        if (card.org.any(String::isNotBlank)) line("ORG:${card.org.joinToString(";") { escape(it) }}")
        card.note?.takeIf(String::isNotBlank)?.let { line("NOTE:${escape(it, MAX_NOTE_CHARS)}") }
        card.photo?.let { line("PHOTO;ENCODING=b;TYPE=JPEG:${Base64.encodeToString(it, Base64.NO_WRAP)}") }
        if (card.updatedAt > 0) line("REV:${rev(card.updatedAt)}")
        append("END:VCARD")
    }

    /** RFC 2426 text escaping; other control characters are dropped. Cut at [max] characters first. */
    fun escape(text: String, max: Int = MAX_FIELD_CHARS): String = buildString {
        for (c in text.replace("\r\n", "\n").take(max)) {
            when (c) {
                '\\' -> append("\\\\")
                ',' -> append("\\,")
                ';' -> append("\\;")
                '\n', '\r' -> append("\\n")
                else -> if (!c.isISOControl()) append(c)
            }
        }
    }

    /** The vCard TYPE for an Android `Phone.TYPE_*`. */
    fun phoneType(androidType: Int): String = when (androidType) {
        2 -> "CELL" // TYPE_MOBILE
        1 -> "HOME"
        3 -> "WORK"
        4, 5, 13 -> "FAX" // TYPE_FAX_WORK, TYPE_FAX_HOME, TYPE_OTHER_FAX
        6 -> "PAGER"
        17 -> "WORK,CELL" // TYPE_WORK_MOBILE
        else -> "VOICE"
    }

    /** K3: a contact's note, cut (the desk shows a few lines). */
    const val MAX_NOTE_CHARS = 2000

    /** The largest power of two that keeps a [width]×[height] image at least [PHOTO_SIZE] on its long side. */
    fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= PHOTO_SIZE) sample *= 2
        return sample
    }

    /** A photo [width]×[height] scaled to fit [PHOTO_SIZE] on its long side; never enlarged, never 0. */
    fun photoSize(width: Int, height: Int, max: Int = PHOTO_SIZE): Pair<Int, Int> {
        val long = maxOf(width, height)
        if (long <= max) return width to height
        return maxOf(1, width * max / long) to maxOf(1, height * max / long)
    }

    private fun rev(millis: Long): String =
        SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))
}
