/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile

/**
 * Pairing a computer that has the same name and model as one already paired, that one is not
 * reachable now, and was last seen at the very LAN address the new one is on, is a reinstall or a
 * new identity of the same machine: the old record is dropped, so Home shows one entry, not a dead
 * twin. Name and model alone are not enough (two laptops can both be called "ubuntu"): the shared
 * address is the second signal. A twin that is reachable now is another machine and is never
 * touched. (Decision 18: no migration of anything else.)
 */
object PairingReplacement {
    data class Entry(val id: String, val name: String, val type: String, val paired: Boolean, val reachable: Boolean, val addresses: Set<String> = emptySet())

    /** The ids of paired records [fresh] replaces. */
    fun superseded(fresh: Entry, others: Collection<Entry>): List<String> = others
        .filter { it.id != fresh.id && it.paired && !it.reachable && same(it.name, fresh.name) && it.type == fresh.type && it.addresses.any { a -> a in fresh.addresses } }
        .map { it.id }

    private fun same(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)
}
