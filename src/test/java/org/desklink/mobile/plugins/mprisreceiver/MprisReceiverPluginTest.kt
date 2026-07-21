package org.desklink.mobile.plugins.mprisreceiver

import org.desklink.mobile.NetworkPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MprisReceiverPluginTest {
    @Test
    fun missingPlayerFallsBackToFirstActivePlayer() {
        val packet = NetworkPacket("desklink.mpris.request").apply {
            this["action"] = "PlayPause"
        }

        val player = MprisReceiverPlugin.resolvePlayerNameForRequest(
            packet,
            linkedSetOf("Spotify", "VLC")
        )

        assertEquals("Spotify", player)
    }

    @Test
    fun unknownPlayerDoesNotFallBackToAnotherPlayer() {
        val packet = NetworkPacket("desklink.mpris.request").apply {
            this["player"] = "Missing"
            this["action"] = "PlayPause"
        }

        val player = MprisReceiverPlugin.resolvePlayerNameForRequest(
            packet,
            linkedSetOf("Spotify", "VLC")
        )

        assertNull(player)
    }
}
