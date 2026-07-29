/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.transport

/** Lifecycle state of one concrete connection transport. */
enum class TransportState {
    CONNECTING,
    CONNECTED,
    CLOSING,
    CLOSED,
    FAILED,
}
