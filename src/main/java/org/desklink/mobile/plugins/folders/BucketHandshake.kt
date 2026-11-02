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

/** "Fix" on a Bucket that is held because its folder is gone or its grant was revoked. */
object BucketFix {
    fun offered(phase: Phase): Boolean = phase == Phase.HELD_EMPTY || phase == Phase.NO_ACCESS

    /**
     * A folder that is gone (held empty) is chosen again from scratch: the old records would read the empty folder as "everything
     * was deleted here", so the new folder starts without them and the computer's files come back. A revoked grant keeps its records,
     * because the folder and its files are still there.
     */
    fun startsFresh(phase: Phase): Boolean = phase == Phase.HELD_EMPTY
}
