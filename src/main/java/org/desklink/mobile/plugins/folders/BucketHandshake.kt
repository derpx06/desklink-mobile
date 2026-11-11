/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.folders

/** When the Bucket's pairing handshake must be sent again. */
object BucketHandshake {
    /**
     * On every feature-ready: the offer goes out again while this phone is still waiting for the
     * computer's answer (it asked, and no Bucket exists yet). An offer sent before the link was ready
     * is never left to a queue that gives up after 30 s.
     */
    fun resendOffer(asked: Boolean, bucketExists: Boolean): Boolean = asked && !bucketExists
}

/**
 * How long after its last scan a Bucket is scanned again. The tree's ContentObserver hears only changes made through SAF:
 * ExternalStorageProvider never reports a file another app writes into the folder (a file manager, a download), and with no storage
 * permission (scoped storage) the app cannot watch the path itself. So while the screen is on (and the desk is feature-ready: only
 * then is a Bucket scanned) it looks again after 5 s, doubling for each scan in a row that found nothing new, up to 30 s; a change,
 * or the screen coming on, starts it at 5 s again. Screen off: every 15 minutes.
 */
object BucketRescan {
    const val QUICK_MS = 5_000L
    const val QUICK_MAX_MS = 30_000L
    const val SCREEN_OFF_MS = 15 * 60_000L

    fun every(screenOn: Boolean, quietScans: Int): Long =
        if (!screenOn) SCREEN_OFF_MS else minOf(QUICK_MS shl quietScans.coerceIn(0, 3), QUICK_MAX_MS)
}

/** "Fix" on a Bucket that is held because its folder is gone or its grant was revoked. */
object BucketFix {
    fun offered(phase: Phase): Boolean = phase == Phase.HELD_EMPTY || phase == Phase.NO_ACCESS

    /**
     * A folder that is gone (held empty) is chosen again from scratch: the old records would read the empty folder as "everything
     * was deleted here", so the new folder starts without them and the computer's files come back. A revoked grant keeps its records,
     * because the folder and its files are still there.
     */
    fun startsFresh(phase: Phase): Boolean = phase == Phase.HELD_EMPTY

    /** A revoked grant is given again for the same folder (the same tree document), or the records kept for it would not match. */
    fun sameTree(old: String?, picked: android.net.Uri): Boolean =
        old == null || runCatching {
            android.provider.DocumentsContract.getTreeDocumentId(picked) == android.provider.DocumentsContract.getTreeDocumentId(android.net.Uri.parse(old))
        }.getOrDefault(false)
}
