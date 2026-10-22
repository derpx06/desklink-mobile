/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.automations

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RulesTest {
    private val day = PhoneState(charging = false, ssid = "\"Home\"", minuteOfDay = 12 * 60)

    @Test
    fun triggersMatchTheirKindAndParameters() {
        val home = Step(Rules.TRIGGER_WIFI_CONNECTED, mapOf("ssid" to "Home"))
        assertTrue(Rules.matches(home, Event(Rules.TRIGGER_WIFI_CONNECTED, mapOf("ssid" to "\"Home\""))))
        assertFalse(Rules.matches(home, Event(Rules.TRIGGER_WIFI_CONNECTED, mapOf("ssid" to "\"Work\""))))
        assertFalse("no SSID readable", Rules.matches(home, Event(Rules.TRIGGER_WIFI_CONNECTED)))
        assertTrue("blank = any", Rules.matches(Step(Rules.TRIGGER_WIFI_CONNECTED, mapOf("ssid" to "")), Event(Rules.TRIGGER_WIFI_CONNECTED)))
        assertFalse(Rules.matches(home, Event(Rules.TRIGGER_WIFI_DISCONNECTED, mapOf("ssid" to "Home"))))
        val app = Step(Rules.TRIGGER_NOTIFICATION, mapOf("package" to "com.chat"))
        assertTrue(Rules.matches(app, Event(Rules.TRIGGER_NOTIFICATION, mapOf("package" to "com.chat"))))
        assertFalse(Rules.matches(app, Event(Rules.TRIGGER_NOTIFICATION, mapOf("package" to "com.other"))))
    }

    @Test
    fun batteryLowFiresOnceAsTheLevelCrossesTheThreshold() {
        val low = Step(Rules.TRIGGER_BATTERY_LOW, mapOf("percent" to "20"))
        fun at(level: Int, previous: Int) = Event(Rules.TRIGGER_BATTERY_LOW, mapOf("level" to "$level", "previous" to "$previous"))
        assertTrue(Rules.matches(low, at(20, 21)))
        assertFalse(Rules.matches(low, at(19, 20)))
        assertFalse(Rules.matches(low, at(30, 31)))
        assertFalse(Rules.matches(Step(Rules.TRIGGER_BATTERY_LOW), at(20, 21)))
    }

    @Test
    fun conditionsHoldIncludingTimesAcrossMidnight() {
        val night = Step(Rules.CONDITION_TIME, mapOf("from" to "22:00", "to" to "07:00"))
        assertTrue(Rules.holds(night, day.copy(minuteOfDay = 23 * 60)))
        assertTrue(Rules.holds(night, day.copy(minuteOfDay = 6 * 60 + 59)))
        assertFalse(Rules.holds(night, day.copy(minuteOfDay = 7 * 60)))
        assertFalse(Rules.holds(night, day))
        assertTrue(Rules.holds(Step(Rules.CONDITION_TIME, mapOf("from" to "09:00", "to" to "17:30")), day))
        assertFalse("junk time never holds", Rules.holds(Step(Rules.CONDITION_TIME, mapOf("from" to "25:00", "to" to "07:00")), day))
        assertTrue(Rules.holds(Step(Rules.CONDITION_WIFI, mapOf("ssid" to "Home")), day))
        assertFalse(Rules.holds(Step(Rules.CONDITION_CHARGING), day))
        assertTrue(Rules.holds(Step(Rules.CONDITION_NOT_CHARGING), day))
        assertFalse("unknown never holds", Rules.holds(Step("future"), day))
    }

    @Test
    fun onlyEnabledMatchingRulesWhoseConditionsHoldAreDue() {
        val a = Rule("a", "a", trigger = Step(Rules.TRIGGER_CHARGING_STARTED), actions = listOf(Step(Rules.ACTION_RING)))
        val off = a.copy(id = "off", enabled = false)
        val charging = a.copy(id = "c", conditions = listOf(Step(Rules.CONDITION_CHARGING)))
        assertEquals(listOf("a"), Rules.due(listOf(a, off, charging), Event(Rules.TRIGGER_CHARGING_STARTED), day).map { it.id })
    }

    @Test
    fun theStoredFormRoundTripsAndEveryBoundHolds() {
        val rule = Rule("r", "Night", trigger = Step(Rules.TRIGGER_NFC_TAG, mapOf("tag" to "abc")),
            conditions = listOf(Step(Rules.CONDITION_TIME, mapOf("from" to "22:00", "to" to "07:00"))),
            actions = listOf(Step(Rules.ACTION_NOTIFY, mapOf("text" to "hi"))))
        val runs = (1..30).map { Run(it.toLong(), it % 2 == 0, "x") }
        val (rules, history) = Rules.fromStored(JSONObject(Rules.toStored(listOf(rule), mapOf("r" to runs, "gone" to runs)).toString()))
        assertEquals(listOf(rule), rules)
        assertEquals(Rules.MAX_HISTORY, history["r"]!!.size)
        assertNull("history of a deleted rule is dropped", history["gone"])

        // Bounds: rules, actions, conditions, text, params; unknown kinds dropped.
        val big = JSONObject().put("version", 1).put("rules", JSONArray().apply {
            (0..Rules.MAX_RULES + 5).forEach { i ->
                put(Rules.toJson(Rule("id$i", "n".repeat(500), trigger = Step(Rules.TRIGGER_CHARGING_STARTED, (0..20).associate { "k$it" to "v" }),
                    conditions = List(Rules.MAX_CONDITIONS + 2) { Step(Rules.CONDITION_CHARGING) },
                    actions = List(Rules.MAX_ACTIONS + 2) { Step(Rules.ACTION_RING) } + Step("shell"))))
            }
            put(JSONObject().put("id", "bad").put("trigger", JSONObject().put("kind", "boot")))
        })
        val (loaded, _) = Rules.fromStored(big)
        assertEquals(Rules.MAX_RULES, loaded.size)
        assertTrue(loaded.all { it.name.length == Rules.MAX_TEXT_CHARS })
        assertTrue(loaded.all { it.actions.size == Rules.MAX_ACTIONS && it.actions.none { a -> a.kind == "shell" } })
        assertTrue(loaded.all { it.conditions.size == Rules.MAX_CONDITIONS })
        assertTrue(loaded.all { it.trigger.params.size == Rules.MAX_PARAMS })
    }

    @Test(expected = IllegalArgumentException::class)
    fun aNewerStoredSchemaIsRefused() {
        Rules.fromStored(JSONObject().put("version", Rules.STORED_VERSION + 1))
    }

    @Test
    fun nfcTagIdsAreShortAndUriSafe() {
        assertTrue(AutomationNfc.validTag("a1b2-c3"))
        assertFalse(AutomationNfc.validTag(""))
        assertFalse(AutomationNfc.validTag("a/../b"))
        assertFalse(AutomationNfc.validTag("x".repeat(AutomationNfc.MAX_TAG_CHARS + 1)))
        assertEquals("kdeconnect://runcommand/automation/a1", AutomationNfc.uri("a1"))
    }
}
