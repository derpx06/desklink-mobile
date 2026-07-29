/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.transport

/** Why a transport was closed. Kept explicit for reconnect policy later. */
enum class DisconnectReason {
    REPLACED,
    NETWORK_LOST,
    SERVICE_STOPPED,
    REMOTE_CLOSED,
    USER_REQUESTED,
    ERROR,
}
