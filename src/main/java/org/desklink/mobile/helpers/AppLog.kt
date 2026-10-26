/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.helpers

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app's own short log for "Export logs". Release builds strip every android.util.Log line (they carry names and addresses),
 * which left the export empty. This keeps a SMALL set of important events instead (connection state, transfer outcomes, errors with
 * class names only), redacted, in a bounded file ([MAX_BYTES]); it is written by code, not by Log, so R8 leaves it alone.
 */
object AppLog {
    const val MAX_BYTES = 256 * 1024
    private const val FILE = "applog.txt"
    private const val OLD = "applog.old.txt"

    @Volatile
    private var dir: File? = null
    private val stamp = ThreadLocal.withInitial { SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT) }
    private val lock = Any()

    fun init(context: Context) { dir = context.filesDir }

    /** Test seam: where the log lives. */
    internal fun useDir(directory: File?) { dir = directory }

    /** One event line. [message] is redacted (addresses, keys) and cut; never put a name, a path or a file name in it. */
    fun event(tag: String, message: String) {
        val where = dir ?: return
        val line = "${stamp.get()!!.format(Date())} ${tag.take(24)} ${LogExport.redact(message).replace('\n', ' ').take(300)}\n"
        runCatching {
            synchronized(lock) {
                val current = File(where, FILE)
                if (current.length() + line.length > MAX_BYTES / 2) {
                    // Two files of half the budget: the older one is dropped when the newer fills.
                    File(where, OLD).delete()
                    current.renameTo(File(where, OLD))
                }
                current.appendText(line)
            }
        }
    }

    /** The kept lines, oldest first. */
    fun lines(): Sequence<String> {
        val where = dir ?: return emptySequence()
        val text = synchronized(lock) {
            listOf(OLD, FILE).joinToString("") { name -> runCatching { File(where, name).readText() }.getOrDefault("") }
        }
        return text.lineSequence().filter { it.isNotEmpty() }
    }

    fun clear() {
        val where = dir ?: return
        synchronized(lock) { File(where, FILE).delete(); File(where, OLD).delete() }
    }

    /** A device id as it appears in the log: eight characters, never its name. */
    fun id(deviceId: String?) = deviceId?.take(8) ?: "-"
}
