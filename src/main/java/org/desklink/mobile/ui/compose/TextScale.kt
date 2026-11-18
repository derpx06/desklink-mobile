/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.compose

/**
 * The font-scale breakpoints layouts change at, in one place. Home's quick actions go two across from [CROWDED]
 * (four across crowded two-word labels at 1.3x); empty states, search hints and a tab's subtitle give way from [LARGE];
 * the terminal header drops its grid size from [HUGE].
 */
object TextScale {
    const val CROWDED = 1.15f
    const val LARGE = 1.4f
    const val HUGE = 1.5f
}
