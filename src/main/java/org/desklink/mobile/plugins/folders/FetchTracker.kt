/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.folders

/**
 * What a Bucket receive that ended without the file (refused, failed, cancelled, silent) means for its path: asked for again after a
 * backoff, three times at most, then listed as "couldn't fetch" instead of holding the one download slot for ever.
 */
class FetchTracker {
    sealed interface Decision {
        data class Retry(val afterMs: Long) : Decision
        object GiveUp : Decision
    }

    private val tries = HashMap<String, Int>()

    fun failed(path: String): Decision {
        val n = (tries[path] ?: 0) + 1
        if (n > MAX_TRIES) {
            tries.remove(path)
            return Decision.GiveUp
        }
        tries[path] = n
        return Decision.Retry(BACKOFF_MS * (1L shl (n - 1)))
    }

    fun succeeded(path: String) { tries.remove(path) }

    fun forget(path: String) { tries.remove(path) }

    companion object {
        const val MAX_TRIES = 3
        const val BACKOFF_MS = 2_000L

        /** A request with no progress for this long is taken as lost. */
        const val SILENT_MS = 2 * 60_000L

        /** The outstanding paths nothing has been heard of for [SILENT_MS]: asked at [requestedAt], last progress at [lastProgress]. */
        fun silent(outstanding: Collection<String>, requestedAt: Map<String, Long>, lastProgress: Map<String, Long>, now: Long): List<String> =
            outstanding.filter { now - maxOf(requestedAt[it] ?: 0L, lastProgress[it] ?: 0L) > SILENT_MS }
    }
}
