/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.activity

import org.desklink.mobile.plugins.notifications.NotificationPrivacy.Mirror
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun aDownloadsByteCountsDoNotOverflowThePercent() {
        assertEquals(50, ActivityItems.percent(50_000_000, 100_000_000))
        assertEquals(100, ActivityItems.percent(Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(99, ActivityItems.percent(2_000_000_000, 2_020_000_000))
        assertEquals(-1, ActivityItems.percent(5, 0))
        assertEquals(0, ActivityItems.percent(-5, 10))
        assertEquals(100, ActivityItems.percent(30, 10))
    }

    @Test
    fun aBusyPhoneStillGetsOneSnapshotASecondAndTheFirstGoesAtOnce() {
        val t = ActivityItems.Throttle(1_000L)
        assertEquals("the first change goes at once", 0L, t.onChange(0))
        assertEquals("the next waits for the end of the second", 700L, t.onChange(300))
        assertEquals("and the ones after it do not push it back", null, t.onChange(600))
        assertEquals(null, t.onChange(900))
        t.onFired(1_000) // the trailing send
        assertEquals("a change right after it waits a full second", 1_000L, t.onChange(1_000))
        t.onFired(2_000)
        assertEquals("a quiet spell: at once again", 0L, t.onChange(5_000))
    }

    @Test
    fun withTheScreenOffABusyProgressBarIsSentOnceInFiveSeconds() {
        val t = ActivityItems.Throttle(1_000L)
        val off = ActivityItems.SCREEN_OFF_INTERVAL_MS
        assertEquals("a call still goes at once", 0L, t.onChange(0, off))
        assertEquals(off - 300, t.onChange(300, off))
        t.onFired(off)
        // An hour of a progress bar changing every 100 ms: at most one send in each interval.
        var sends = 1
        var pendingAt = Long.MAX_VALUE
        var now = off
        while (now < 3_600_000L) {
            now += 100
            if (now >= pendingAt) { t.onFired(now); sends++; pendingAt = Long.MAX_VALUE }
            when (val wait = t.onChange(now, off)) { null -> {}; 0L -> sends++; else -> pendingAt = now + wait }
        }
        assertTrue("$sends sends", sends <= 3_600_000L / off + 2)
    }

    @Test
    fun callsAreReadAsTheNotificationRulesReadThemAndTheTagStaysOnThePhone() {
        assertEquals(true, ActivityItems.isCall("call", "x", null))
        assertEquals(true, ActivityItems.isCall("missed_call", "x", null))
        assertEquals("the default dialer's own notification", true, ActivityItems.isCall(null, "com.dialer", "com.dialer"))
        assertEquals(false, ActivityItems.isCall("progress", "x", "com.dialer"))
        val id = ActivityItems.id(7, "com.chat", "conversation:Alice")
        assertEquals(false, id.contains("Alice"))
        assertEquals(id, ActivityItems.id(7, "com.chat", "conversation:Alice"))
    }

    @Test
    fun theSnapshotIsReadOffTheMainThreadAndALostListenerIsNotACrash() {
        val source = java.io.File("src/main/java/org/desklink/mobile/plugins/activity/ActivityPlugin.kt").readText()
        val send = source.substringAfter("private fun sendChange()").substringBefore("private fun notificationsOn")
        assertEquals(true, send.contains("ThreadHelper.execute"))
        assertEquals(true, Regex("""runCatching \{ service\?\.takeIf \{ it\.isConnected \}\?\.activeNotifications \}""").containsMatchIn(source))
        assertEquals("the filter changing is listened to", true, source.contains("NotificationAppFilter.addChangeListener(filterChanged)"))
        assertEquals(true, source.contains("FLAG_LOCAL_ONLY"))
    }
}
