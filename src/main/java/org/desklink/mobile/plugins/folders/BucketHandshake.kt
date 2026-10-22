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
