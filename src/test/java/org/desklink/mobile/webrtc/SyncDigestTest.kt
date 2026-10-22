/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.plugins.contacts.ContactsPlugin
import org.desklink.mobile.plugins.notifications.NotificationsPlugin
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SyncDigestTest {
    private var mine = "me-1"
    private val digest = SyncDigest { mine }

    private fun have(peerInstance: String, vararg revs: Pair<String, String>) =
        NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SYNC_HAVE).apply {
            this["peerInstance"] = peerInstance
            this["have"] = JSONObject().also { body -> revs.forEach { body.put(it.first, it.second) } }
        }

    @Test
    fun everythingIsSentUntilThePeerHasSaidWhatItHolds() {
        digest.endLink()
        assertTrue(digest.shouldSend(SyncDigest.CONTACTS, "r1"))
        val ran = mutableListOf<String>()
        var timeout: Runnable? = null
        digest.whenHave(1000, { _, task -> timeout = task }, { ran += "go" })
        assertEquals("nothing came yet", emptyList<String>(), ran)
        timeout!!.run()
        assertEquals("the wait timed out: everything is sent", listOf("go"), ran)
        timeout!!.run()
        assertEquals("only once", listOf("go"), ran)
    }

    @Test
    fun onlyAFeatureWhoseRevTheyHoldIsSkipped() {
        digest.endLink()
        digest.onHave(have("me-1", SyncDigest.CONTACTS to "r1", SyncDigest.NOTES to "old"))
        assertFalse(digest.shouldSend(SyncDigest.CONTACTS, "r1"))
        assertTrue("changed since", digest.shouldSend(SyncDigest.NOTES, "new"))
        assertTrue("not mentioned", digest.shouldSend(SyncDigest.NOTIFICATIONS, "r1"))
    }

    @Test
    fun aHaveForAnOlderProcessOfMineMeansANewEpochAndEverythingIsSent() {
        digest.endLink()
        digest.onHave(have("me-0", SyncDigest.CONTACTS to "r1"))
        assertTrue(digest.shouldSend(SyncDigest.CONTACTS, "r1"))
    }

    @Test
    fun aNewLinkForgetsTheLastHave() {
        digest.endLink()
        digest.onHave(have("me-1", SyncDigest.CONTACTS to "r1"))
        digest.endLink()
        assertTrue(digest.shouldSend(SyncDigest.CONTACTS, "r1"))
    }

    @Test
    fun aMalformedHaveIsIgnored() {
        digest.endLink()
        digest.onHave(NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SYNC_HAVE))
        digest.onHave(have("me-1", "x".repeat(SyncDigest.MAX_FEATURE_LENGTH + 1) to "r"))
        digest.onHave(have("me-1", SyncDigest.CONTACTS to "r".repeat(SyncDigest.MAX_REV_LENGTH + 1)))
        digest.onHave(have("m".repeat(SyncDigest.MAX_REV_LENGTH + 1), SyncDigest.CONTACTS to "r1"))
        val tooMany = have("me-1", *Array(SyncDigest.MAX_FEATURES + 1) { "f$it" to "r" })
        digest.onHave(tooMany)
        var ran = false
        digest.whenHave(1000, { _, _ -> }, { ran = true })
        assertFalse("no usable have arrived", ran)
        assertTrue(digest.shouldSend(SyncDigest.CONTACTS, "r1"))
    }

    @Test
    fun whatIsAppliedIsReportedBackForThatPeerProcessOnly() {
        val settings = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SETTINGS).apply { this["rev"] = "s1" }
        val media = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_MPRIS).apply { this["rev"] = "m1" }
        // Notes and snippets are an index followed by a fetch round: never reported as held.
        val notes = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_NOTES).apply { this["rev"] = "n1" }
        val battery = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_BATTERY).apply { this["rev"] = "ignored" }
        val noRev = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SNIPPETS)
        listOf(settings, media, notes, battery, noRev).forEach { digest.record("desk-1", it) }
        val reported = digest.havePacket("desk-1")
        assertEquals("desk-1", reported.getString("peerInstance"))
        val body = reported.getJSONObject("have")!!
        assertEquals("s1", body.getString(SyncDigest.SETTINGS))
        assertEquals("m1", body.getString(SyncDigest.MEDIA))
        assertEquals(2, body.length())
        // A restarted desk (another instance) is a new epoch: nothing is offered for it.
        assertEquals(0, digest.havePacket("desk-2").getJSONObject("have")!!.length())
        assertEquals(0, digest.havePacket(null).getJSONObject("have")!!.length())
    }

    @Test
    fun onlyASnapshotNotificationCountsAsTheNotificationsFeature() {
        assertEquals(SyncDigest.NOTIFICATIONS, SyncDigest.featureOf(NetworkPacket(DeskLinkProtocol.PACKET_TYPE_NOTIFICATION).apply { this["snapshot"] = true }))
        assertNull(SyncDigest.featureOf(NetworkPacket(DeskLinkProtocol.PACKET_TYPE_NOTIFICATION)))
        assertEquals(SyncDigest.MEDIA, SyncDigest.featureOf(NetworkPacket(DeskLinkProtocol.PACKET_TYPE_MPRIS)))
        assertNull(SyncDigest.featureOf(NetworkPacket(DeskLinkProtocol.PACKET_TYPE_BATTERY)))
    }

    @Test
    fun theHaveCrossesTheChannelAndStaysSmallEvenWhenFull() {
        digest.record("desk-1", NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SETTINGS).apply { this["rev"] = "r".repeat(SyncDigest.MAX_REV_LENGTH) })
        val packet = digest.havePacket("d".repeat(32))
        assertAllCross(listOf(packet))
        assertTrue(wireSize(packet) < 1024)
    }

    @Test
    fun revsAreStableOrderSensitiveAndUnambiguous() {
        assertEquals(SyncDigest.rev(listOf("a", "b")), SyncDigest.rev(listOf("a", "b")))
        assertNotEquals(SyncDigest.rev(listOf("ab", "c")), SyncDigest.rev(listOf("a", "bc")))
        assertTrue(SyncDigest.rev(listOf("x")).length <= SyncDigest.MAX_REV_LENGTH)
        // Notifications: the same set in any order is the same rev; an update (new post time) changes it.
        val a = NotificationsPlugin.snapshotRev(listOf("n1" to 10L, "n2" to 20L))
        assertEquals(a, NotificationsPlugin.snapshotRev(listOf("n2" to 20L, "n1" to 10L)))
        assertNotEquals(a, NotificationsPlugin.snapshotRev(listOf("n1" to 10L, "n2" to 21L)))
        assertNotEquals(a, NotificationsPlugin.snapshotRev(listOf("n1" to 10L)))
        assertEquals(ContactsPlugin.contactsRev(mapOf("a" to 1L, "b" to 2L)), ContactsPlugin.contactsRev(mapOf("b" to 2L, "a" to 1L)))
        assertNotEquals(ContactsPlugin.contactsRev(mapOf("a" to 1L)), ContactsPlugin.contactsRev(mapOf("a" to 2L)))
    }

    /** A `have` that arrives before this side's own feature-ready is kept for this connection only. */
    @Test
    fun aHaveThatArrivedEarlyIsUsedAndAStaleOneIsForgotten() {
        digest.onHave(have("me-1", SyncDigest.CONTACTS to "r1"))
        var ran = 0
        digest.whenHave(1000, { _, _ -> fail("must not wait: the have is already here") }, { ran++ })
        assertEquals(1, ran)
        assertFalse(digest.shouldSend(SyncDigest.CONTACTS, "r1"))
        // The connection ends: the next one starts with nothing known.
        digest.endLink()
        assertTrue(digest.shouldSend(SyncDigest.CONTACTS, "r1"))
        var waited = false
        digest.whenHave(1000, { _, _ -> waited = true }, { })
        assertTrue("with no have it waits (without blocking)", waited)
    }

    @Test
    fun aWaitingFeatureReadyRunsTheMomentTheHaveArrives() {
        var ran = 0
        digest.whenHave(1000, { _, _ -> }, { ran++ })
        assertEquals(0, ran)
        digest.onHave(have("me-1", SyncDigest.CONTACTS to "r1"))
        assertEquals(1, ran)
    }

    @Test
    fun endingTheLinkCancelsAWaitSoItCannotRunAgainstTheNextConnection() {
        var ran = 0
        var timer: Runnable? = null
        digest.whenHave(1000, { _, t -> timer = t }, { ran++ })
        digest.endLink()
        digest.onHave(have("me-1", SyncDigest.CONTACTS to "r1")) // the next connection's have
        assertEquals("the old wait was cancelled, not run by it", 0, ran)
        timer!!.run() // its timeout still fires, and finds its connection gone
        assertEquals(0, ran)
    }

    @Test
    fun theWaitIsOneSecond() {
        assertEquals(1000L, SyncDigest.SYNC_WAIT_MS)
    }
}
