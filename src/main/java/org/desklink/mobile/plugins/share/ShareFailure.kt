/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.share

import org.desklink.mobile.transport.TransportError
import org.desklink.mobile.transport.TransportErrorCode
import org.desklink.mobile.webrtc.TransferResumingException

/**
 * What a share file's failure means for the queue (ShareQueue): a file that was cancelled or refused is not sent again by itself
 * (it is shown once); a link that was not ready keeps every entry and tries again at the next feature-ready, three times at most.
 */
object ShareFailure {
    enum class Kind { RESUMING, NOT_READY, CANCELLED, FAILED }

    const val MAX_NOT_READY_ATTEMPTS = 3

    @JvmStatic
    fun classify(failure: Throwable?, cancelled: Boolean): Kind = when {
        failure is TransferResumingException -> Kind.RESUMING
        cancelled -> Kind.CANCELLED
        failure is TransportError && failure.code == TransportErrorCode.CLOSED -> Kind.NOT_READY
        failure is IllegalStateException && failure.message?.endsWith("is not ready") == true -> Kind.NOT_READY
        else -> Kind.FAILED
    }

    /** Whether the queue entries of this share stay for the next feature-ready. */
    @JvmStatic
    fun keepsEntries(kind: Kind): Boolean = kind == Kind.NOT_READY || kind == Kind.RESUMING
}
