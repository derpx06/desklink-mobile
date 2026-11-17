/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.helpers

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The addresses a TCP connection may come from: the phone's `isPrivateAddress` and the desk's
 * `lan_scope::inbound_source_ok` are pinned by the same list (`shared/lan_source_vectors.json`, an identical
 * copy is `DeskLink/testdata/lan_source_vectors.json`), so the two rules cannot drift. The phone accepts loopback
 * here (its listener refuses it outside debug builds on top of this rule).
 */
class LanSourceVectorsTest {
    /** The list's checksum: the desk's `a_tcp_source_is_local_exactly_as_the_phone_decides` asserts the same constant on its copy. */
    private val vectorsSha256 = "9add4bf6d778fa1a963c71fe46b46fc0344354a1e4f2c85120419467ce4f7abb"

    /**
     * Not in the list on purpose: fec0::/10 (deprecated site-local IPv6: Java's isSiteLocalAddress, so this phone, accepts it;
     * the desk refuses it) and addresses with a scope id (`fe80::1%wlan0` does not parse on the desk).
     */
    @Test
    fun theDeskAndThePhoneDecideTheSameWayOnEveryVectorAddress() {
        val text = javaClass.getResourceAsStream("/shared/lan_source_vectors.json")!!.bufferedReader().readText()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals("the vector list changed: update vectorsSha256 here and in the desk's lan_scope test, and copy the file to both repos", vectorsSha256, digest)
        val vectors = org.json.JSONObject(text)
        val cases = vectors.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val ip = case.getString("ip")
            assertEquals("$ip: ${case.getString("why")}", case.getBoolean("accepted"), isPrivateAddress(InetAddress.getByName(ip)))
        }
    }
}
