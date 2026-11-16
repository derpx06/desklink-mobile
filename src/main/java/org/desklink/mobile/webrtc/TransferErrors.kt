/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

// How a file transfer's errors read: resuming after a lost link, a full disk,
// a closed link, and the words the peer is told; and the one publish thread.
// Used by the transfer manager, FileSender and FileReceiver.

/** The link went away mid-send; the checkpoint is kept and the next connection resumes it. */
class TransferResumingException(reason: String) : IllegalStateException(reason)

/** The disk is full: somewhere in what caused [error] Android said so. */
internal fun isStorageFull(error: Throwable): Boolean = org.desklink.mobile.plugins.share.UnpublishedFiles.isStorageFull(error)

/** One thread that puts finished files in place, one at a time, apart from the file thread. */
internal object ThreadPublisher {
    val shared: java.util.concurrent.Executor by lazy {
        org.desklink.mobile.helpers.Guarded.single("DeskLink-publish")
    }
}

/** The link under a transfer closed or is not open yet: pause it, never fail it. */
internal fun linkClosed(error: Throwable): Boolean =
    error is org.desklink.mobile.transport.TransportError &&
        error.code == org.desklink.mobile.transport.TransportErrorCode.CLOSED

/**
 * What the peer is told when a transfer ends here: short human text, never a class name or a
 * raw platform message (those reached the desk as "org.desklink…TransportError: …").
 */
internal fun peerReason(error: Throwable, fallback: String): String {
    val message = error.message.orEmpty()
    return when {
        isStorageFull(error) -> "Not enough space on the phone"
        // Our own checks (require/check) say what went wrong in plain words.
        (error is IllegalArgumentException || error is IllegalStateException) && message.isNotBlank() &&
            !Regex("""Exception|Error:|org\.|java\.|android\.|/""").containsMatchIn(message) -> message.take(256)
        else -> fallback
    }
}
