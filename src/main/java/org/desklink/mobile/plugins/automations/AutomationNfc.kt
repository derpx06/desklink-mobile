/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.automations

import android.app.Activity
import android.content.Context
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * F7 "NFC tag tapped": a tag holds `kdeconnect://runcommand/automation/<tag>`, the deep link
 * RunCommandUrlActivity already receives for NDEF, so a tap works with DeskLink closed.
 */
object AutomationNfc {
    const val SEGMENT = "automation"

    /** A tag id: short, and only letters, digits and dashes, since it travels in a URI. */
    const val MAX_TAG_CHARS = 64

    /** "Write tag" waits this long for a tag, then gives up. */
    const val WRITE_TIMEOUT_MS = 60_000L

    private const val TAG = "DeskLink/Automations"
    private val main = Handler(Looper.getMainLooper())
    private var timeout: Runnable? = null

    fun uri(tag: String) = "kdeconnect://runcommand/$SEGMENT/$tag"

    fun validTag(tag: String) = tag.length in 1..MAX_TAG_CHARS && tag.all { it.isLetterOrDigit() || it == '-' }

    @JvmStatic
    fun onTag(context: Context, tag: String) {
        if (!validTag(tag)) return
        Automations.fire(context, Event(Rules.TRIGGER_NFC_TAG, mapOf("tag" to tag)))
    }

    fun available(context: Context) = NfcAdapter.getDefaultAdapter(context)?.isEnabled == true

    /**
     * Writes [tag] to the next NFC tag held to the phone while [activity] is in front. [onResult]
     * runs on the main thread: true written, false failed or timed out.
     */
    fun startWriting(activity: Activity, tag: String, onResult: (Boolean) -> Unit): Boolean {
        val adapter = NfcAdapter.getDefaultAdapter(activity)?.takeIf { it.isEnabled } ?: return false
        if (!validTag(tag)) return false
        val message = NdefMessage(arrayOf(NdefRecord.createUri(uri(tag))))
        fun finish(ok: Boolean) = main.post {
            stopWriting(activity)
            onResult(ok)
        }
        adapter.enableReaderMode(
            activity,
            { nfc ->
                val ok = runCatching {
                    Ndef.get(nfc)?.use { ndef ->
                        ndef.connect()
                        check(ndef.isWritable && ndef.maxSize >= message.byteArrayLength) { "not writable" }
                        ndef.writeNdefMessage(message)
                    } ?: NdefFormatable.get(nfc)?.use { formatable ->
                        formatable.connect()
                        formatable.format(message)
                    } ?: error("not an NDEF tag")
                }.onFailure { Log.w(TAG, "Could not write the tag: ${it.message}") }.isSuccess
                finish(ok)
            },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null,
        )
        timeout = Runnable { finish(false) }.also { main.postDelayed(it, WRITE_TIMEOUT_MS) }
        return true
    }

    fun stopWriting(activity: Activity) {
        timeout?.let(main::removeCallbacks)
        timeout = null
        runCatching { NfcAdapter.getDefaultAdapter(activity)?.disableReaderMode(activity) }
    }
}
