/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.transport

import java.io.IOException

enum class TransportErrorCode {
    CLOSED,
    UNSUPPORTED_CHANNEL,
    INVALID_PACKET,
    SEND_FAILED,
}

/** Plain Java-compatible transport failure value. */
class TransportError @JvmOverloads constructor(
    val code: TransportErrorCode,
    override val message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
