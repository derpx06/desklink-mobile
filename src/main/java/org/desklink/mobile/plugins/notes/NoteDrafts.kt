/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.notes

/**
 * What the editor holds that the store does not: a body over the size cap is kept in the editor but never saved, and a screen
 * rotation used to bring back the stored (older) note. Kept in memory only (notes are sealed on disk; a plain draft file would
 * not be): it survives a rotation and a trip to another app, not the process dying.
 */
object NoteDrafts {
    /** [base]: the `modified` of the stored note this was edited from (0 for a new note). */
    data class Draft(val title: String, val body: String, val pinned: Boolean, val base: Long = 0L)

    private val drafts = HashMap<String, Draft>()

    @Synchronized
    fun put(key: String, draft: Draft) { drafts[key] = draft }

    @Synchronized
    fun get(key: String): Draft? = drafts[key]

    @Synchronized
    fun clear(key: String) { drafts.remove(key) }

    /**
     * The draft for [key] if it was made from the version that is stored now; one made from an older version (the desk changed the
     * note since) is dropped, and the caller says so.
     */
    @Synchronized
    fun valid(key: String, storedModified: Long): Draft? {
        val draft = drafts[key] ?: return null
        if (draft.base == storedModified) return draft
        drafts.remove(key)
        return null
    }

    /** The key for a note being edited, or for a new one not yet saved. */
    fun key(id: String?) = id ?: "new"
}
