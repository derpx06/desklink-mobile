/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.screen.onboarding

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/** What shows before Home: the launch splash held while the paired computer is looked for, and the tour on a first run. Pure, so it is tested without a screen. */
object FirstRun {
    /** A new default-preferences key; no existing key is read or rewritten. */
    const val KEY_ONBOARDING_SEEN = "onboarding_seen"

    /** The launch splash never holds the app longer than this (counted from MainActivity's creation). */
    const val SPLASH_MAX_MS = 500L

    /**
     * The splash shows on a cold start, for a paired computer that is not reachable yet, until it is or [SPLASH_MAX_MS] has passed.
     * No paired computer means the state is already known (nothing to look for); a reachable one means it is found.
     */
    fun showSplash(coldStart: Boolean, pairedName: String?, reachable: Boolean, expired: Boolean): Boolean =
        coldStart && pairedName != null && !reachable && !expired

    /** The tour is for someone who has not paired and has not seen it; a paired person never sees it unasked. */
    fun showOnboarding(seen: Boolean, pairedCount: Int): Boolean = !seen && pairedCount == 0

    fun seen(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY_ONBOARDING_SEEN, false)

    fun markSeen(prefs: SharedPreferences) {
        if (!seen(prefs)) prefs.edit { putBoolean(KEY_ONBOARDING_SEEN, true) }
    }

    /** Set once the first-run permissions step is left with Continue or Skip, or once a computer is paired. A new key, like [KEY_ONBOARDING_SEEN]. */
    const val KEY_SETUP_DONE = "permissions_setup_done"

    /** "Pair with a computer" goes through the permissions step first, once, and only for someone who has never paired. */
    fun showSetup(done: Boolean, pairedCount: Int): Boolean = !done && pairedCount == 0

    fun setupDone(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY_SETUP_DONE, false)

    fun markSetupDone(prefs: SharedPreferences) {
        if (!setupDone(prefs)) prefs.edit { putBoolean(KEY_SETUP_DONE, true) }
    }

    /** Home's "Finish setting up" card, folded by the person. */
    const val KEY_HOME_SETUP_COLLAPSED = "home_setup_collapsed"

    /** A pairing finished with something still to allow: the checklist waits for the screen to be free. Kept across a restart. */
    const val KEY_CHECKLIST_PENDING = "permissions_checklist_pending"

    /** The paired computers last seen, so a pairing that finished while the activity was gone still counts as new. */
    const val KEY_KNOWN_PAIRED = "known_paired_ids"

    fun checklistPending(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY_CHECKLIST_PENDING, false)

    fun setChecklistPending(prefs: SharedPreferences, pending: Boolean) = prefs.edit { putBoolean(KEY_CHECKLIST_PENDING, pending) }

    /** Null until first written: an app updated from a build without it treats nobody as newly paired. */
    fun knownPaired(prefs: SharedPreferences): Set<String>? = prefs.getStringSet(KEY_KNOWN_PAIRED, null)?.toSet()

    fun setKnownPaired(prefs: SharedPreferences, ids: Set<String>) = prefs.edit { putStringSet(KEY_KNOWN_PAIRED, ids.toSet()) }
}

/** Forgets what a screen remembered once the person has left it, so coming back starts afresh (the tour replays from page one). */
@androidx.compose.runtime.Composable
fun ForgetLeftRoutes(top: String, holder: androidx.compose.runtime.saveable.SaveableStateHolder, routes: Set<String>) {
    var previous by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(top) }
    androidx.compose.runtime.LaunchedEffect(top) {
        if (previous != top && previous in routes) holder.removeState(previous)
        previous = top
    }
}

/** The route stack after a pairing, as a pure function (the compare-codes, scan and desk-initiated paths all end here). */
object PairingStack {
    /**
     * Drops the screens a pairing used ([isPairing]: scan, pair) and keeps the rest in order, so a checklist pushed by whichever
     * path finished first is not wiped by the one that finished second. [home] stays at the bottom.
     */
    fun settle(stack: List<String>, home: String, isPairing: (String) -> Boolean): List<String> {
        val rest = stack.filterNot(isPairing)
        return if (rest.firstOrNull() == home) rest else listOf(home) + rest
    }

    /** Whether the checklist may be pushed: only straight on top of Home. */
    fun canOfferChecklist(stack: List<String>, home: String) = stack == listOf(home)

    /** The computers paired since [previous] (none when nothing was known yet: a first look is not a pairing). */
    fun fresh(previous: Set<String>?, ids: Set<String>): Set<String> = previous?.let { ids - it }.orEmpty()

    /**
     * Whether a queued checklist goes up now: only with this activity in front (not under a desk's screen-share or microphone
     * prompt, the camera, or the system's permission dialog), a computer paired, and nothing but Home on the stack.
     */
    fun offerChecklistNow(pending: Boolean, resumed: Boolean, paired: Boolean, stack: List<String>, home: String): Boolean =
        pending && resumed && paired && canOfferChecklist(stack, home)

    /** The checklist is pushed once. */
    fun withChecklist(stack: List<String>, checklist: String) = if (stack.lastOrNull() == checklist) stack else stack + checklist
}
