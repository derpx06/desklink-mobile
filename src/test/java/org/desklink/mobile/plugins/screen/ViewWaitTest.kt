package org.desklink.mobile.plugins.screen

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewWaitTest {
    private val asked = 10_000L

    @Test
    fun `the first seconds are a plain request`() {
        assertEquals(ViewWait.ASKING, ViewWait.of(asked, asked))
        assertEquals(ViewWait.ASKING, ViewWait.of(asked, asked + 2_999))
    }

    @Test
    fun `after three seconds the person is pointed at the computer`() {
        assertEquals(ViewWait.LOOK_AT_DESK, ViewWait.of(asked, asked + 3_000))
        assertEquals(ViewWait.LOOK_AT_DESK, ViewWait.of(asked, asked + 44_999))
    }

    @Test
    fun `after forty five seconds nothing happened and the spinner is given up`() {
        assertEquals(ViewWait.NOTHING_HAPPENED, ViewWait.of(asked, asked + 45_000))
        assertEquals(ViewWait.NOTHING_HAPPENED, ViewWait.of(asked, asked + 10 * 60_000))
    }

    @Test
    fun `asking again starts the count over`() {
        val again = asked + 50_000
        assertEquals(ViewWait.ASKING, ViewWait.of(again, again + 1))
    }

    @Test
    fun `a clock reading before the request is still a plain request`() {
        assertEquals(ViewWait.ASKING, ViewWait.of(asked, asked - 5))
    }
}
