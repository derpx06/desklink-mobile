/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.plugins

import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.R
import org.desklink.mobile.plugins.battery.BatteryPlugin
import org.desklink.mobile.plugins.desksettings.DeskSettingsPlugin
import org.desklink.mobile.plugins.desksettings.DeskSettingsState
import org.desklink.mobile.plugins.deskunlock.DeskUnlockPlugin
import org.desklink.mobile.plugins.deskunlock.UnlockOffer
import org.desklink.mobile.plugins.handoff.Handoff
import org.desklink.mobile.plugins.mprisreceiver.MprisReceiverPlugin
import org.desklink.mobile.plugins.runcommand.RunCommandPlugin
import org.desklink.mobile.plugins.terminal.TerminalActivity
import org.desklink.mobile.plugins.terminal.TerminalAttentionNotifier
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.desklink.mobile.webrtc.terminal.TerminalSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's side of the Stage D contract: each packet as the desk sends or reads it. */
class DeskFeaturesTest {
    private fun settings(vararg skip: String) = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SETTINGS).apply {
        mapOf(
            "keepAwake" to true, "clipboard" to false, "notifications" to true, "relayConfigured" to false,
            "relay" to false, "commandAlerts" to true, "unlockNearby" to true,
        ).filterKeys { it !in skip }.forEach { (k, v) -> this[k] = v }
    }

    // ---- §4 settings --------------------------------------------------------------------------

    @Test
    fun theDesksSettingsReportIsReadWhole() {
        assertEquals(
            DeskSettingsState(keepAwake = true, clipboard = false, notifications = true, relayConfigured = false, relay = false, commandAlerts = true, unlockNearby = true),
            DeskSettingsPlugin.parse(settings()),
        )
        // The desk always sends every key; a partial report is not trusted.
        assertNull(DeskSettingsPlugin.parse(settings("unlockNearby")))
    }

    @Test
    fun aSwitchSendsOneKeyUnderSet() {
        val packet = DeskSettingsPlugin.requestPacket(DeskSettingsPlugin.KEEP_AWAKE, true, relayConfigured = false)!!
        assertEquals(DeskLinkProtocol.PACKET_TYPE_SETTINGS_REQUEST, packet.type)
        val set = packet.getJSONObject("set")!!
        assertEquals(1, set.length())
        assertTrue(set.getBoolean("keepAwake"))
        assertNotNull(DeskSettingsPlugin.requestPacket(DeskSettingsPlugin.COMMAND_ALERTS, false, relayConfigured = false))
        // The desk's own: never asked for from here.
        assertNull(DeskSettingsPlugin.requestPacket("unlockNearby", true, relayConfigured = true))
        // No relay at the desk: nothing to turn on.
        assertNull(DeskSettingsPlugin.requestPacket(DeskSettingsPlugin.RELAY, true, relayConfigured = false))
        assertNotNull(DeskSettingsPlugin.requestPacket(DeskSettingsPlugin.RELAY, true, relayConfigured = true))
    }

    @Test
    fun aSwitchShowsTheAskedValueUntilTheDeskReports() {
        assertTrue(DeskSettingsPlugin.shown("keepAwake", reported = false, pending = mapOf("keepAwake" to true)))
        assertFalse(DeskSettingsPlugin.shown("keepAwake", reported = false, pending = mapOf("clipboard" to true)))
        assertTrue(DeskSettingsPlugin.shown("keepAwake", reported = true, pending = emptyMap()))
    }

    @Test
    fun theSessionPasswordFlagIsOptional() {
        assertNull(DeskSettingsPlugin.parse(settings())!!.sessionPasswordSet)
        assertEquals(true, DeskSettingsPlugin.parse(settings().apply { this["sessionPasswordSet"] = true })!!.sessionPasswordSet)
    }

    // ---- §5 unlock ----------------------------------------------------------------------------

    private fun unlockRequest(nonce: String?, reason: String? = "button") = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_UNLOCK_REQUEST).apply {
        nonce?.let { this["nonce"] = it }
        reason?.let { this["reason"] = it }
    }

    private val nonce = "0123456789abcdef0123456789abcdef"

    @Test
    fun onlyAWellFormedNonceIsAnOffer() {
        assertEquals(UnlockOffer(nonce, "button", 5), UnlockOffer.parse(unlockRequest(nonce), now = 5))
        assertEquals("phone", UnlockOffer.parse(unlockRequest(nonce, "phone"), now = 5)?.reason)
        assertEquals("button", UnlockOffer.parse(unlockRequest(nonce, "whatever"), now = 5)?.reason)
        assertNull(UnlockOffer.parse(unlockRequest(null), now = 5))
        assertNull(UnlockOffer.parse(unlockRequest(nonce.uppercase()), now = 5))
        assertNull(UnlockOffer.parse(unlockRequest(nonce.dropLast(1)), now = 5))
        assertNull(UnlockOffer.parse(unlockRequest("$nonce\""), now = 5))
    }

    @Test
    fun anOfferIsUsableForLessThanTheDesksMinute() {
        val offer = UnlockOffer(nonce, "button", receivedAt = 1_000)
        assertTrue(offer.isFresh(1_000 + 54_999))
        assertFalse(offer.isFresh(1_000 + UnlockOffer.FRESH_MS))
    }

    @Test
    fun theApprovalSpendsTheNonceAsALockRequest() {
        val packet = DeskUnlockPlugin.approvalPacket(nonce)
        assertEquals(DeskLinkProtocol.PACKET_TYPE_LOCK_REQUEST, packet.type)
        assertFalse(packet.getBoolean("setLocked", true))
        assertEquals(nonce, packet.getString("nonce"))
    }

    @Test
    fun everyRefusalCodeHasItsOwnWords() {
        assertEquals(R.string.unlock_refused_pref_off, DeskUnlockPlugin.refusalText("pref-off"))
        assertEquals(R.string.unlock_refused_not_lan, DeskUnlockPlugin.refusalText("not-lan"))
        assertEquals(R.string.unlock_refused_not_locked, DeskUnlockPlugin.refusalText("not-locked"))
        assertEquals(R.string.unlock_refused_not_ready, DeskUnlockPlugin.refusalText("not-ready"))
        assertEquals(R.string.unlock_refused_wrong_device, DeskUnlockPlugin.refusalText("wrong-device"))
        for (code in listOf("expired", "no-nonce", "replayed")) assertEquals(R.string.unlock_refused_expired, DeskUnlockPlugin.refusalText(code))
        assertEquals(R.string.unlock_refused_other, DeskUnlockPlugin.refusalText("from-the-future"))
    }

    // ---- §6 hand-off --------------------------------------------------------------------------

    @Test
    fun aHandOffNamesATerminalId() {
        val packet = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_HANDOFF).apply {
            this["sessionId"] = "t-1_a"
            this["title"] = "zsh"
            this["path"] = "~/api"
        }
        assertEquals(Handoff("t-1_a", "zsh", "~/api"), Handoff.parse(packet))
        packet["sessionId"] = "../x"
        assertNull(Handoff.parse(packet))
        assertNull(Handoff.parse(NetworkPacket(DeskLinkProtocol.PACKET_TYPE_HANDOFF)))
    }

    // ---- Phase 1: snapshots and offline actions ----------------------------------------------

    @Test
    fun theNotificationSnapshotListsIdsFirst() {
        val packet = org.desklink.mobile.plugins.notifications.NotificationsPlugin.snapshotPacket(listOf("a", "b"))
        assertEquals(DeskLinkProtocol.PACKET_TYPE_NOTIFICATION, packet.type)
        assertTrue(packet.getBoolean("snapshot", false))
        assertEquals(listOf("a", "b"), packet.getStringList("ids"))
        assertEquals(emptyList<String>(), org.desklink.mobile.plugins.notifications.NotificationsPlugin.snapshotPacket(emptyList()).getStringList("ids"))
    }

    @Test
    fun turningBatteryOffClearsItAtTheDesk() {
        val packet = BatteryPlugin.turnedOffPacket()
        assertEquals(DeskLinkProtocol.PACKET_TYPE_BATTERY, packet.type)
        assertEquals(-1, packet.getInt("currentCharge"))
    }

    @Test
    fun onlyExpiredTapsAreSaidOutLoudNotAutomaticSends() {
        val auto = object : org.desklink.mobile.Device.SendPacketStatusCallback() {
            override fun onSuccess() = Unit
            override fun onFailure(e: Throwable) = Unit
        }
        assertEquals(2, org.desklink.mobile.expiredUserActions(listOf(org.desklink.mobile.UserActionCallback(), auto, org.desklink.mobile.UserActionCallback(auto))))
        assertEquals(0, org.desklink.mobile.expiredUserActions(listOf(auto, auto)))
    }

    @Test
    fun onlyARefusalNamingTheRecentTapAnswersIt() {
        // The desk's own push (no key, or another command's) never re-runs the tap.
        assertFalse(RunCommandPlugin.isTapRefusal(null, "k1", 0, 1_000))
        assertFalse(RunCommandPlugin.isTapRefusal("k2", "k1", 0, 1_000))
        assertFalse(RunCommandPlugin.isTapRefusal("k1", null, 0, 1_000))
        assertFalse(RunCommandPlugin.isTapRefusal("k1", "k1", 0, 60_000))
        assertTrue(RunCommandPlugin.isTapRefusal("k1", "k1", 0, 1_000))
    }

    // ---- §2 run command -----------------------------------------------------------------------

    @Test
    fun aLockedRunIsRetriedOnceAfterThePassword() {
        assertEquals(RunCommandPlugin.RefusalAction.PROVE_PASSWORD_AND_RETRY, RunCommandPlugin.refusalAction("locked", false))
        assertEquals(RunCommandPlugin.RefusalAction.TELL_LOCKED, RunCommandPlugin.refusalAction("locked", true))
        assertEquals(RunCommandPlugin.RefusalAction.TELL_TERMINAL_OFF, RunCommandPlugin.refusalAction("terminal-off", false))
    }

    private fun session(id: String, title: String, startedAt: Long?, exit: Long? = null, gone: Boolean = false) =
        TerminalSessionState(id, title, 24, 80, exitStatus = exit, attached = false, wanted = false, gone = gone, startedAt = startedAt)

    @Test
    fun theRunsTerminalIsTheNewestWithItsName() {
        val sessions = listOf(
            session("old", "deploy", startedAt = 100),
            session("other", "build", startedAt = 2_000),
            session("ended", "deploy", startedAt = 2_000, exit = 0),
            session("new", "deploy", startedAt = 1_990),
        )
        assertEquals("new", TerminalActivity.pickStartedSession(sessions, "deploy", since = 2_000))
        assertNull(TerminalActivity.pickStartedSession(sessions.take(1), "deploy", since = 2_000))
        // An older desk without startedAt: the newest with the name.
        assertEquals("b", TerminalActivity.pickStartedSession(listOf(session("a", "x", null), session("b", "x", null)), "x", since = 2_000))
    }

    // ---- §7 finished --------------------------------------------------------------------------

    @Test
    fun aFinishedJobAlertsUnlessItsTerminalIsOnScreen() {
        assertTrue(TerminalAttentionNotifier.shouldAlertFinished(visibleTerminalId = null, terminalId = "t1"))
        assertTrue(TerminalAttentionNotifier.shouldAlertFinished(visibleTerminalId = "t2", terminalId = "t1"))
        assertFalse(TerminalAttentionNotifier.shouldAlertFinished(visibleTerminalId = "t1", terminalId = "t1"))
    }

    @Test
    fun durationsReadNaturally() {
        assertEquals("45 s", TerminalAttentionNotifier.formatDuration(45))
        assertEquals("2 min 4 s", TerminalAttentionNotifier.formatDuration(124))
        assertEquals("1 h 3 min", TerminalAttentionNotifier.formatDuration(3_780))
    }

    // ---- §1 media, §8 battery -----------------------------------------------------------------

    @Test
    fun seekIsMicrosecondsAndNeverBeforeTheStart() {
        assertEquals(15_000L, MprisReceiverPlugin.seekTarget(5_000, 10_000_000))
        assertEquals(0L, MprisReceiverPlugin.seekTarget(5_000, -10_000_000))
    }

    @Test
    fun chargeTimeIsWholeMinutesOrMinusOne() {
        assertEquals(-1L, BatteryPlugin.chargeTimeRemaining(isCharging = false, rawMillis = 600_000))
        assertEquals(-1L, BatteryPlugin.chargeTimeRemaining(isCharging = true, rawMillis = -1))
        assertEquals(-1L, BatteryPlugin.chargeTimeRemaining(isCharging = true, rawMillis = 0))
        assertEquals(1_800_000L, BatteryPlugin.chargeTimeRemaining(isCharging = true, rawMillis = 1_859_999))
    }

    /** S1 with hundreds of long ids: the snapshot still crosses the data channel. */
    @Test
    fun aHugeNotificationSnapshotStillCrosses() {
        val ids = (0 until 2_000).map { "0|com.example.app|$it|tag-${"t".repeat(80)}|10001" }
        val packet = org.desklink.mobile.plugins.notifications.NotificationsPlugin.snapshotPacket(ids)
        org.desklink.mobile.webrtc.assertAllCross(listOf(packet))
        org.junit.Assert.assertTrue(packet.getStringList("ids")!!.size in 100 until 2_000)
    }

    /** A huge notification (long text and conversation) is cut to cross; big clipboard text is not shared. */
    @Test
    fun bigNotificationsAndClipboardTextStayWithinTheWire() {
        val np = NetworkPacket(org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol.PACKET_TYPE_NOTIFICATION).apply {
            this["id"] = "k"
            this["text"] = "t".repeat(100_000)
            this["ticker"] = "x".repeat(100_000)
            this["conversation"] = org.json.JSONArray().apply { repeat(500) { put(org.json.JSONObject().put("text", "m".repeat(500))) } }
        }
        org.desklink.mobile.plugins.notifications.NotificationsPlugin.fitToWire(np)
        org.desklink.mobile.webrtc.assertAllCross(listOf(np))
        org.junit.Assert.assertTrue(np.getString("text").length <= org.desklink.mobile.plugins.notifications.NotificationsPlugin.MAX_TEXT_CHARS + 1)
        val clipboard = org.desklink.mobile.plugins.clipboard.ClipboardPlugin
        org.junit.Assert.assertTrue(clipboard.fitsWire("a".repeat(30_000)))
        org.junit.Assert.assertFalse(clipboard.fitsWire("a".repeat(60_000)))
        val longest = "a".repeat(clipboard.MAX_SHARED_TEXT_BYTES - 2)
        org.desklink.mobile.webrtc.assertAllCross(listOf(NetworkPacket(org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol.PACKET_TYPE_CLIPBOARD).apply { this["content"] = longest }))
    }
}
