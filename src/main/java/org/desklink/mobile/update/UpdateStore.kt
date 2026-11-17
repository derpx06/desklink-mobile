/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.update

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

/**
 * The update system's own state (docs/UPDATES.md 4.4): the SharedPreferences file `desklink_update`, never the default
 * preferences, so settings export, config salvage and unpair cleanup do not touch it. A corrupt file is cleared, never an
 * error. The install id is random, local, and used only for the rollout bucket: it is never sent or logged.
 */
internal class UpdateStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    private inline fun <T> guarded(default: T, block: () -> T): T = try {
        block()
    } catch (_: ClassCastException) {
        prefs.edit().clear().commit()
        default
    }

    /** 16 random bytes, created on first use and again if the file is deleted. */
    fun installId(): ByteArray = guarded(null) { prefs.getString(K_INSTALL, null)?.let(::unhex)?.takeIf { it.size == 16 } }
        ?: ByteArray(16).also { SecureRandom().nextBytes(it); prefs.edit().putString(K_INSTALL, hex(it)).apply() }

    var enabled: Boolean
        get() = guarded(true) { prefs.getBoolean(K_ENABLED, true) }
        set(v) = prefs.edit().putBoolean(K_ENABLED, v).apply()

    var wifiOnly: Boolean
        get() = guarded(true) { prefs.getBoolean(K_WIFI, true) }
        set(v) = prefs.edit().putBoolean(K_WIFI, v).apply()

    /** The stored choice, or beta for a 0.x build and stable from 1.0 (the default is computed, so a stored value always wins). */
    fun channel(installed: Version): Channel = guarded(null) { Channel.of(prefs.getString(K_CHANNEL, null)) }
        ?: if (installed.major == 0) Channel.BETA else Channel.STABLE

    fun setChannel(c: Channel) = prefs.edit().putString(K_CHANNEL, c.key).apply()

    var etag: String?
        get() = guarded(null) { prefs.getString(K_ETAG, null) }
        set(v) = prefs.edit().putString(K_ETAG, v).apply()

    /** The envelope of the last fully verified manifest, kept so the answer is there at once. It is verified again on load. */
    var cachedEnvelope: String?
        get() = guarded(null) { prefs.getString(K_BODY, null) }
        set(v) = prefs.edit().putString(K_BODY, v).apply()

    var lastChecked: Long
        get() = guarded(0L) { prefs.getLong(K_CHECKED, 0) }
        set(v) = prefs.edit().putLong(K_CHECKED, v).apply()

    var lastIssuedAt: Long
        get() = guarded(0L) { prefs.getLong(K_ISSUED, 0) }
        set(v) = prefs.edit().putLong(K_ISSUED, v).apply()

    var skippedVersion: String?
        get() = guarded(null) { prefs.getString(K_SKIP, null) }
        set(v) = prefs.edit().putString(K_SKIP, v).apply()

    var snoozedVersion: String?
        get() = guarded(null) { prefs.getString(K_SNOOZE_V, null) }
        set(v) = prefs.edit().putString(K_SNOOZE_V, v).apply()

    var snoozedUntil: Long
        get() = guarded(0L) { prefs.getLong(K_SNOOZE_T, 0) }
        set(v) = prefs.edit().putLong(K_SNOOZE_T, v).apply()

    /** The open the popup was last decided for, so a repeated call within one open is a no-op. */
    var lastPopupOpen: String?
        get() = guarded(null) { prefs.getString(K_OPEN, null) }
        set(v) = prefs.edit().putString(K_OPEN, v).apply()

    /** First failure of the current streak (0 = none), its reason code, and how many in a row (drives the backoff). */
    var failedSince: Long
        get() = guarded(0L) { prefs.getLong(K_FAIL_SINCE, 0) }
        set(v) = prefs.edit().putLong(K_FAIL_SINCE, v).apply()

    var failReason: String?
        get() = guarded(null) { prefs.getString(K_FAIL_REASON, null) }
        set(v) = prefs.edit().putString(K_FAIL_REASON, v).apply()

    var failStreak: Int
        get() = guarded(0) { prefs.getInt(K_FAIL_N, 0) }
        set(v) = prefs.edit().putInt(K_FAIL_N, v).apply()

    /** Debug builds only: a test server address that replaces the built-in URL. */
    var debugUrl: String?
        get() = guarded(null) { prefs.getString(K_DEBUG_URL, null) }
        set(v) = prefs.edit().putString(K_DEBUG_URL, v).apply()

    fun prefsForDecision() = UpdatePrefs(skippedVersion, snoozedVersion, snoozedUntil)

    companion object {
        const val FILE = "desklink_update"
        private const val K_INSTALL = "installId"
        private const val K_ENABLED = "update_check"
        private const val K_WIFI = "update_wifi_only"
        private const val K_CHANNEL = "update_channel"
        private const val K_ETAG = "etag"
        private const val K_BODY = "cachedEnvelope"
        private const val K_CHECKED = "lastChecked"
        private const val K_ISSUED = "lastIssuedAt"
        private const val K_SKIP = "skippedVersion"
        private const val K_SNOOZE_V = "snoozedVersion"
        private const val K_SNOOZE_T = "snoozedUntil"
        private const val K_OPEN = "lastPopupOpen"
        private const val K_FAIL_SINCE = "failedSince"
        private const val K_FAIL_REASON = "failReason"
        private const val K_FAIL_N = "failStreak"
        private const val K_DEBUG_URL = "debugUrl"

        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        fun unhex(s: String): ByteArray? = if (s.length % 2 != 0 || !Regex("[0-9a-f]*").matches(s)) null else ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
