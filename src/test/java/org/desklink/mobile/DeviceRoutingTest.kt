/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile

import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceRoutingTest {
    @Test
    fun onlyAShareWithAPayloadIsAFileSendAndANotificationIconIsNot() {
        val share = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SHARE_REQUEST).apply { payload = NetworkPacket.Payload(ByteArray(10)) }
        val icon = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_NOTIFICATION).apply { payload = NetworkPacket.Payload(ByteArray(10)) }
        assertTrue(isFileSend(share))
        assertFalse("a notification icon must not wait behind a transfer", isFileSend(icon))
        assertFalse(isFileSend(NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SHARE_REQUEST)))
    }
}
