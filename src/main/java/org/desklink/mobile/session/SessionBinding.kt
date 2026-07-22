/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.session

import org.desklink.mobile.transport.SessionTransport

/** Immutable identity carried by work associated with one transport generation. */
data class SessionBinding(
    val deviceId: String,
    val sessionId: Long,
    val connectionGeneration: Long,
    val transport: SessionTransport,
)
