/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TypingReportsTest {
    @Test
    fun aRefusalIsSaidAtOnceThenPacedAndSuccessOnlyClearsOne() {
        val reports = TypingReports(everyMs = 3_000)
        assertFalse("typing that works says nothing", reports.shouldSend(null, 0))
        assertTrue(reports.shouldSend("no-text-field", 100))
        assertFalse("not per key", reports.shouldSend("no-text-field", 200))
        assertTrue("a different reason at once", reports.shouldSend("not-editable", 300))
        assertFalse(reports.shouldSend("not-editable", 3_299))
        assertTrue("the same again after a while", reports.shouldSend("not-editable", 3_300))
        assertTrue("typed after a refusal: cleared", reports.shouldSend(null, 3_400))
        assertFalse(reports.shouldSend(null, 3_500))
        assertTrue(reports.shouldSend("not-editable", 3_600))
    }

    @Test
    fun aNewShareStartsWithNothingSaid() {
        val reports = TypingReports(everyMs = 3_000)
        assertTrue(reports.shouldSend("no-text-field", 0, "share-1"))
        assertFalse(reports.shouldSend("no-text-field", 10, "share-1"))
        assertTrue("the new window hears it at once", reports.shouldSend("no-text-field", 20, "share-2"))
        assertFalse("nothing to clear in a new share", reports.shouldSend(null, 30, "share-3"))
    }
}
