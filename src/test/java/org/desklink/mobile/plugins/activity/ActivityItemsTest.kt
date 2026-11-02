/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.activity

import org.desklink.mobile.plugins.notifications.NotificationPrivacy.Mirror
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActivityItemsTest {
    private val fixture = JSONObject(javaClass.getResourceAsStream("/shared/activity_bodies.json")!!.bufferedReader().readText())

    @Test
    fun everySharedFixtureCaseIsKeptAsTheComputerKeepsIt() {
        val cases = fixture.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            assertEquals(case.getString("name"), case.getJSONArray("expect").toString(), ActivityItems.sanitize(case.getJSONArray("items")).toString())
        }
    }

    @Test
    fun lengthsAreCutAtTheContractsBounds() {
        val long = JSONObject().put("id", "x".repeat(300)).put("appLabel", "a".repeat(200)).put("title", "t".repeat(300)).put("text", "e".repeat(600))
        val item = ActivityItems.sanitize(JSONArray().put(long)).getJSONObject(0)
        assertEquals(240, item.getString("id").length); assertEquals(120, item.getString("appLabel").length)
        assertEquals(240, item.getString("title").length); assertEquals(500, item.getString("text").length)
    }

    @Test
    fun theRulesOfDesklinkNotificationDecideWhatIsSent() {
        fun d(ongoing: Boolean = true, summary: Boolean = false, media: Boolean = false, own: Boolean = false, enabled: Boolean = true,
              privacy: Mirror = Mirror.FULL, hiddenCall: Boolean = false, block: Boolean = false) =
            ActivityItems.decide(ongoing, summary, media, own, enabled, privacy, hiddenCall, block)
        assertEquals(ActivityItems.Show.FULL, d())
        assertEquals("a plain notification is not an activity", ActivityItems.Show.SKIP, d(ongoing = false))
        assertEquals("media sessions have their own packet", ActivityItems.Show.SKIP, d(media = true))
        assertEquals("an app switched off in the filter", ActivityItems.Show.SKIP, d(enabled = false))
        assertEquals("secret on the lock screen", ActivityItems.Show.SKIP, d(privacy = Mirror.SKIP))
        assertEquals("our own", ActivityItems.Show.SKIP, d(own = true))
        assertEquals("a call without Show caller", ActivityItems.Show.SKIP, d(hiddenCall = true))
        assertEquals("a private one on a secured screen", ActivityItems.Show.HIDDEN, d(privacy = Mirror.HIDDEN))
        assertEquals("an app set to hide contents", ActivityItems.Show.HIDDEN, d(block = true))
    }

    @Test
    fun hiddenContentsLeaveOnlyTheAppName() {
        val c = ActivityItems.Candidate("1|a|", "Maps", "Turn left on Secret Street", "in 200 m", -1, -1, false, "navigation")
        val hidden = ActivityItems.json(c, hide = true)
        assertEquals("Maps", hidden.getString("title")); assertEquals("", hidden.getString("text"))
        assertEquals("Turn left on Secret Street", ActivityItems.json(c, hide = false).getString("title"))
    }

    @Test
    fun onlyThingsWithSomethingLiveAreActivitiesAndCallsComeFirst() {
        assertEquals("call", ActivityItems.kindOf(isCall = true, isNavigation = false, hasChronometer = true, hasProgress = true))
        assertEquals("navigation", ActivityItems.kindOf(false, true, false, false))
        assertEquals("timer", ActivityItems.kindOf(false, false, true, true))
        assertEquals("progress", ActivityItems.kindOf(false, false, false, true))
        assertNull("a service that is merely running", ActivityItems.kindOf(false, false, false, false))
        assertEquals(listOf("call", "navigation", "timer", "progress", "other"), ActivityItems.KINDS.sortedBy(ActivityItems::importance))
    }

    @Test
    fun theRevIgnoresTheClockButFollowsWhatIsShown() {
        val a = JSONArray().put(JSONObject().put("id", "1").put("title", "Pasta"))
        val b = JSONArray().put(JSONObject().put("id", "1").put("title", "Pasta"))
        assertEquals(ActivityItems.rev(a), ActivityItems.rev(b))
        assertNotEquals(ActivityItems.rev(a), ActivityItems.rev(JSONArray()))
        assertEquals(DeskLinkProtocol.PACKET_TYPE_ACTIVITY, ActivityItems.packet(a, 5L).type)
        assertEquals(5L, ActivityItems.packet(a, 5L).getLong("at"))
    }
}
