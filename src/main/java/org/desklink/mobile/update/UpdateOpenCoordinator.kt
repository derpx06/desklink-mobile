/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.update

import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Show on open (docs/UPDATES.md 4.1): the only thing that may ever raise update UI on the phone. Decided at most once per
 * open, from the cache; a cache older than 24 h is refreshed with a 3.5 s budget off the main thread, and an answer that
 * arrives later is stored for the next open and never shown. Skipped while anything else is up ([blocked]).
 */
internal class UpdateOpenCoordinator(
    private val engine: UpdateEngine,
    private val store: UpdateStore,
    private val scope: CoroutineScope,
    private val blocked: () -> Boolean,
) {
    /** Null = show nothing. Otherwise the [UpdateState.Available] or [UpdateState.Required] to present. */
    suspend fun onOpen(openId: String): UpdateState? {
        if (store.lastPopupOpen == openId) return null // at most once per open
        store.lastPopupOpen = openId
        if (!engine.configured() || !store.enabled) return null
        var state = engine.state()
        if (!offers(state)) return null
        val age = engine.cacheAgeMs()
        if (age == null || age > UpdateEngine.DAY_MS) {
            // The refresh keeps running past the budget (its answer lands in the cache for the next open).
            val job = scope.async(Dispatchers.IO) { engine.refresh(atOpen = true) }
            withTimeoutOrNull(OPEN_BUDGET_MS) { job.await() }
            state = engine.state()
            if (!offers(state)) return null
        }
        return state.takeUnless { blocked() } // the screen is not free: this open shows nothing and is not retried
    }

    /** The popup was dismissed with Not now, swipe, back, tap outside or the primary button. A required update is only dismissed for this open. */
    fun notNow(state: UpdateState) {
        if (state is UpdateState.Available) engine.later(state.component)
    }

    fun skip(state: UpdateState) {
        if (state is UpdateState.Available) engine.skip(state.component)
    }

    private fun offers(s: UpdateState) = s is UpdateState.Available || s is UpdateState.Required

    companion object {
        const val OPEN_BUDGET_MS = 3_500L

        /** One per activity INSTANCE (a launcher launch creates one); [onOpen] ignores a repeated id, so a reused id would silence a real open. */
        fun newOpenId(): String = java.util.UUID.randomUUID().toString()

        /**
         * A launcher launch of the main activity in a new instance: a cold start or a re-launch from the launcher or recents.
         * Not rotation (a saved state), not a share, QR, deep link or notification tap (no LAUNCHER category), not the service.
         */
        fun isLauncherOpen(action: String?, categories: Set<String>?, hasSavedState: Boolean): Boolean =
            !hasSavedState && action == Intent.ACTION_MAIN && categories?.contains(Intent.CATEGORY_LAUNCHER) == true
    }
}
