/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.mousepad

import org.desklink.mobile.R
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerStatusTest {
    /** F46: a Desk screen that failed (a locked computer, a refusal) said "Connecting to <desk>…" above its reason and Try again. */
    @Test
    fun theMirrorHeadingSaysConnectingOnlyWhileItIs() {
        assertEquals(R.string.remote_connecting_to, mirrorHeading(waiting = true))
        assertEquals(R.string.remote_not_shown, mirrorHeading(waiting = false))
    }
}
