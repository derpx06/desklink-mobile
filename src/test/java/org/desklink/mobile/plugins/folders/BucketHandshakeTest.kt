/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.folders

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BucketHandshakeTest {
    @Test
    fun aLinkThatComesUpLateStillSendsTheOfferThePersonAskedFor() {
        assertTrue(BucketHandshake.resendOffer(asked = true, bucketExists = false))
    }

    @Test
    fun nothingIsSentAgainOnceTheBucketExistsOrWasNeverAsked() {
        assertFalse(BucketHandshake.resendOffer(asked = true, bucketExists = true))
        assertFalse(BucketHandshake.resendOffer(asked = false, bucketExists = false))
        assertFalse(BucketHandshake.resendOffer(asked = false, bucketExists = true))
    }
}
