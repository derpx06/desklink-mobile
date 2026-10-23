/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.clipboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import org.desklink.mobile.NetworkPacket
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * Clipboard images (contract C3): an image copy travels as a file transfer flagged
 * `clipboard: true`, `mimeType: "image/png"`. The receiver puts it on the clipboard and never in
 * Downloads; its temp file is deleted after ten minutes.
 */
object ClipboardImages {
    const val MIME = "image/png"
    private const val TAG = "DeskLink/Clipboard"
    const val MAX_BYTES = 32L * 1024 * 1024
    private const val MAX_SIDE = 4096
    const val KEEP_MS = 10 * 60 * 1000L
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    fun isClipboardImage(np: NetworkPacket) = np.getBoolean("clipboard", false) && np.getString("mimeType", "") == MIME

    fun isPng(head: ByteArray) = head.size >= PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { head[it] == PNG_SIGNATURE[it] }

    private fun dir(context: Context) = File(context.cacheDir, "clipboard").apply { mkdirs() }

    /** Files older than [maxAge]: what a process death left behind. */
    fun expired(files: List<File>, now: Long, maxAge: Long = KEEP_MS) = files.filter { now - it.lastModified() >= maxAge }

    /** The desk's image copy: onto this phone's clipboard (as the desk's, so never sent back). */
    fun receive(context: Context, np: NetworkPacket): Boolean {
        val payload = np.payload ?: return false
        val folder = dir(context)
        expired(folder.listFiles()?.toList().orEmpty(), System.currentTimeMillis()).forEach(File::delete)
        val file = File(folder, "${UUID.randomUUID()}.png")
        try {
            if (payload.payloadSize !in 1..MAX_BYTES) error("clipboard image of ${payload.payloadSize} bytes")
            val input = requireNotNull(payload.inputStream)
            file.outputStream().use { input.copyTo(it) }
            if (!isPng(file.inputStream().use { it.readNBytesCompat(PNG_SIGNATURE.size) })) error("not a PNG")
        } catch (e: Exception) {
            Log.w(TAG, "clipboard image refused (${e.javaClass.simpleName})")
            file.delete()
            return false
        } finally {
            payload.close()
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        Handler(Looper.getMainLooper()).post { ClipboardListener.instance(context).setImage(uri) }
        Handler(Looper.getMainLooper()).postDelayed({ file.delete() }, KEEP_MS)
        Log.i(TAG, "clipboard: image from the desk (${file.length()} bytes)")
        return true
    }

    /** The phone's image copy as a PNG of at most 4096 px a side, or null if it cannot be read. */
    fun encodePng(context: Context, uri: Uri): ByteArray? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray().takeIf { it.size <= MAX_BYTES }
        }
    }.onFailure { Log.w(TAG, "clipboard image unreadable: ${it.javaClass.simpleName}") }.getOrNull()

    /** The share packet that carries [png] as a clipboard image. */
    fun packet(png: ByteArray) = NetworkPacket(org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol.PACKET_TYPE_SHARE_REQUEST).apply {
        this["filename"] = "clipboard.png"
        this["clipboard"] = true
        this["mimeType"] = MIME
        payload = NetworkPacket.Payload(png)
    }

    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val buffer = ByteArray(n)
        var read = 0
        while (read < n) {
            val count = read(buffer, read, n - read)
            if (count < 0) break
            read += count
        }
        return buffer.copyOf(read)
    }
}
