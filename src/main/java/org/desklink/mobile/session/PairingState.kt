/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.session

/** Pairing state kept with the logical device session, not a transport. */
enum class PairingState {
    UNKNOWN,
    NOT_PAIRED,
    REQUESTED,
    PAIRED,
}
