/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui

/**
 * Whether the once-a-second refresh should redraw. A screen that can say what it shows passes a
 * stamp (an equality-comparable snapshot) and is redrawn only when it changed; a screen without
 * one (null) is redrawn every time, as before. Redrawing an unchanged Home cost ~1 % CPU and
 * ~55 frames a minute on an idle phone.
 */
class RefreshGate {
    private var last: Any? = null

    fun next(stamp: Any?): Boolean {
        val changed = stamp == null || stamp != last
        last = stamp
        return changed
    }
}
