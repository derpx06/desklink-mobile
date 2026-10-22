package org.desklink.mobile.webrtc

import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRtcFeatureProfileTest {

    /**
     * Pins the wire contract: byte-identical to the desktop's `WEBRTC_PROFILE`
     * (`profile_matches_the_android_webrtc_feature_profile`). The handover now
     * uses the intersection of the two apps' lists, so a one-sided edit no
     * longer breaks every feature, but the lists are still kept in step.
     */
    @Test
    fun profileMatchesTheDesktopFeatureRegistry() {
        assertEquals(
            listOf(
                "desklink.battery",
                "desklink.clipboard",
                "desklink.clipboard.connect",
                "desklink.contacts.request_all_uids_timestamps",
                "desklink.contacts.request_vcards_by_uid",
                "desklink.contacts.response_uids_timestamps",
                "desklink.contacts.response_vcards",
                "desklink.findmyphone.request",
                "desklink.folders",
                "desklink.handoff",
                "desklink.lock",
                "desklink.lock.request",
                "desklink.mousepad.keyboardstate",
                "desklink.mousepad.request",
                "desklink.mpris",
                "desklink.mpris.request",
                "desklink.notes",
                "desklink.notification",
                "desklink.notification.action",
                "desklink.notification.reply",
                "desklink.notification.request",
                "desklink.ping",
                "desklink.settings",
                "desklink.settings.request",
                "desklink.share.request",
                "desklink.shareinputdevices",
                "desklink.shareinputdevices.request",
                "desklink.snippets",
                "desklink.sync.have",
                "desklink.telephony",
                "desklink.telephony.request",
                "desklink.unlock.request",
            ),
            WebRtcFeatureProfile.capabilities(ALL_PACKET_TYPES),
        )
    }

    @Test
    fun aPeerMayClaimLessThanItsIdentityButNeverMore() {
        val identity = listOf(DeskLinkProtocol.PACKET_TYPE_PING, DeskLinkProtocol.PACKET_TYPE_BATTERY, REMOVED_SMS)
        assertTrue(WebRtcFeatureProfile.handoverListIsConsistent(listOf(DeskLinkProtocol.PACKET_TYPE_PING), identity))
        assertTrue(WebRtcFeatureProfile.handoverListIsConsistent(emptyList(), identity))
        // Types outside this profile are filtered from both sides first.
        assertTrue(WebRtcFeatureProfile.handoverListIsConsistent(listOf(DeskLinkProtocol.PACKET_TYPE_PING, "desklink.future"), identity))
        assertFalse(WebRtcFeatureProfile.handoverListIsConsistent(listOf(DeskLinkProtocol.PACKET_TYPE_SETTINGS), identity))
        assertFalse(WebRtcFeatureProfile.handoverListIsConsistent(listOf(DeskLinkProtocol.PACKET_TYPE_PING), null))
    }

    @Test
    fun onlyTheIntersectionIsSentAndAccepted() {
        val id = "older-desk"
        // An older desk: no settings, no unlock; it receives ping and sends battery.
        WebRtcFeatureProfile.recordPeer(
            id,
            incoming = listOf(DeskLinkProtocol.PACKET_TYPE_PING, DeskLinkProtocol.PACKET_TYPE_BATTERY, "desklink.unknown"),
            outgoing = listOf(DeskLinkProtocol.PACKET_TYPE_BATTERY, REMOVED_SMS),
        )
        try {
            assertTrue(WebRtcFeatureProfile.peerAccepts(id, DeskLinkProtocol.PACKET_TYPE_PING))
            assertFalse(WebRtcFeatureProfile.peerAccepts(id, DeskLinkProtocol.PACKET_TYPE_SETTINGS_REQUEST))
            assertFalse(WebRtcFeatureProfile.peerAccepts(id, DeskLinkProtocol.PACKET_TYPE_UNLOCK_REQUEST))
            assertFalse(WebRtcFeatureProfile.peerAccepts(id, "desklink.unknown"))
            assertTrue(WebRtcFeatureProfile.peerSends(id, DeskLinkProtocol.PACKET_TYPE_BATTERY))
            assertFalse(WebRtcFeatureProfile.peerSends(id, DeskLinkProtocol.PACKET_TYPE_SETTINGS))
            // Outside this profile, even when the peer claims it.
            assertFalse(WebRtcFeatureProfile.peerSends(id, REMOVED_SMS))
        } finally {
            WebRtcFeatureProfile.forgetPeer(id)
        }
        // Before a handover only this profile applies.
        assertTrue(WebRtcFeatureProfile.peerAccepts(id, DeskLinkProtocol.PACKET_TYPE_SETTINGS_REQUEST))
        assertFalse(WebRtcFeatureProfile.peerAccepts(id, REMOVED_SMS))
    }

    @Test
    fun capabilityFilteringDropsSignalingAndUnsupportedDirections() {
        val capabilities = setOf(
            DeskLinkProtocol.PACKET_TYPE_PING,
            DeskLinkProtocol.PACKET_TYPE_FINDMYPHONE_REQUEST,
            DeskLinkProtocol.PACKET_TYPE_MOUSEPAD_REQUEST,
            DeskLinkProtocol.PACKET_TYPE_WEBRTC_SIGNAL_V1,
        )

        assertEquals(
            listOf(
                DeskLinkProtocol.PACKET_TYPE_FINDMYPHONE_REQUEST,
                DeskLinkProtocol.PACKET_TYPE_MOUSEPAD_REQUEST,
                DeskLinkProtocol.PACKET_TYPE_PING,
            ),
            WebRtcFeatureProfile.capabilities(capabilities),
        )
    }

    @Test
    fun featuresWithHandlersOnBothApplicationsAreEnabled() {
        assertTrue(WebRtcFeatureProfile.allows(DeskLinkProtocol.PACKET_TYPE_PING))
        assertTrue(WebRtcFeatureProfile.allows(DeskLinkProtocol.PACKET_TYPE_FINDMYPHONE_REQUEST))
        assertTrue(WebRtcFeatureProfile.allows(DeskLinkProtocol.PACKET_TYPE_SHARE_REQUEST))
        assertTrue(WebRtcFeatureProfile.allows(DeskLinkProtocol.PACKET_TYPE_CLIPBOARD))
        assertTrue(WebRtcFeatureProfile.allows(DeskLinkProtocol.PACKET_TYPE_NOTIFICATION))
        assertTrue(WebRtcFeatureProfile.allows(DeskLinkProtocol.PACKET_TYPE_MOUSEPAD_REQUEST))
        assertTrue(WebRtcFeatureProfile.allows(DeskLinkProtocol.PACKET_TYPE_BATTERY))
    }

    /** Signaling is bootstrap transport metadata and is never a feature capability. */
    @Test
    fun featuresWithoutACounterpartStayDisabled() {
        assertFalse(WebRtcFeatureProfile.allows(DeskLinkProtocol.PACKET_TYPE_SCREEN_REQUEST))
        assertFalse(WebRtcFeatureProfile.allows(DeskLinkProtocol.PACKET_TYPE_WEBRTC_SIGNAL_V1))
    }

    /**
     * 1.3 removed these features. An older peer may still advertise or send them: they are outside
     * the profile, so they are filtered at the boundary and never reach a handler.
     */
    @Test
    fun removedFeaturesAreOutsideTheProfileAndNeverAdvertised() {
        REMOVED_TYPES.forEach { assertFalse(it, WebRtcFeatureProfile.allows(it)) }
        assertEquals(emptyList<String>(), WebRtcFeatureProfile.capabilities(REMOVED_TYPES))
        val id = "older-peer"
        WebRtcFeatureProfile.recordPeer(id, incoming = REMOVED_TYPES, outgoing = REMOVED_TYPES)
        try {
            REMOVED_TYPES.forEach {
                assertFalse(it, WebRtcFeatureProfile.peerAccepts(id, it))
                assertFalse(it, WebRtcFeatureProfile.peerSends(id, it))
            }
        } finally {
            WebRtcFeatureProfile.forgetPeer(id)
        }
    }

    private companion object {
        const val REMOVED_SMS = "desklink.sms.request"
        val REMOVED_TYPES = listOf(
            "desklink.automations", "desklink.runcommand", "desklink.runcommand.request",
            "desklink.digitizer", "desklink.digitizer.session", "desklink.presenter",
            "desklink.mousepad.echo", "desklink.systemvolume", "desklink.systemvolume.request",
            "desklink.connectivity_report", "desklink.share.request.update",
            "desklink.notification.cancel", "desklink.telephony.request_mute", REMOVED_SMS,
        )
        val ALL_PACKET_TYPES = setOf(
            DeskLinkProtocol.PACKET_TYPE_FOLDERS,
            DeskLinkProtocol.PACKET_TYPE_BATTERY,
            DeskLinkProtocol.PACKET_TYPE_CLIPBOARD,
            DeskLinkProtocol.PACKET_TYPE_CLIPBOARD_CONNECT,
            DeskLinkProtocol.PACKET_TYPE_CONTACTS_REQUEST,
            DeskLinkProtocol.PACKET_TYPE_CONTACTS_REQUEST_ALL_UIDS_TIMESTAMPS,
            DeskLinkProtocol.PACKET_TYPE_CONTACTS_REQUEST_VCARDS_BY_UIDS,
            DeskLinkProtocol.PACKET_TYPE_CONTACTS_RESPONSE_UIDS_TIMESTAMPS,
            DeskLinkProtocol.PACKET_TYPE_CONTACTS_RESPONSE_VCARDS,
            DeskLinkProtocol.PACKET_TYPE_FINDMYPHONE_REQUEST,
            DeskLinkProtocol.PACKET_TYPE_LOCK,
            DeskLinkProtocol.PACKET_TYPE_LOCK_REQUEST,
            DeskLinkProtocol.PACKET_TYPE_MOUSEPAD_KEYBOARDSTATE,
            DeskLinkProtocol.PACKET_TYPE_MOUSEPAD_REQUEST,
            DeskLinkProtocol.PACKET_TYPE_MPRIS,
            DeskLinkProtocol.PACKET_TYPE_MPRIS_REQUEST,
            DeskLinkProtocol.PACKET_TYPE_NOTES,
            DeskLinkProtocol.PACKET_TYPE_NOTIFICATION,
            DeskLinkProtocol.PACKET_TYPE_NOTIFICATION_ACTION,
            DeskLinkProtocol.PACKET_TYPE_NOTIFICATION_REPLY,
            DeskLinkProtocol.PACKET_TYPE_NOTIFICATION_REQUEST,
            DeskLinkProtocol.PACKET_TYPE_PING,
            DeskLinkProtocol.PACKET_TYPE_SCREEN_REQUEST,
            DeskLinkProtocol.PACKET_TYPE_HANDOFF,
            DeskLinkProtocol.PACKET_TYPE_SETTINGS,
            DeskLinkProtocol.PACKET_TYPE_SETTINGS_REQUEST,
            DeskLinkProtocol.PACKET_TYPE_UNLOCK_REQUEST,
            DeskLinkProtocol.PACKET_TYPE_SHARE_REQUEST,
            DeskLinkProtocol.PACKET_TYPE_SHARE_INPUT_DEVICES,
            DeskLinkProtocol.PACKET_TYPE_SHARE_INPUT_DEVICES_REQUEST,
            DeskLinkProtocol.PACKET_TYPE_SNIPPETS,
            DeskLinkProtocol.PACKET_TYPE_SYNC_HAVE,
            DeskLinkProtocol.PACKET_TYPE_TELEPHONY,
            DeskLinkProtocol.PACKET_TYPE_TELEPHONY_REQUEST,
            DeskLinkProtocol.PACKET_TYPE_WEBRTC_SIGNAL_V1,
        )
    }
}
