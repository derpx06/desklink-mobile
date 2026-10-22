/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.automations

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Contract F7 A1–A6 on the phone. */
@RunWith(RobolectricTestRunner::class)
class RulesTest {
    private val noon = PhoneState(ssid = "\"Home\"", minuteOfDay = 12 * 60, deskConnected = true, deskLocked = false)

    private fun rule(id: String, trigger: Step, vararg actions: Step, conditions: List<Step> = emptyList(), runOn: String = Rules.PHONE, modified: Long = 1) =
        Rule(id, id, trigger = trigger, conditions = conditions, actions = actions.toList(), runOn = runOn, modified = modified)

    @Test
    fun matchingFollowsA6() {
        val home = Step(Rules.T_WIFI, mapOf("state" to "connected", "ssid" to "Home"))
        assertTrue(Rules.matches(home, Event(Rules.T_WIFI, mapOf("state" to "connected", "ssid" to "home"))))
        assertFalse(Rules.matches(home, Event(Rules.T_WIFI, mapOf("state" to "connected", "ssid" to "Work"))))
        assertFalse("a parameter the rule sets must be in the event", Rules.matches(home, Event(Rules.T_WIFI, mapOf("state" to "connected"))))
        assertTrue("absent = any", Rules.matches(Step(Rules.T_WIFI), Event(Rules.T_WIFI, mapOf("state" to "disconnected"))))
        val app = Step(Rules.T_NOTIFICATION, mapOf("app" to "com.Chat"))
        assertTrue(Rules.matches(app, Event(Rules.T_NOTIFICATION, mapOf("app" to "com.Chat"))))
        assertFalse("app is exact", Rules.matches(app, Event(Rules.T_NOTIFICATION, mapOf("app" to "com.chat"))))
        val low = Step(Rules.T_BATTERY, mapOf("below" to 20))
        assertTrue(Rules.matches(low, Event(Rules.T_BATTERY, mapOf("below" to 19))))
        assertTrue(Rules.matches(low, Event(Rules.T_BATTERY, mapOf("below" to 20))))
        assertFalse(Rules.matches(low, Event(Rules.T_BATTERY, mapOf("below" to 21))))
        val weekdays = Step(Rules.T_TIME, mapOf("at" to "07:30", "days" to "12345"))
        assertTrue(Rules.matches(weekdays, AutomationAlarms.timeEvent(at(2026, 10, 22, 7, 30)))) // a Thursday
        assertFalse(Rules.matches(weekdays, AutomationAlarms.timeEvent(at(2026, 10, 24, 7, 30)))) // a Saturday
        assertFalse(Rules.matches(weekdays, AutomationAlarms.timeEvent(at(2026, 10, 22, 7, 31))))
    }

    @Test
    fun conditionsHoldWhereTheRuleRuns() {
        val night = Step(Rules.C_TIME_BETWEEN, mapOf("from" to "22:00", "to" to "07:00"))
        assertTrue(Rules.holds(night, noon.copy(minuteOfDay = 23 * 60)))
        assertTrue(Rules.holds(night, noon.copy(minuteOfDay = 6 * 60 + 59)))
        assertFalse(Rules.holds(night, noon.copy(minuteOfDay = 7 * 60)))
        assertFalse(Rules.holds(Step(Rules.C_TIME_BETWEEN, mapOf("from" to "25:00", "to" to "07:00")), noon))
        assertTrue(Rules.holds(Step(Rules.C_WIFI, mapOf("ssid" to "Home")), noon))
        assertTrue(Rules.holds(Step(Rules.C_PHONE_CONNECTED, mapOf("connected" to true)), noon))
        assertFalse(Rules.holds(Step(Rules.C_PHONE_CONNECTED, mapOf("connected" to false)), noon))
        assertTrue(Rules.holds(Step(Rules.C_DESK_LOCKED, mapOf("locked" to false)), noon))
        assertFalse("unknown lock state never holds", Rules.holds(Step(Rules.C_DESK_LOCKED), noon.copy(deskLocked = null)))
        assertFalse("unknown condition never holds", Rules.holds(Step("future"), noon))
    }

    @Test
    fun onlyLiveEnabledRunnablePhoneRulesAreDue() {
        val trigger = Step(Rules.T_CHARGING, mapOf("state" to "start"))
        val event = Event(Rules.T_CHARGING, mapOf("state" to "start"))
        val ring = Step(Rules.A_RING)
        val rules = listOf(
            rule("a", trigger, ring),
            rule("desk", trigger, ring, runOn = Rules.DESK),
            rule("off", trigger, ring).copy(enabled = false),
            rule("gone", trigger, ring).copy(deleted = true),
            rule("newer", trigger, Step("phone-teleport")),
            rule("cond", trigger, ring, conditions = listOf(Step(Rules.C_PHONE_CONNECTED, mapOf("connected" to false)))),
        )
        assertEquals(listOf("a"), Rules.due(rules, event, noon).map { it.id })
        assertFalse(Rules.runnable(rules[4]))
    }

