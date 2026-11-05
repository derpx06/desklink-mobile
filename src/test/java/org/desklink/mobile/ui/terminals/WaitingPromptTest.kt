/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.terminals

import org.desklink.mobile.ui.compose.components.SessionPreviews
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WaitingPromptTest {
    private val prompt = listOf("Do you want to proceed?", "❯ 1. Yes", "  2. No, and tell Claude what to do differently")

    @Test fun anOpenPromptOnALiveAttachedSessionWaits() {
        assertTrue(SessionPreviews.isWaiting(true, null, prompt))
    }

    @Test fun aSessionThisPhoneIsNotReceivingIsHistory() {
        assertFalse(SessionPreviews.isWaiting(false, null, prompt))
    }

    @Test fun anExitedSessionDoesNotWait() {
        assertFalse(SessionPreviews.isWaiting(true, 0L, prompt))
    }

    @Test fun aPromptAnsweredAndFollowedByMoreOutputIsNotWaiting() {
        val after = prompt + listOf("running tests", "compiling", "linking", "done")
        assertFalse(SessionPreviews.isWaiting(true, null, after))
    }
}
