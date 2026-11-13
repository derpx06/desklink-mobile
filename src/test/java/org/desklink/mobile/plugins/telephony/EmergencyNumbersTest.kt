/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.telephony

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The numbers neither app dials for the computer: the shared list (wire_values.json), bare, prefixed, or behind a country code. */
class EmergencyNumbersTest {
    private val shared = JSONObject(javaClass.getResourceAsStream("/shared/wire_values.json")!!.bufferedReader().readText())
        .getJSONObject("telephony").getJSONArray("emergency").let { a -> (0 until a.length()).map(a::getString) }

    @Test
    fun theListIsTheDesksAndEveryFormOfItIsRefused() {
        assertEquals(shared, CallRequests.EMERGENCY)
        for (number in shared) {
            for (dialled in listOf(number, "+$number", "00$number", "+44$number")) assertTrue(dialled, CallRequests.isEmergency(dialled))
        }
    }

    @Test
    fun australias000AndCountryCodeFormsAreRefused() {
        // The same cases as the desk's emergency_numbers_are_refused_and_premium_ones_flagged.
        for (n in listOf("112", "911", "+112", "00112", "999", "000", "+000", "+44 999", "0044 112", "+1 911", "+33112", "+61 000")) {
            assertTrue(n, CallRequests.isEmergency(n))
        }
        for (n in listOf("1120", "5551234", "+14155550100", "11", "+4420 7946 0000", "+1911555")) {
            assertFalse(n, CallRequests.isEmergency(n))
        }
    }
}
