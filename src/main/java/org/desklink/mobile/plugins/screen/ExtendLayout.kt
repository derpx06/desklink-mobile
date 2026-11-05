/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.screen

import android.content.pm.ActivityInfo

/**
 * Pure decisions for the desk screen as a second display (Extend). The computer adds a virtual monitor the size of
 * this phone's view, so it must be asked with the phone's landscape area, never a portrait one seen while the
 * activity is still turning, and never before the gate (fingerprint, phone-only check) has passed.
 */
object ExtendLayout {
    /** How long to wait for a landscape area before asking with what there is (the computer then picks a size). */
    const val AREA_WAIT_MS = 2_000L

    /** Portrait until the gate has passed (biometrics are not offered reliably over a forced-landscape secure window), then locked to either landscape. */
    fun orientation(gateOpen: Boolean): Int =
        if (gateOpen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    /** A measured view size worth telling the computer about: landscape. Anything else is a rotation in progress. */
    fun publishable(width: Int, height: Int): Pair<Int, Int>? =
        (width to height).takeIf { width > height && height > 0 }

    /** Ask now: the landscape area is known, or waiting for it has gone on long enough. */
    fun readyToAsk(area: Pair<Int, Int>?, waitedMs: Long): Boolean =
        area != null && area.first > area.second || waitedMs >= AREA_WAIT_MS
}
