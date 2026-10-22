/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Where a file being sent is read from, **in place**: there is no private copy. The sender asks
 * [stat] before every block and once at the end (a changed size or mtime restarts the file, a
 * missing file fails it) and [open]s it at a block boundary, so resuming never re-reads what was
 * already acknowledged.
 */
interface FileSource {
    data class Stat(val size: Long, val mtime: Long)

    /** Null when the file no longer exists or cannot be read. */
    fun stat(): Stat?

    /** A stream positioned at [offset]; the caller closes it. */
    fun open(offset: Long): InputStream

    /** A string that can rebuild this source after a restart (a `file:` or `content:` URI), or null. */
    val persistentUri: String? get() = null
}

class PlainFileSource(private val file: File) : FileSource {
    override fun stat(): FileSource.Stat? = file.takeIf { it.isFile }?.let { FileSource.Stat(it.length(), it.lastModified()) }

    override fun open(offset: Long): InputStream = FileInputStream(file).also { if (offset > 0) it.channel.position(offset) }

    override val persistentUri: String get() = file.toURI().toString()
}

class UriFileSource(private val context: android.content.Context, private val uri: Uri) : FileSource {
    private val resolver: ContentResolver get() = context.contentResolver

    override fun stat(): FileSource.Stat? = runCatching {
        if (uri.scheme == "file") return PlainFileSource(File(requireNotNull(uri.path))).stat()
        if (android.provider.DocumentsContract.isDocumentUri(context, uri)) {
            // A document in a picked folder (the Bucket): its provider's own size and modified time.
            resolver.query(
                uri,
                arrayOf(android.provider.DocumentsContract.Document.COLUMN_SIZE, android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null, null, null,
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) return FileSource.Stat(cursor.getLong(0), if (cursor.isNull(1)) 0L else cursor.getLong(1))
            }
            return null
        }
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) size = cursor.getLong(0)
        }
        if (size < 0) resolver.openAssetFileDescriptor(uri, "r")?.use { size = it.length }
        if (size < 0) return null
        FileSource.Stat(size, org.desklink.mobile.helpers.FilesHelper.getLastModifiedTime(context, uri) ?: 0L)
    }.getOrNull()

    override fun open(offset: Long): InputStream {
        val descriptor = requireNotNull(resolver.openFileDescriptor(uri, "r")) { "The file cannot be opened" }
        val stream = FileInputStream(descriptor.fileDescriptor)
        // The stream owns the descriptor from here: closing it closes both.
        val owned = object : InputStream() {
            override fun read() = stream.read()
            override fun read(b: ByteArray, off: Int, len: Int) = stream.read(b, off, len)
            override fun close() {
                runCatching { stream.close() }
                runCatching { descriptor.close() }
            }
        }
        if (offset > 0) {
            // A seekable source jumps; anything else (a pipe) is read past.
            val jumped = runCatching { stream.channel.position(offset) }.isSuccess
            if (!jumped) {
                var left = offset
                while (left > 0) {
                    val skipped = stream.skip(left)
                    if (skipped <= 0) { owned.close(); throw java.io.IOException("The file is shorter than its resume point") }
                    left -= skipped
                }
            }
        }
        return owned
    }

    override val persistentUri: String get() = uri.toString()
}