    @Test
    fun theWireFormRoundTripsAndEveryBoundDropsTheRule() {
        val r = rule("r", Step(Rules.T_BATTERY, mapOf("below" to 15)), Step("keep-awake", mapOf("on" to true)), Step(Rules.A_NOTIFY, mapOf("title" to "Low")),
            conditions = listOf(Step(Rules.C_TIME_BETWEEN, mapOf("from" to "22:00", "to" to "07:00"))), modified = 42)
        assertEquals(r, Rules.ruleFrom(JSONObject(Rules.toJson(r).toString())))
        fun json(change: (JSONObject) -> Unit) = JSONObject(Rules.toJson(r).toString()).also(change)
        assertNull("name", Rules.ruleFrom(json { it.put("name", "n".repeat(Rules.MAX_NAME_CHARS + 1)) }))
        assertNull("id", Rules.ruleFrom(json { it.put("id", "i".repeat(Rules.MAX_ID_CHARS + 1)) }))
        assertNull("runOn", Rules.ruleFrom(json { it.put("runOn", "cloud") }))
        assertNull("no modified", Rules.ruleFrom(json { it.remove("modified") }))
        assertNull("no actions", Rules.ruleFrom(json { it.put("actions", JSONArray()) }))
        assertNull("actions", Rules.ruleFrom(json { o -> o.put("actions", JSONArray().apply { repeat(Rules.MAX_STEPS + 1) { put(Rules.toJson(Step(Rules.A_RING))) } }) }))
        assertNull("params", Rules.ruleFrom(json { o -> o.put("trigger", Rules.toJson(Step(Rules.T_WIFI, (0..Rules.MAX_PARAMS).associate { "k$it" to "v" }))) }))
        assertNull("value", Rules.ruleFrom(json { o -> o.put("trigger", Rules.toJson(Step(Rules.T_WIFI, mapOf("ssid" to "s".repeat(Rules.MAX_VALUE_CHARS + 1))))) }))
        assertNull("nested", Rules.ruleFrom(json { o -> o.put("trigger", JSONObject().put("kind", "wifi").put("params", JSONObject().put("x", JSONObject()))) }))
        // A tombstone may be empty.
        assertNotNull(Rules.ruleFrom(JSONObject().put("id", "t").put("modified", 5).put("runOn", "desk").put("deleted", true)))
    }

    @Test
    fun syncIsLastWriterWinsAndRepliesSplit() {
        val ours = mapOf("a" to rule("a", Step(Rules.T_NFC), Step(Rules.A_RING), modified = 10), "b" to rule("b", Step(Rules.T_NFC), Step(Rules.A_RING), modified = 10))
        val index = JSONArray()
            .put(JSONObject().put("id", "a").put("modified", 11))
            .put(JSONObject().put("id", "b").put("modified", 10))
            .put(JSONObject().put("id", "c").put("modified", 1))
        assertEquals(listOf("a", "c"), Rules.wanted(ours, index))
        assertTrue(Rules.newer(ours.getValue("a").copy(modified = 11), ours["a"]))
        assertFalse("equal keeps ours", Rules.newer(ours.getValue("a"), ours["a"]))
        val many = (0 until Rules.MAX_RULES).map { rule("r$it", Step(Rules.T_NFC), Step(Rules.A_NOTIFY, mapOf("body" to "x".repeat(500))), modified = 1) }
        val packets = Rules.itemsPackets(many)
        assertTrue(packets.size > 1)
        org.desklink.mobile.webrtc.assertAllCross(packets)
        org.desklink.mobile.webrtc.assertAllCross(listOf(Rules.indexPacket(many)))
        assertEquals(Rules.MAX_RULES, packets.sumOf { it.getJSONArray("items")!!.length() })
        assertEquals(2, Rules.fetchPackets((0 until Rules.FETCH_IDS + 1).map { "$it" }).size)
    }

    @Test
    fun storedFormKeepsOriginAndHistoryAndReadsVersionOne() {
        val r = rule("r", Step(Rules.T_NFC), Step(Rules.A_RING), runOn = Rules.DESK)
        val runs = (1..30).map { Run(it.toLong(), true, "x") }
        val back = Rules.fromStored(JSONObject(Rules.toStored(Rules.Stored(mapOf("r" to r), mapOf("r" to "desk1"), mapOf("r" to runs, "gone" to runs))).toString()))
        assertEquals(r, back.rules["r"])
        assertEquals("desk1", back.origin["r"])
        assertEquals(Rules.MAX_HISTORY, back.history["r"]!!.size)
        assertNull(back.history["gone"])
        val v1 = JSONObject().put("version", 1).put("rules", JSONArray().put(JSONObject(Rules.toJson(r).toString()).apply { remove("modified") }))
        assertEquals(1L, Rules.fromStored(v1).rules["r"]!!.modified)
        assertEquals(1, Rules.prune(mapOf("t" to r.copy(id = "t", deleted = true, modified = 1), "r" to r), Rules.TOMBSTONE_DAYS * 86_400_000 + 2).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aNewerStoredSchemaIsRefused() {
        Rules.fromStored(JSONObject().put("version", Rules.STORED_VERSION + 1))
    }

    @Test
    fun nextAlarmHonoursDays() {
        val thursdayMorning = at(2026, 10, 22, 6, 0)
        assertEquals(at(2026, 10, 22, 7, 30), AutomationAlarms.next("07:30", "12345", thursdayMorning))
        assertEquals(at(2026, 10, 24, 7, 30), AutomationAlarms.next("07:30", "67", thursdayMorning))
        assertEquals(at(2026, 10, 23, 5, 0), AutomationAlarms.next("05:00", null, thursdayMorning))
        assertNull(AutomationAlarms.next("7:3x", null, thursdayMorning))
    }

    @Test
    fun nfcTagIdsAreShortAndUriSafe() {
        assertTrue(AutomationNfc.validTag("a1b2-c3"))
        assertFalse(AutomationNfc.validTag(""))
        assertFalse(AutomationNfc.validTag("a/../b"))
        assertFalse(AutomationNfc.validTag("x".repeat(AutomationNfc.MAX_TAG_CHARS + 1)))
        assertEquals("kdeconnect://runcommand/automation/a1", AutomationNfc.uri("a1"))
    }

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        java.util.Calendar.getInstance().apply { clear(); set(y, mo - 1, d, h, mi) }.timeInMillis
}
