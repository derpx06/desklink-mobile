/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.update

/**
 * Version skew with the paired computer (docs/UPDATES.md 6.8): informational, never gates a connection. Only a different
 * major.minor counts; a patch difference, a missing or unparsable version is nothing.
 */
internal sealed interface UpdateSkew {
    data object None : UpdateSkew
    data class PeerOlder(val peer: String) : UpdateSkew
    data class PeerNewer(val peer: String) : UpdateSkew

    companion object {
        fun of(own: String?, peer: String?): UpdateSkew {
            val a = Version.parseLenient(own) ?: return None
            val b = Version.parseLenient(peer) ?: return None
            val shown = peer!!.takeWhile { it in '0'..'9' || it == '.' }.take(16)
            val c = compareValuesBy(b, a, { it.major }, { it.minor })
            return when {
                c < 0 -> PeerOlder(shown)
                c > 0 -> PeerNewer(shown)
                else -> None
            }
        }
    }
}
