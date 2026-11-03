/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.desklink.mobile.helpers

import android.content.Context
import java.security.MessageDigest
import java.security.cert.Certificate

/**
 * Computers this phone unpaired and may not have told. The `pair:false` is owed: it is sent as soon as a link to that computer exists
 * (see PairingHandler.sendOwedPairFalse), and forgotten once sent or when the computer is paired again. Without it the computer keeps
 * its record until it happens to send a packet the phone answers. A small set of `id:fingerprint` entries, kept across restarts; the
 * fingerprint is the certificate the computer had when it was unpaired, so only that computer (not an impostor under its id) is told.
 */
object OwedPairFalse {
    private const val PREFS = "desklink_owed_pair_false"
    private const val KEY = "ids"

    private fun prefs(context: Context) = (context.applicationContext ?: context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun read(context: Context): Set<String> {
        val all = prefs(context).getStringSet(KEY, emptySet()).orEmpty()
        // A plain-id entry (the first format, no certificate) cannot be paid to the right computer: dropped.
        val valid = all.filter { ':' in it }.toSet()
        if (valid.size != all.size) prefs(context).edit().putStringSet(KEY, valid).apply()
        return valid
    }

    private fun idOf(entry: String) = entry.substringBefore(':')

    fun fingerprint(certificate: Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }

    /** Owe [deviceId] a `pair:false`; [fingerprint] is its certificate's (null: unknown, then it is never sent). Replaces an earlier debt. */
    @Synchronized
    fun owe(context: Context, deviceId: String, fingerprint: String?) {
        // apply(): the in-memory value is current at once, and this is called from the main thread.
        prefs(context).edit().putStringSet(KEY, read(context).filterNot { idOf(it) == deviceId }.toSet() + "$deviceId:${fingerprint.orEmpty()}").apply()
    }

    fun owes(context: Context, deviceId: String) = read(context).any { idOf(it) == deviceId }

    /** Whether the debt to [deviceId] may be paid over a link showing [linkFingerprint]: only to the certificate that was unpaired. */
    fun mayTell(context: Context, deviceId: String, linkFingerprint: String?): Boolean {
        val stored = read(context).firstOrNull { idOf(it) == deviceId }?.substringAfter(':', "").orEmpty()
        return stored.isNotEmpty() && linkFingerprint != null && stored.equals(linkFingerprint, ignoreCase = true)
    }

    @Synchronized
    fun clear(context: Context, deviceId: String) {
        if (owes(context, deviceId)) prefs(context).edit().putStringSet(KEY, read(context).filterNot { idOf(it) == deviceId }.toSet()).apply()
    }
}
