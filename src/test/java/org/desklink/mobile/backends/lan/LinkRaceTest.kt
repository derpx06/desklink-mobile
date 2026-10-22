/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.backends.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkRaceTest {
    private val phone = "phone-b" // sorts higher: the desk "aaa" is the initiator
    private val desk = "desk-a"

    @Test
    fun aLoneLinkIsAlwaysKept() {
        assertTrue(LinkRace().arrive(desk, phone, weDialed = true, now = 0))
    }

    @Test
    fun withinTheWindowTheLowerIdsDialSurvivesWhateverTheArrivalOrder() {
        // The desk (lower) dialed: it wins whether its socket arrived first or second.
        val first = LinkRace()
        assertTrue(first.arrive(desk, phone, weDialed = false, now = 0)) // desk's dial
        assertFalse(first.arrive(desk, phone, weDialed = true, now = 500)) // phone's dial loses
        val second = LinkRace()
        assertTrue(second.arrive(desk, phone, weDialed = true, now = 0)) // phone's dial first
        assertTrue(second.arrive(desk, phone, weDialed = false, now = 500)) // desk's dial replaces it
    }

    @Test
    fun bothSidesKeepTheSameSocket() {
        // From the desk's side the same two sockets: the desk's own dial is "weDialed".
        val deskView = LinkRace()
        assertTrue(deskView.arrive(phone, desk, weDialed = true, now = 0))
        assertFalse(deskView.arrive(phone, desk, weDialed = false, now = 300)) // the phone's dial loses here too
    }

    @Test
    fun afterTheWindowOrFromTheSameDialerTheNewestReplaces() {
        val race = LinkRace()
        assertTrue(race.arrive(desk, phone, weDialed = false, now = 0))
        assertTrue(race.arrive(desk, phone, weDialed = true, now = LinkRace.WINDOW_MS)) // a real reconnect
        assertTrue(race.arrive(desk, phone, weDialed = true, now = LinkRace.WINDOW_MS + 100)) // same dialer
    }

    @Test
    fun aLostLinkMakesTheNextOneNoRace() {
        val race = LinkRace()
        assertTrue(race.arrive(desk, phone, weDialed = false, now = 0))
        race.forget(desk)
        assertTrue(race.arrive(desk, phone, weDialed = true, now = 100))
    }

    @Test
    fun desksAreIndependent() {
        val race = LinkRace()
        assertTrue(race.arrive("desk-a", phone, weDialed = false, now = 0))
        assertTrue(race.arrive("desk-c", phone, weDialed = true, now = 100))
        assertEquals(1_000L, LinkRace.WINDOW_MS)
    }
}
