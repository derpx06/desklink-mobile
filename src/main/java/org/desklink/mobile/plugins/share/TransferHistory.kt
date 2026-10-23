/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.plugins.share

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.NetworkPacket
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID
import java.util.WeakHashMap

/** One file sent to or received from a computer. */
data class TransferRecord(
    val id: String,
    val deviceId: String,
    /** The computer's name when it happened; it may be unpaired since. */
    val deviceName: String,
    val name: String,
    val size: Long,
    val sent: Boolean,
    val time: Long,
    /** content:// or file:// */
    val uri: String,
    val mime: String?,
)

/** How a file is previewed in the transfers grid. */
enum class PreviewKind { IMAGE, VIDEO, AUDIO, PDF, TEXT, ARCHIVE, OTHER }

/** Whether a picture of the file's content can exist; other kinds show their type icon at once, never a grey placeholder. */
val PreviewKind.hasPicture: Boolean get() = this == PreviewKind.IMAGE || this == PreviewKind.VIDEO || this == PreviewKind.PDF

fun previewKind(mime: String?, name: String): PreviewKind {
    val type = mime?.lowercase()?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
        ?: mimeForExtension(name.substringAfterLast('.', "").lowercase())
    return when {
        type == null -> PreviewKind.OTHER
        type.startsWith("image/") -> PreviewKind.IMAGE
        type.startsWith("video/") -> PreviewKind.VIDEO
        type.startsWith("audio/") -> PreviewKind.AUDIO
        type == "application/pdf" -> PreviewKind.PDF
        type.startsWith("text/") || type == "application/json" || type == "application/xml" -> PreviewKind.TEXT
        type in ARCHIVES -> PreviewKind.ARCHIVE
        else -> PreviewKind.OTHER
    }
}

private val ARCHIVES = setOf(
    "application/zip", "application/x-tar", "application/gzip", "application/x-gzip", "application/x-7z-compressed",
    "application/x-rar-compressed", "application/vnd.rar", "application/x-bzip2", "application/x-xz", "application/zstd",
)

/** The extensions that matter here, without the platform's MimeTypeMap (absent in unit tests). */
private fun mimeForExtension(ext: String): String? = when (ext) {
    "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "avif" -> "image/$ext"
    "mp4", "mkv", "webm", "mov", "3gp", "avi" -> "video/$ext"
    "mp3", "ogg", "opus", "flac", "wav", "m4a", "aac" -> "audio/$ext"
    "pdf" -> "application/pdf"
    "txt", "md", "log", "csv", "rs", "kt", "py", "sh", "toml", "yaml", "yml", "html", "css", "js" -> "text/plain"
    "json" -> "application/json"
    "xml" -> "application/xml"
    "zip" -> "application/zip"
    "tar" -> "application/x-tar"
    "gz", "tgz" -> "application/gzip"
    "7z" -> "application/x-7z-compressed"
    "rar" -> "application/vnd.rar"
    "bz2" -> "application/x-bzip2"
    "xz" -> "application/x-xz"
    "zst" -> "application/zstd"
    else -> null
}

/**
 * The transfer history: one line per file, newest last, in app-private storage. Kept 30 days and
 * at most [MAX_RECORDS]; both are applied when the file is first read.
 */
class TransferHistoryStore(private val file: File, private val now: () -> Long = System::currentTimeMillis) {
    private var records: MutableList<TransferRecord>? = null

    @Synchronized
    fun all(): List<TransferRecord> = load().asReversed().toList()

    fun forDevice(deviceId: String): List<TransferRecord> = all().filter { it.deviceId == deviceId }

    @Synchronized
    fun append(record: TransferRecord) {
        val list = load()
        list += record
        if (list.size > MAX_RECORDS) {
            prune()
        } else {
            file.parentFile?.mkdirs()
            file.appendText(encode(record) + "\n")
        }
    }

    @Synchronized
    fun remove(id: String) {
        if (load().removeAll { it.id == id }) rewrite()
    }

    /** Drops what is older than [MAX_AGE_MS] and all but the newest [MAX_RECORDS]. */
    @Synchronized
    fun prune() {
        val list = load()
        val cutoff = now() - MAX_AGE_MS
        val kept = list.filter { it.time >= cutoff }.takeLast(MAX_RECORDS)
        list.clear()
        list += kept
        rewrite()
    }

