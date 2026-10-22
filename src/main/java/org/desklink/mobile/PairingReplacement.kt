/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile

/**
 * Pairing a computer that has the same name and model as one already paired, and that one is not
 * reachable, is a reinstall or a new identity of the same machine: the old record is dropped, so
 * Home shows one entry, not a dead twin. A twin that is reachable now is a different machine and is
 * never touched. (Decision 18: no migration of anything else.)
 */
object PairingReplacement {
    data class Entry(val id: String, val name: String, val type: String, val paired: Boolean, val reachable: Boolean)

    /** The ids of paired records [fresh] replaces. */
    fun superseded(fresh: Entry, others: Collection<Entry>): List<String> = others
        .filter { it.id != fresh.id && it.paired && !it.reachable && same(it.name, fresh.name) && it.type == fresh.type }
        .map { it.id }

    private fun same(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)
}
