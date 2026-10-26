/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.backends.lan

/**
 * When the Wi-Fi multicast lock (a battery cost) may be let go: every paired computer that is EXPECTED has a LAN link. A computer
 * not seen for ten minutes (switched off, elsewhere) is not expected, so it cannot keep the lock held for ever.
 */
object MulticastPolicy {
    const val EXPECTED_WITHIN_MS = 10 * 60_000L

    fun allExpectedLinked(paired: Collection<String>, linked: Set<String>, seenAt: (String) -> Long, now: Long): Boolean {
        if (paired.isEmpty()) return linked.isNotEmpty()
        val expected = paired.filter { it in linked || now - seenAt(it) in 0..EXPECTED_WITHIN_MS }
        return expected.all { it in linked } && linked.isNotEmpty()
    }
}
