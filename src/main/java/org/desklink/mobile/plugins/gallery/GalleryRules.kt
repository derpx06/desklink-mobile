/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.gallery

import org.json.JSONArray
import java.io.File

/** Gallery (W12): which new images may go to the computer, how many, and what was already sent. Pure, so each rule has a test. */
object GalleryRules {
    val TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif", "image/gif")
    const val MAX_BYTES = 50L * 1024 * 1024
    const val MAX_AGE_MS = 24L * 3600 * 1000
    const val MAX_FILES_PER_HOUR = 20
    const val MAX_BYTES_PER_HOUR = 200L * 1024 * 1024
    const val HOUR_MS = 3600_000L
    const val LEDGER_SIZE = 2000

    /** A file found in a picked folder. [uri] is its document URI. */
    data class Found(val uri: String, val name: String, val mime: String, val size: Long, val mtime: Long)

    /**
     * The files to send now, oldest first: an image of an allowed type and size, not older than 24 hours, not there before the
     * switch was turned on ([enabledAt]), and not already sent.
     */
    fun toSend(found: List<Found>, ledger: Ledger, enabledAt: Long, now: Long): List<Found> = found
        .filter { it.mime in TYPES && it.size in 1..MAX_BYTES && it.mtime >= enabledAt && now - it.mtime <= MAX_AGE_MS && !ledger.has(it.uri, it.mtime) }
        .sortedBy { it.mtime }

    /** At most [MAX_FILES_PER_HOUR] files and [MAX_BYTES_PER_HOUR] bytes in any rolling hour (the computer counts too). */
    class Budget(private val now: () -> Long = System::currentTimeMillis) {
        private val sent = ArrayDeque<Pair<Long, Long>>()

        @Synchronized
        fun allows(size: Long): Boolean {
            trim()
            return sent.size < MAX_FILES_PER_HOUR && sent.sumOf { it.second } + size <= MAX_BYTES_PER_HOUR
        }

        @Synchronized
        fun record(size: Long) { trim(); sent.addLast(now() to size) }

        /** When room opens up again, for a retry. */
        @Synchronized
        fun nextRoomAt(): Long = trim().let { sent.firstOrNull()?.first?.plus(HOUR_MS) ?: now() }

        private fun trim() { val t = now(); while (sent.isNotEmpty() && t - sent.first().first >= HOUR_MS) sent.removeFirst() }
    }

    /** What was sent, by (uri, mtime): the newest [LEDGER_SIZE] kept in a file, so a restart sends nothing twice. */
    class Ledger(private val file: File) {
        private val entries = LinkedHashSet<String>()

        init {
            runCatching {
                if (file.exists()) JSONArray(file.readText()).let { a -> for (i in 0 until a.length()) entries.add(a.getString(i)) }
            }
        }

        private fun key(uri: String, mtime: Long) = "$mtime|$uri"

        @Synchronized
        fun has(uri: String, mtime: Long) = key(uri, mtime) in entries

        @Synchronized
        fun add(uri: String, mtime: Long) {
            entries.remove(key(uri, mtime))
            entries.add(key(uri, mtime))
            while (entries.size > LEDGER_SIZE) entries.remove(entries.first())
            save()
        }

        @Synchronized
        fun remove(uri: String, mtime: Long) {
            if (entries.remove(key(uri, mtime))) save()
        }

        private fun save() {
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.path + ".tmp")
                tmp.writeText(JSONArray(entries.toList()).toString())
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            }
        }
    }

    /** What the computer's refusal means for the file just offered. */
    enum class Refusal { NONE, OFF, LIMIT, TYPE }

    fun refusalOf(message: String?): Refusal = when {
        message == null -> Refusal.NONE
        "gallery-off" in message -> Refusal.OFF
        "gallery-limit" in message -> Refusal.LIMIT
        "gallery-type" in message -> Refusal.TYPE
        else -> Refusal.NONE
    }
}
