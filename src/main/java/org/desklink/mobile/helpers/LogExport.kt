/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.helpers

import android.os.Build
import android.os.Process

/** "Export logs": only this app's own lines, with the network addresses and key material taken out, and a failure said out loud. */
object LogExport {
    private val ipv4 = Regex("""\b\d{1,3}(\.\d{1,3}){3}\b""")
    private val ipv6 = Regex("""\b([0-9a-fA-F]{1,4}:){3,7}[0-9a-fA-F]{1,4}\b""")
    private val mac = Regex("""\b([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}\b""")
    private val longHex = Regex("""\b[0-9a-fA-F]{32,}\b""")

    fun redact(line: String): String = line
        .replace(mac, "<mac>").replace(ipv6, "<ip>").replace(ipv4, "<ip>")
        .replace(longHex) { it.value.take(6) + "…" }

    /** The command: this process's log only (API 24+; before that Android shows an app only its own lines anyway). */
    fun command(pid: Int = Process.myPid()): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) arrayOf("logcat", "-d", "--pid=$pid") else arrayOf("logcat", "-d")

    /** Writes the header and the redacted lines to [output]; throws if logcat could not be read. */
    fun write(header: String, lines: Sequence<String>, output: java.io.OutputStream) {
        output.write((header + "\n").toByteArray(Charsets.UTF_8))
        lines.forEach { output.write((redact(it) + "\n").toByteArray(Charsets.UTF_8)) }
    }
}
