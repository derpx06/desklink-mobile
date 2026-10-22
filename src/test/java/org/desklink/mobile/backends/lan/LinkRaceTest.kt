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

    /**
     * The desk's `link_race` (DeskLink/src/device_links/core/session_link.rs), ported line for line:
     * the phone must give the same answer for every arrival order, or the two sides close each other's socket.
     */
    private enum class Verdict { NOT_A_RACE, KEEP_INCUMBENT, REPLACE_INCUMBENT }

    private fun deskLinkRace(incumbentDialedByUs: Boolean, ageMs: Long, newDialedByUs: Boolean, localId: String, peerId: String): Verdict {
        if (incumbentDialedByUs == newDialedByUs || ageMs >= LinkRace.WINDOW_MS) return Verdict.NOT_A_RACE
        val lowerDialerIsUs = localId < peerId
        return if (incumbentDialedByUs == lowerDialerIsUs) Verdict.KEEP_INCUMBENT else Verdict.REPLACE_INCUMBENT
    }

    @Test
    fun thePhoneAnswersExactlyLikeTheDeskForEveryArrivalOrderAndTheWindowEdges() {
        for ((local, peer) in listOf("a" to "b", "b" to "a")) {
            for (incumbentOurs in listOf(true, false)) for (newOurs in listOf(true, false)) for (age in listOf(0L, 10L, 200L, 999L, 1000L, 1500L)) {
                // The phone is "local" here; "ours" means the phone dialed.
                val race = LinkRace()
                assertTrue(race.arrive(peer, local, weDialed = incumbentOurs, now = 0))
                val keptNew = race.arrive(peer, local, weDialed = newOurs, now = age)
                val desk = deskLinkRace(incumbentOurs, age, newOurs, local, peer)
                assertEquals("local=$local incumbentOurs=$incumbentOurs newOurs=$newOurs age=$age", desk != Verdict.KEEP_INCUMBENT, keptNew)
            }
        }
    }

    @Test
    fun bothSidesEndWithTheSameDialersLinkInEveryOrder() {
        // Two links: one dialed by the desk ("a"), one by the phone ("b"). The desk sees them as (ours, theirs), the phone as (theirs, ours).
        for ((deskId, phoneId) in listOf("a" to "b", "b" to "a")) for (deskDialedFirst in listOf(true, false)) {
            val phoneRace = LinkRace()
            val phoneKept = if (deskDialedFirst) {
                phoneRace.arrive(deskId, phoneId, weDialed = false, now = 0)
                if (phoneRace.arrive(deskId, phoneId, weDialed = true, now = 300)) "phone" else "desk"
            } else {
                phoneRace.arrive(deskId, phoneId, weDialed = true, now = 0)
                if (phoneRace.arrive(deskId, phoneId, weDialed = false, now = 300)) "desk" else "phone"
            }
            // The desk's verdict on the same pair of links, in the same order (it sees the first link as incumbent).
            val deskSurvivor = if (deskDialedFirst) {
                if (deskLinkRace(true, 300, false, deskId, phoneId) == Verdict.REPLACE_INCUMBENT) "phone" else "desk"
            } else {
                if (deskLinkRace(false, 300, true, deskId, phoneId) == Verdict.REPLACE_INCUMBENT) "desk" else "phone"
            }
            assertEquals("desk=$deskId phone=$phoneId deskFirst=$deskDialedFirst", deskSurvivor, phoneKept)
            assertEquals("the lower id's dial survives", if (deskId < phoneId) "desk" else "phone", phoneKept)
        }
    }
}
