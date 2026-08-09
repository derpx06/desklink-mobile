/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

import org.junit.Assert.assertFalse
import org.junit.Test

class WebRtcMediaNegotiationPolicyTest {
    @Test
    fun initialFeatureHandoverDoesNotOfferAnUnrequestedScreenTrack() {
        assertFalse(WebRtcMediaNegotiationPolicy.includeScreenTrackInInitialOffer)
    }
}
