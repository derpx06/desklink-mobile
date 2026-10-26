/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.notes

/**
 * What the editor holds that the store does not: a body over the size cap is kept in the editor but never saved, and a screen
 * rotation used to bring back the stored (older) note. Kept in memory only (notes are sealed on disk; a plain draft file would
 * not be): it survives a rotation and a trip to another app, not the process dying.
 */
object NoteDrafts {
    data class Draft(val title: String, val body: String, val pinned: Boolean)

    private val drafts = HashMap<String, Draft>()

    @Synchronized
    fun put(key: String, draft: Draft) { drafts[key] = draft }

    @Synchronized
    fun get(key: String): Draft? = drafts[key]

    @Synchronized
    fun clear(key: String) { drafts.remove(key) }

    /** The key for a note being edited, or for a new one not yet saved. */
    fun key(id: String?) = id ?: "new"
}