    private fun load(): MutableList<TransferRecord> = records ?: run {
        val read = if (file.exists()) file.readLines().mapNotNull(::decode) else emptyList()
        records = read.toMutableList()
        prune()
        records!!
    }

    private fun rewrite() {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(records.orEmpty().joinToString("") { encode(it) + "\n" })
        tmp.renameTo(file)
    }

    companion object {
        const val MAX_RECORDS = 500
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

        private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
        private fun dec(value: String) = URLDecoder.decode(value, "UTF-8")

        internal fun encode(r: TransferRecord): String = listOf(
            "1", r.id, r.deviceId, r.deviceName, r.name, r.size.toString(), if (r.sent) "s" else "r", r.time.toString(), r.uri, r.mime.orEmpty(),
        ).joinToString("\t") { enc(it) }

        internal fun decode(line: String): TransferRecord? = runCatching {
            val f = line.split('\t').map(::dec)
            if (f.size < 10 || f[0] != "1") return null
            TransferRecord(f[1], f[2], f[3], f[4], f[5].toLong(), f[6] == "s", f[7].toLong(), f[8], f[9].ifEmpty { null })
        }.getOrNull()
    }
}

/** The app's history, and the hooks the share jobs call. */
object TransferHistory {
    @Volatile
    private var store: TransferHistoryStore? = null

    /** Bumped on every change, so open screens re-read. */
    var revision by mutableIntStateOf(0)
        private set

    /** The local file behind each outgoing packet: the packet itself carries only a name. */
    private val sources = WeakHashMap<NetworkPacket, Uri>()

    fun store(context: Context): TransferHistoryStore = store ?: synchronized(this) {
        store ?: TransferHistoryStore(File(context.applicationContext.filesDir, "transfer_history.tsv")).also { store = it }
    }

    @JvmStatic
    fun rememberSource(packet: NetworkPacket, uri: Uri) = synchronized(sources) { sources[packet] = uri }

    /** Where a send came from, without consuming it (the send may still resume later). */
    fun peekSource(packet: NetworkPacket): Uri? = synchronized(sources) { sources[packet] }

    private fun resumeSources(context: Context) = context.getSharedPreferences("transfer_resume_sources", Context.MODE_PRIVATE)

    /** Kept across a lost link, so a resumed send lands in the history with its original file. */
    fun rememberResumable(context: Context, transferId: String, uri: Uri) =
        resumeSources(context).edit().putString(transferId, uri.toString()).apply()

    fun forgetResumable(context: Context, transferId: String) = resumeSources(context).edit().remove(transferId).apply()

    /** A send that finished on a later connection than it started on. */
    fun recordResumedSent(context: Context, deviceId: String, transferId: String, name: String, size: Long) {
        val uri = resumeSources(context).getString(transferId, null)?.let(Uri::parse) ?: return
        forgetResumable(context, transferId)
        append(context, deviceId, name, size, sent = true, uri, context.contentResolver.getType(uri))
    }

    @JvmStatic
    fun recordSent(context: Context, deviceId: String, packet: NetworkPacket) {
        val uri = synchronized(sources) { sources.remove(packet) } ?: return
        val name = packet.getString("filename", uri.lastPathSegment.orEmpty())
        append(context, deviceId, name, packet.payloadSize.coerceAtLeast(0), sent = true, uri, context.contentResolver.getType(uri))
    }

    @JvmStatic
    fun recordReceived(context: Context, deviceId: String, name: String, size: Long, uri: Uri, mime: String?) =
        append(context, deviceId, name, size, sent = false, uri, mime)

    private fun append(context: Context, deviceId: String, name: String, size: Long, sent: Boolean, uri: Uri, mime: String?) {
        val deviceName = DeskLinkApplication.getInstance().getDevice(deviceId)?.name.orEmpty()
        runCatching {
            store(context).append(TransferRecord(UUID.randomUUID().toString(), deviceId, deviceName, name, size, sent, System.currentTimeMillis(), uri.toString(), mime))
        }
        revision++
    }

    /** Something a screen shows changed without a history row (a file kept for a retry). */
    fun changed() {
        revision++
    }

    fun remove(context: Context, id: String) {
        store(context).remove(id)
        revision++
    }
}
