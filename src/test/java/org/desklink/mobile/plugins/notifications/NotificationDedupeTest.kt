/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationDedupeTest {
    private var now = 0L
    private val dedupe = NotificationDedupe { now }

    @Test
    fun anUnchangedRepostOfTheSameKeyIsNotSentAgain() {
        assertTrue(dedupe.shouldSend("k", "chat", "s1", preexisting = false))
        now += 60_000
        assertFalse("an app re-posting identical content", dedupe.shouldSend("k", "chat", "s1", preexisting = false))
        assertTrue("new words", dedupe.shouldSend("k", "chat", "s2", preexisting = false))
    }

    @Test
    fun aResyncIsAlwaysSentBecauseTheDeskMayHaveForgottenIt() {
        assertTrue(dedupe.shouldSend("k", "chat", "s1", preexisting = false))
        assertTrue(dedupe.shouldSend("k", "chat", "s1", preexisting = true))
        assertFalse("and it is remembered", dedupe.shouldSend("k", "chat", "s1", preexisting = false))
    }

    @Test
    fun aRemovedOneIsSentAgainWhenItComesBack() {
        assertTrue(dedupe.shouldSend("k", "chat", "s1", preexisting = false))
        dedupe.forget("k")
        assertTrue(dedupe.shouldSend("k", "chat", "s1", preexisting = false))
    }

    @Test
    fun aSendThatFailedIsRetried() {
        assertTrue(dedupe.shouldSend("k", "chat", "s1", preexisting = false))
        dedupe.forget("k") // the delivery callback's failure
        assertTrue(dedupe.shouldSend("k", "chat", "s1", preexisting = false))
    }

    @Test
    fun theSameWordsUnderASecondKeyAtOnceAreOneNotification() {
        assertTrue(dedupe.shouldSend("a", "chat", "words", preexisting = false, words = "chat|Mara|hi"))
        now += 1_000
        assertFalse(dedupe.shouldSend("b", "chat", "s", preexisting = false, words = "chat|Mara|hi"))
        now += 10_000
        assertTrue("later it is a new message", dedupe.shouldSend("c", "chat", "s", preexisting = false, words = "chat|Mara|hi"))
    }

    @Test
    fun otherAppsWithTheSameWordsAreNotTwins() {
        assertTrue(dedupe.shouldSend("a", "one", "s", preexisting = false, words = "one|Mara|hi"))
        assertTrue(dedupe.shouldSend("b", "two", "s", preexisting = false, words = "two|Mara|hi"))
    }

    @Test
    fun clearForgetsEverything() {
        dedupe.shouldSend("k", "chat", "s1", preexisting = false)
        dedupe.clear()
        assertTrue(dedupe.shouldSend("k", "chat", "s1", preexisting = false))
    }
}
