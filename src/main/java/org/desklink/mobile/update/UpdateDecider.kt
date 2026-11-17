/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.update

import java.security.MessageDigest

/** What the person chose about the offered version; read from the store, passed in so [UpdateDecider] stays pure. */
internal data class UpdatePrefs(val skippedVersion: String? = null, val snoozedVersion: String? = null, val snoozedUntil: Long = 0)

/** The one decision function (docs/UPDATES.md 4.3). The Android client compares `versionCode`; `version` is display. */
internal object UpdateDecider {
    /** Staged rollout (2.3): a stable 0..99 bucket per install and release, computed on the device from the local install id. */
    fun bucket(installId: ByteArray, component: String, version: String): Int {
        val d = MessageDigest.getInstance("SHA-256")
        d.update(installId)
        d.update("|$component|$version".toByteArray(Charsets.UTF_8))
        val h = d.digest()
        val u = ((h[0].toLong() and 0xff) shl 24) or ((h[1].toLong() and 0xff) shl 16) or ((h[2].toLong() and 0xff) shl 8) or (h[3].toLong() and 0xff)
        return (u % 100).toInt()
    }

    /** The release a channel offers: its own entry; the beta channel also sees a stable one that is newer. A missing channel is null. */
    fun pick(payload: Payload, channel: Channel): Component? {
        val own = payload.channels[channel]
        if (channel != Channel.BETA) return own
        val stable = payload.channels[Channel.STABLE]
        return if (own != null && stable != null) maxOf(own, stable, compareBy { it.versionCode }) else own ?: stable
    }

    fun decide(
        installed: Version,
        installedCode: Int,
        channel: Channel,
        payload: Payload,
        prefs: UpdatePrefs,
        installId: ByteArray,
        nowMs: Long,
        manual: Boolean,
    ): UpdateState {
        val c = pick(payload, channel) ?: return UpdateState.UpToDate
        if (c.rollout == 0) return UpdateState.UpToDate // a pause: nothing is shown, not even for the button or a required update
        if (installed < c.minSupported) return UpdateState.Required(c) // ignores skip, snooze and the 1..99 rollout
        if (c.versionCode <= installedCode) return UpdateState.UpToDate
        if (manual) return UpdateState.Available(c)
        if (bucket(installId, "android", c.version.toString()) >= c.rollout) return UpdateState.UpToDate
        if (prefs.skippedVersion == c.version.toString()) return UpdateState.Skipped(c)
        if (prefs.snoozedVersion == c.version.toString() && prefs.snoozedUntil > nowMs) return UpdateState.Snoozed(c, prefs.snoozedUntil)
        return UpdateState.Available(c)
    }
}
