/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingRepliesTest {
    @Test
    fun anUpdateOfTheSameNotificationReplacesItsHandleInsteadOfPilingUp() {
        val replies = PendingReplies<String>()
        repeat(500) { replies.put("chat", "reply-$it", "h$it") }
        assertEquals(1, replies.size)
        assertNull(replies.get("reply-0"))
        assertEquals("h499", replies.get("reply-499"))
    }

    @Test
    fun aRemovedNotificationDropsItsHandleAndAUsedOneCannotBeUsedTwice() {
        val replies = PendingReplies<String>()
        replies.put("a", "r1", "x")
        replies.put("b", "r2", "y")
        replies.removeKey("a")
        assertNull(replies.get("r1"))
        assertEquals("y", replies.take("r2"))
        assertNull(replies.take("r2"))
        assertEquals(0, replies.size)
    }

    @Test
    fun theTotalIsBoundedWhateverTheAppsDo() {
        val replies = PendingReplies<Int>(max = 50)
        repeat(1000) { replies.put("k$it", "r$it", it) }
        assertEquals(50, replies.size)
        assertNull(replies.get("r0"))
        assertEquals(999, replies.get("r999"))
    }
}
