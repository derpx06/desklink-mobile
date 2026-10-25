/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.protocol

import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shared fixture (workspace protocol/testdata/desklink_v9/packet_types.json, copied into both repos): the packet-type strings
 * the desktop and this app both speak. A constant added or changed on one side only fails here and in the desktop's twin test.
 */
class PacketTypesFixtureTest {
    private val fixture: List<String> = run {
        val text = javaClass.getResourceAsStream("/protocol/packet_types.json")!!.bufferedReader().readText()
        val types = JSONObject(text).getJSONArray("packetTypes")
        (0 until types.length()).map { types.getString(it) }.sorted()
    }

    // Constant names differ between the apps; the wire strings are what must agree.
    private val constants: List<String> = DeskLinkProtocol::class.java.declaredFields
        .filter { it.name.startsWith("PACKET_TYPE_") }
        .map { it.isAccessible = true; it.get(null) as String }.sorted()

    @Test
    fun everyPacketTypeIsInTheSharedFixtureAndNothingElse() {
        assertEquals(fixture, constants)
    }
}
