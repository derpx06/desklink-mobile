package org.desklink.mobile.webrtc

import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRtcFeatureProfileTest {

    /**
     * Pins the wire contract. The desktop client filters its own capabilities
     * with an identical set and the handover compares the two lists for exact
     * equality, so this list and `DESKTOP_INCOMING`/`DESKTOP_OUTGOING` in the
     * desktop's `features/registry.rs` must be edited together. A one-sided
     * change fails here rather than breaking every paired feature at runtime.
     */
    @Test
    fun profileMatchesTheDesktopFeatureRegistry() {
        assertEquals(
            listOf(
                "desklink.battery",
                "desklink.clipboard",
                "desklink.clipboard.connect",
                "desklink.findmyphone.request",
                "desklink.lock",
                "desklink.lock.request",
                "desklink.mousepad.request",
                "desklink.notification",
                "desklink.notification.action",
                "desklink.notification.reply",
                "desklink.notification.request",
                "desklink.ping",
                "desklink.sftp",
                "desklink.sftp.request",
                "desklink.share.request",
            ),
            WebRtcFeatureProfile.capabilities(ALL_PACKET_TYPES),
        )
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

    /**
     * MPRIS, system volume and run-command are implemented on this application
     * in the phone-controls-desktop direction only, and the desktop has no
     * backend to answer them. Signaling is bootstrap transport metadata and is
     * never a feature capability.
     */
    @Test
    fun featuresWithoutADesktopCounterpartStayDisabled() {
        assertFalse(WebRtcFeatureProfile.allows(DeskLinkProtocol.PACKET_TYPE_MPRIS_REQUEST))
        assertFalse(WebRtcFeatureProfile.allows(DeskLinkProtocol.PACKET_TYPE_MPRIS))
        assertFalse(WebRtcFeatureProfile.allows(DeskLinkProtocol.PACKET_TYPE_SYSTEMVOLUME_REQUEST))
        assertFalse(WebRtcFeatureProfile.allows(DeskLinkProtocol.PACKET_TYPE_RUNCOMMAND_REQUEST))
        assertFalse(WebRtcFeatureProfile.allows(DeskLinkProtocol.PACKET_TYPE_SCREEN_REQUEST))
        assertFalse(WebRtcFeatureProfile.allows(DeskLinkProtocol.PACKET_TYPE_SMS_REQUEST))
        assertFalse(WebRtcFeatureProfile.allows(DeskLinkProtocol.PACKET_TYPE_WEBRTC_SIGNAL_V1))
    }

    private companion object {
        val ALL_PACKET_TYPES = setOf(
            DeskLinkProtocol.PACKET_TYPE_BATTERY,
            DeskLinkProtocol.PACKET_TYPE_CLIPBOARD,
            DeskLinkProtocol.PACKET_TYPE_CLIPBOARD_CONNECT,
            DeskLinkProtocol.PACKET_TYPE_CONNECTIVITY_REPORT,
            DeskLinkProtocol.PACKET_TYPE_FINDMYPHONE_REQUEST,
            DeskLinkProtocol.PACKET_TYPE_LOCK,
            DeskLinkProtocol.PACKET_TYPE_LOCK_REQUEST,
            DeskLinkProtocol.PACKET_TYPE_MOUSEPAD_ECHO,
            DeskLinkProtocol.PACKET_TYPE_MOUSEPAD_KEYBOARDSTATE,
            DeskLinkProtocol.PACKET_TYPE_MOUSEPAD_REQUEST,
            DeskLinkProtocol.PACKET_TYPE_MPRIS,
            DeskLinkProtocol.PACKET_TYPE_MPRIS_REQUEST,
            DeskLinkProtocol.PACKET_TYPE_NOTIFICATION,
            DeskLinkProtocol.PACKET_TYPE_NOTIFICATION_ACTION,
            DeskLinkProtocol.PACKET_TYPE_NOTIFICATION_CANCEL,
            DeskLinkProtocol.PACKET_TYPE_NOTIFICATION_REPLY,
            DeskLinkProtocol.PACKET_TYPE_NOTIFICATION_REQUEST,
            DeskLinkProtocol.PACKET_TYPE_PING,
            DeskLinkProtocol.PACKET_TYPE_PRESENTER,
            DeskLinkProtocol.PACKET_TYPE_RUNCOMMAND,
            DeskLinkProtocol.PACKET_TYPE_RUNCOMMAND_REQUEST,
            DeskLinkProtocol.PACKET_TYPE_SCREEN_REQUEST,
            DeskLinkProtocol.PACKET_TYPE_SFTP,
            DeskLinkProtocol.PACKET_TYPE_SFTP_REQUEST,
            DeskLinkProtocol.PACKET_TYPE_SHARE_REQUEST,
            DeskLinkProtocol.PACKET_TYPE_SHARE_REQUEST_UPDATE,
            DeskLinkProtocol.PACKET_TYPE_SMS_REQUEST,
            DeskLinkProtocol.PACKET_TYPE_SYSTEMVOLUME,
            DeskLinkProtocol.PACKET_TYPE_SYSTEMVOLUME_REQUEST,
            DeskLinkProtocol.PACKET_TYPE_TELEPHONY_REQUEST,
            DeskLinkProtocol.PACKET_TYPE_WEBRTC_SIGNAL_V1,
        )
    }
}
