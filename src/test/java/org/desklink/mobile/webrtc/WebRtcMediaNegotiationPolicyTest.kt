/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

import org.junit.Assert.assertTrue
import org.junit.Test

class WebRtcMediaNegotiationPolicyTest {
    /**
     * The inverse of what this test used to assert, and deliberately so.
     *
     * It previously pinned `includeScreenTrackInInitialOffer` to `false`, on
     * the reasoning that screen media would be added later by an explicit
     * screen-session negotiation path. That path was never built, and the flag
     * guarded the only `addTrack` call in the app — so the phone captured into
     * a track attached to no sender and mirroring could never work in either
     * direction.
     *
     * Attaching up front is what makes a video m-line exist from the first
     * exchange, which matters because there is no renegotiation route on this
     * transport: `onRenegotiationNeeded` is a no-op, a second offer for a live
     * attempt is refused, and only the deterministic initiator may offer.
     */
    @Test
    fun screenTrackIsAttachedUpFrontSoMirroringNeedsNoRenegotiation() {
        assertTrue(WebRtcMediaNegotiationPolicy.includeScreenTrackInInitialOffer)
    }
}
