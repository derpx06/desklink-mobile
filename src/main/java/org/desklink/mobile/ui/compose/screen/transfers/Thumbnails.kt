/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.screen.transfers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.desklink.mobile.plugins.share.PreviewKind
import org.desklink.mobile.plugins.share.TransferRecord
import org.desklink.mobile.plugins.share.previewKind
import java.io.File

/** What a history card shows; [loading] until the first read finishes. */
data class FilePreview(
    val loading: Boolean = true,
    /** The file can no longer be opened (moved, deleted, or its permission ended). */
    val missing: Boolean = false,
    val bitmap: Bitmap? = null,
    val durationMs: Long? = null,
)

/**
 * Thumbnails and video poster frames, decoded off the main thread and cached by uri, size and
 * modification time (a file replaced under the same name gets a new picture).
 */
object Thumbnails {
    private val cache = object : LruCache<String, FilePreview>((Runtime.getRuntime().maxMemory() / 1024 / 16).toInt()) {
        override fun sizeOf(key: String, value: FilePreview) = (value.bitmap?.byteCount ?: 0) / 1024 + 1
    }

    suspend fun load(context: Context, record: TransferRecord, sizePx: Int): FilePreview = withContext(Dispatchers.IO) {
        val uri = Uri.parse(record.uri)
        if (!exists(context, uri)) return@withContext FilePreview(loading = false, missing = true)
        val key = "${record.uri}|${record.size}|${modified(context, uri, record.time)}|$sizePx"
        cache.get(key)?.let { return@withContext it }
        val preview = runCatching {
            when (previewKind(record.mime, record.name)) {
                PreviewKind.IMAGE -> FilePreview(loading = false, bitmap = thumbnail(context, uri, sizePx) ?: decodeSampled(context, uri, sizePx))
                PreviewKind.VIDEO -> video(context, uri, sizePx)
                else -> FilePreview(loading = false)
            }
        }.getOrDefault(FilePreview(loading = false))
        cache.put(key, preview)
        preview
    }

    fun exists(context: Context, uri: Uri): Boolean =
        if (uri.scheme == "file") File(uri.path.orEmpty()).isFile
        else runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)

    private fun modified(context: Context, uri: Uri, fallback: Long): Long =
        if (uri.scheme == "file") File(uri.path.orEmpty()).lastModified()
        else runCatching {
            context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        }.getOrNull() ?: fallback

    /** The provider's own thumbnail (MediaStore, documents) on Android 10+. */
    private fun thumbnail(context: Context, uri: Uri, sizePx: Int): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.scheme == "content") {
            runCatching { context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null) }.getOrNull()
        } else {
            null
        }

    /** Decodes at the smallest power-of-two scale still at least [sizePx] on the short side. */
    fun decodeSampled(context: Context, uri: Uri, sizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= sizePx) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun video(context: Context, uri: Uri, sizePx: Int): FilePreview {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val frame = thumbnail(context, uri, sizePx) ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, sizePx * 2, sizePx * 2)
            } else {
                retriever.getFrameAtTime(1_000_000)
            }
            FilePreview(loading = false, bitmap = frame, durationMs = duration)
        } finally {
            runCatching { retriever.release() }
        }
    }
}

@Composable
fun rememberFilePreview(record: TransferRecord, sizePx: Int, revision: Int = 0): State<FilePreview> {
    val context = LocalContext.current
    return produceState(FilePreview(), record.id, sizePx, revision) { value = Thumbnails.load(context, record, sizePx) }
}
