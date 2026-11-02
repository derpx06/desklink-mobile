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

class BucketFixTest {
    @Test
    fun fixIsOfferedOnlyForAFolderThatIsGoneOrAGrantThatWasRevoked() {
        for (phase in Phase.values()) {
            assertTrue(phase.name, BucketFix.offered(phase) == (phase == Phase.HELD_EMPTY || phase == Phase.NO_ACCESS))
        }
    }

    @Test
    fun aMissingFolderStartsWithoutRecordsSoNothingReadsAsDeletedButARevokedGrantKeepsThem() {
        assertTrue(BucketFix.startsFresh(Phase.HELD_EMPTY))
        assertFalse(BucketFix.startsFresh(Phase.NO_ACCESS))
    }
}

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class BucketFixFolderTest {
    private fun tree(id: String) = android.net.Uri.parse("content://com.android.externalstorage.documents/tree/" + android.net.Uri.encode(id))

    @Test
    fun aRevokedGrantIsGivenAgainForTheSameFolderOnly() {
        val old = tree("primary:Documents/DeskLink Bucket").toString()
        assertTrue(BucketFix.sameTree(old, tree("primary:Documents/DeskLink Bucket")))
        assertFalse(BucketFix.sameTree(old, tree("primary:Download")))
        assertTrue("no record of the old one: nothing to compare", BucketFix.sameTree(null, tree("primary:Download")))
    }
}
