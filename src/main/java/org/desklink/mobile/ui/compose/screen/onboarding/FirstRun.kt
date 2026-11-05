/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.screen.onboarding

import android.content.SharedPreferences
import androidx.core.content.edit

/** What shows before Home: the splash while the paired computer is looked for, and the tour on a first run. Pure, so it is tested without a screen. */
object FirstRun {
    /** A new default-preferences key; no existing key is read or rewritten. */
    const val KEY_ONBOARDING_SEEN = "onboarding_seen"

    /** The splash never holds the app longer than this. */
    const val SPLASH_MAX_MS = 1_000L

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
}
