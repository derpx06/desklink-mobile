/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.desksettings

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.Device
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.R
import org.desklink.mobile.helpers.NotificationHelper
import org.desklink.mobile.plugins.inputdevicesreceiver.Glide
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Ask on phone / Ask on computer (shared fixture asks.json): a desk's ask is a prompt the person answers, never a switch flipped;
 * the phone's state goes back in its own report; the phone's asks to the desk follow the fixture's states.
 */
@RunWith(RobolectricTestRunner::class)
class AsksTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val sent = mutableListOf<NetworkPacket>()
    private val enabled = mutableSetOf<String>()
    private lateinit var device: Device
    private var ready = true

    private fun fixture() = JSONObject(javaClass.getResourceAsStream("/shared/asks.json")!!.bufferedReader().readText())

    @Before
    fun setUp() {
        // Notifications allowed (Android 13+ asks for it): prompts "on" unless a test turns them off.
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        device = mockk(relaxed = true)
        every { device.deviceId } returns DESK
        every { device.name } returns "Workbench"
        every { device.isPaired } returns true
        every { device.hasActiveWebRtcFeatureTransport } answers { ready }
        every { device.sendPacket(any()) } answers { sent += firstArg<NetworkPacket>() }
        every { device.sendPacket(any(), any()) } answers { sent += firstArg<NetworkPacket>() }
        every { device.isPluginEnabled(any()) } answers { firstArg<String>() in enabled }
        every { device.setPluginEnabled(any(), any()) } answers { if (secondArg()) enabled += firstArg<String>() else enabled -= firstArg<String>() }
        settings = DeskSettingsPlugin().also { it.setContext(app, device) }
        every { device.getPlugin(any<Class<org.desklink.mobile.plugins.Plugin>>()) } returns null
        every { device.getPlugin(DeskSettingsPlugin::class.java) } answers { settings }
        DeskLinkApplication.getInstance().devices[DESK] = device
    }

    private lateinit var settings: DeskSettingsPlugin

    @After
    fun tearDown() {
        DeskLinkApplication.getInstance().devices.remove(DESK)
        Asks.Feature.entries.forEach { Asks.forget(app, DESK, it.key) }
        Glide.setAllowed(app, DESK, false)
    }

    private fun plugin() = settings

    private fun ask(vararg keys: String, value: Any = true) = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SETTINGS_REQUEST).apply {
        this["set"] = JSONObject().apply { keys.forEach { put(it, value) } }
    }

    private fun notices(): List<Notification> = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications

    private fun reports() = sent.filter { it.type == DeskLinkProtocol.PACKET_TYPE_SETTINGS }

    // ---- the contract ------------------------------------------------------------------------------------------------------

    @Test
    fun theFeatureTablesTimesAndWordsAreTheFixtures() {
        val f = fixture()
        val toPhone = f.getJSONObject("toPhone")
        assertEquals(toPhone.keys().asSequence().toList(), Asks.Feature.entries.map { it.key })
        Asks.Feature.entries.forEach { assertEquals(it.key, toPhone.getString(it.key), app.getString(it.phrase)) }
        assertEquals(f.getJSONObject("toDesk").keys().asSequence().toList(), Asks.TO_DESK)
        assertTrue("never a settable key", Asks.TO_DESK.none { it in DeskSettingsPlugin.SETTABLE })
        assertEquals(f.getLong("waitSeconds") * 1000, Asks.WAIT_MS)
        assertEquals(f.getLong("reaskSeconds") * 1000, Asks.REASK_MS)
        assertEquals(f.getLong("dedupeSeconds") * 1000, Asks.DEDUPE_MS)
        val words = f.getJSONObject("words")
        fun ours(name: Int) = app.getString(name, "{device}", "{feature}")
        mapOf(
            "prompt" to R.string.ask_prompt, "allow" to R.string.ask_allow, "notNow" to R.string.ask_not_now,
            "askOnPhone" to R.string.ask_on_phone, "askOnComputer" to R.string.ask_on_computer, "askAgain" to R.string.ask_again,
            "waiting" to R.string.ask_waiting, "declined" to R.string.ask_declined, "timedOut" to R.string.ask_timed_out,
            "undelivered" to R.string.ask_undelivered, "blocked" to R.string.ask_blocked,
        ).forEach { (key, res) -> assertEquals(key, words.getString(key), ours(res)) }
        assertEquals(listOf("waiting", "declined", "timedOut", "undelivered", "blocked"),
            (0 until f.getJSONArray("states").length()).map { f.getJSONArray("states").getString(it) })
        assertEquals(AskBook.State.entries.size, f.getJSONArray("states").length())
    }

    @Test
    fun theReportIsTheFixturesShape() {
        val ex = fixture().getJSONObject("examples")
        assertEquals(listOf(Asks.Feature.CONTACTS), Asks.askedKeys(ex.getJSONObject("ask").getJSONObject("set")))
        fun same(expected: JSONObject, packet: NetworkPacket) {
            assertEquals(DeskLinkProtocol.PACKET_TYPE_SETTINGS, packet.type)
            assertEquals(expected.toString(), JSONObject(packet.serialize()).getJSONObject("body").toString())
        }
        same(ex.getJSONObject("report"), Asks.reportPacket(linkedMapOf("contacts" to false, "glide" to true), promptsOn = true))
        same(ex.getJSONObject("declinedReport"), Asks.reportPacket(linkedMapOf("contacts" to false, "glide" to true), true, declined = "contacts"))
        same(ex.getJSONObject("blockedReport"), Asks.reportPacket(linkedMapOf("contacts" to false), promptsOn = false))
    }

    // ---- incoming: a prompt, never a switch ---------------------------------------------------------------------------------

    @Test
    fun everyFeatureGetsItsOwnHeadsUpThatNeverTurnsAnythingOn() {
        val p = plugin()
        assertTrue(p.supportedPacketTypes.contains(DeskLinkProtocol.PACKET_TYPE_SETTINGS_REQUEST))
        assertTrue(p.outgoingPacketTypes.contains(DeskLinkProtocol.PACKET_TYPE_SETTINGS))
        assertTrue(p.onPacketReceived(ask(*Asks.Feature.entries.map { it.key }.toTypedArray())))
        // Every feature not already on (Robolectric grants Do Not Disturb access) gets its own prompt.
        val off = Asks.Feature.entries.filter { !Asks.isOn(app, device, it) }
        assertTrue(off.size >= Asks.Feature.entries.size - 1)
        assertEquals(off.size, notices().size)
        assertTrue("nothing turned on", enabled.isEmpty() && !Glide.allowed(app, DESK))
        val contacts = notices().single { it.extras.getString(Notification.EXTRA_TITLE) == "Workbench asks to see your contacts" }
        assertEquals(NotificationHelper.Channels.REQUESTS, contacts.channelId)
        assertEquals(listOf("Allow", "Not now"), contacts.actions.map { it.title.toString() })
        assertEquals(Asks.WAIT_MS, contacts.timeoutAfter)
        @Suppress("DEPRECATION")
        assertEquals(Notification.PRIORITY_HIGH, contacts.priority)
        assertEquals("no details on the lock screen", Notification.VISIBILITY_PRIVATE, contacts.visibility)
        assertEquals(app.getString(R.string.ask_public_title), contacts.publicVersion.extras.getString(Notification.EXTRA_TITLE))
        assertTrue("Allow opens the consent screen (allowed from a notification on Android 14+)", shadowOf(contacts.actions[0].actionIntent).isActivityIntent)
        assertTrue("Not now is a broadcast", shadowOf(contacts.actions[1].actionIntent).isBroadcastIntent)
        assertEquals("the banner lists them too", off.size, Asks.waiting().size)
        val report = reports().last()
        assertEquals("on", report.getString("prompts"))
        assertFalse(report.getJSONObject("features")!!.getBoolean("contacts"))
    }

    @Test
    fun aRepeatIsDroppedAndAFreshAskReplacesNeverStacks() {
        val p = plugin()
        var now = 1_000_000L
        repeat(20) { Asks.onAsked(app, DESK, "Workbench", Asks.Feature.CONTACTS, on = false, now = now) }
        assertEquals(1, notices().size)
        now += Asks.DEDUPE_MS
        assertTrue(Asks.onAsked(app, DESK, "Workbench", Asks.Feature.CONTACTS, on = false, now = now))
        assertEquals("replaced", 1, notices().size)
        assertEquals(1, Asks.waiting(now).size)
        assertEquals("gone with the desk's wait", 0, Asks.waiting(now + Asks.WAIT_MS).size)
        p.onPacketReceived(ask("contacts", value = false))
        p.onPacketReceived(ask("terminalAccess"))
        assertEquals("only true for a phone key asks", 1, notices().size)
    }

    @Test
    fun aFeatureAlreadyOnIsAnsweredByTheReportAlone() {
        enabled += org.desklink.mobile.plugins.Plugin.getPluginKey(org.desklink.mobile.plugins.clipboard.ClipboardPlugin::class.java)
        plugin().onPacketReceived(ask("clipboard"))
        assertTrue(notices().isEmpty())
        assertTrue(reports().last().getJSONObject("features")!!.getBoolean("clipboard"))
    }

    @Test
    fun notNowTellsTheDeskAndChangesNothing() {
        plugin().onPacketReceived(ask("contacts"))
        Asks.AskReceiver().onReceive(app, Intent(Asks.ACTION_NOT_NOW).putExtra(Asks.EXTRA_DESK, DESK).putExtra(Asks.EXTRA_KEY, "contacts"))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        Thread.sleep(200)
        assertTrue(notices().isEmpty() && Asks.waiting().isEmpty())
        assertTrue(enabled.isEmpty())
        val report = reports().last()
        assertEquals(listOf("contacts"), report.getStringList("declined"))
    }

    @Test
    fun withNotificationsOffTheReportSaysPromptsOffAndTheBannerStillHasIt() {
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        plugin().onPacketReceived(ask("calls"))
        assertEquals("off", reports().last().getString("prompts"))
        assertEquals(Asks.Feature.CALLS, Asks.waiting().single().feature)
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true)
    }

    // ---- Allow, per kind ----------------------------------------------------------------------------------------------------

    private fun allow(key: String) = Robolectric.buildActivity(AskConsentActivity::class.java, Asks.allowIntent(app, DESK, key)).setup()

    private fun settle() {
        repeat(20) { if (enabled.isEmpty()) Thread.sleep(25) }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
    }

    @Test
    fun allowForARuntimePermissionTurnsTheSettingOnAndAsksAndroid() {
        Asks.onAsked(app, DESK, "Workbench", Asks.Feature.CONTACTS, on = false)
        val activity = allow("contacts").get()
        settle()
        assertTrue(enabled.contains(org.desklink.mobile.plugins.Plugin.getPluginKey(org.desklink.mobile.plugins.contacts.ContactsPlugin::class.java)))
        val asked = shadowOf(activity).lastRequestedPermission
        assertTrue("Android's dialog for READ_CONTACTS", asked?.requestedPermissions?.contains(android.Manifest.permission.READ_CONTACTS) == true ||
            shadowOf(activity).nextStartedActivityForResult?.intent?.action == "androidx.activity.result.contract.action.REQUEST_PERMISSIONS")
        assertTrue("answered: the prompt goes", notices().isEmpty())
    }

    @Test
    fun allowForASpecialAccessOpensItsSystemPage() {
        val activity = allow("notifications").get()
        settle()
        assertTrue(enabled.contains(org.desklink.mobile.plugins.Plugin.getPluginKey(org.desklink.mobile.plugins.notifications.NotificationsPlugin::class.java)))
        assertEquals(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, shadowOf(activity).nextStartedActivity?.action)
        val dnd = allow("dnd").get()
        assertEquals(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, shadowOf(dnd).nextStartedActivity?.action)
    }

    @Test
    fun allowForAPlainSettingTurnsItOnAndReports() {
        val activity = allow("clipboard").get()
        settle()
        assertTrue(enabled.contains(org.desklink.mobile.plugins.Plugin.getPluginKey(org.desklink.mobile.plugins.clipboard.ClipboardPlugin::class.java)))
        assertTrue("done at once", activity.isFinishing)
    }

    @Test
    fun allowForGlideTurnsItOnForThatDeskAndOpensTheAccessibilitySetupWhenTheServiceIsOff() {
        val activity = allow("glide").get()
        assertTrue(Glide.allowed(app, DESK))
        assertEquals(org.desklink.mobile.plugins.mousereceiver.AccessibilitySetupActivity::class.java.name, shadowOf(activity).nextStartedActivity?.component?.className)
    }

    @Test
    fun allowForControlOnlyOpensTheAccessibilitySetupAndTurnsNothingOn() {
        assertFalse("off while the service is not running", Asks.isOn(app, device, Asks.Feature.CONTROL))
        val activity = allow("control").get()
        settle()
        assertEquals(org.desklink.mobile.plugins.mousereceiver.AccessibilitySetupActivity::class.java.name, shadowOf(activity).nextStartedActivity?.component?.className)
        assertTrue("nothing enabled from the ask", enabled.isEmpty() && !Glide.allowed(app, DESK))
        assertTrue("done at once", activity.isFinishing)
    }

    @Test
    fun theConsentScreenRefusesAnUnknownDeskOrKey() {
        assertTrue(Robolectric.buildActivity(AskConsentActivity::class.java, Asks.allowIntent(app, "nobody", "contacts")).setup().get().isFinishing)
        assertTrue(Robolectric.buildActivity(AskConsentActivity::class.java, Asks.allowIntent(app, DESK, "terminalAccess")).setup().get().isFinishing)
        assertTrue(enabled.isEmpty())
    }

    // ---- outgoing: Ask on computer ------------------------------------------------------------------------------------------

    @Test
    fun anAskWaitsThenEndsClearly() {
        var now = 0L
        val book = AskBook { now }
        assertNull(book.state("terminalAccess"))
        book.asked("terminalAccess", delivered = true)
        assertEquals(AskBook.State.WAITING, book.state("terminalAccess"))
        assertEquals("the desk's notifications are off", AskBook.State.BLOCKED, book.state("terminalAccess", promptsOff = true))
        assertFalse("one pending per key", book.mayAsk("terminalAccess", manual = true))
        now = Asks.WAIT_MS
        assertEquals(AskBook.State.TIMED_OUT, book.state("terminalAccess"))
        assertTrue("Ask again once nobody answered", book.mayAsk("terminalAccess", manual = true))
        assertFalse("but never unasked within the re-ask time", book.mayAsk("terminalAccess", manual = false))
        book.onReport(mapOf("terminalAccess" to true), emptyList())
        assertNull("a late Allow still counts", book.state("terminalAccess"))
        book.asked("unlockNearby", delivered = true)
        book.onReport(mapOf("unlockNearby" to false), listOf("unlockNearby"))
        assertEquals(AskBook.State.DECLINED, book.state("unlockNearby"))
        now += Asks.REASK_MS
        assertTrue(book.mayAsk("unlockNearby", manual = false))
    }

    @Test
    fun anAskThatCannotGoIsSaidAndSentOnceTheLinkIsReady() {
        ready = false
        val p = plugin()
        p.onPacketReceived(deskReport(mapOf("terminalAccess" to false)))
        assertTrue(p.askable("terminalAccess"))
        assertTrue(p.askDesk("terminalAccess"))
        assertEquals(AskBook.State.UNDELIVERED, p.askState("terminalAccess"))
        assertTrue(sent.none { it.type == DeskLinkProtocol.PACKET_TYPE_SETTINGS_REQUEST })
        ready = true
        p.onFeatureReady()
        val request = sent.single { it.type == DeskLinkProtocol.PACKET_TYPE_SETTINGS_REQUEST }
        assertEquals(true, request.getJSONObject("set")!!.getBoolean("terminalAccess"))
        assertEquals(AskBook.State.WAITING, p.askState("terminalAccess"))
        p.onFeatureReady()
        assertEquals("retried once", 1, sent.count { it.type == DeskLinkProtocol.PACKET_TYPE_SETTINGS_REQUEST })
    }

    @Test
    fun askOnComputerIsOnlyForADeskThatCanBeAskedAndNeverForASettableKey() {
        val p = plugin()
        p.onPacketReceived(deskReport(null))
        assertFalse("an older desk: no button", p.askable("terminalAccess"))
        p.onPacketReceived(deskReport(mapOf("terminalAccess" to true)))
        assertFalse("on there", p.askable("terminalAccess"))
        assertFalse(p.askDesk("clipboard"))
        assertFalse(p.askDesk("anything"))
        p.onPacketReceived(deskReport(mapOf("terminalAccess" to false), prompts = "off"))
        p.askDesk("terminalAccess")
        assertEquals(AskBook.State.BLOCKED, p.askState("terminalAccess"))
        assertFalse("no second ask while it waits", p.askDesk("terminalAccess"))
    }

    private fun deskReport(features: Map<String, Boolean>?, prompts: String = "on") = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SETTINGS).apply {
        listOf("keepAwake", "clipboard", "notifications", "relayConfigured", "relay", "commandAlerts", "unlockNearby").forEach { this[it] = false }
        if (features != null) {
            this["features"] = JSONObject(features)
            this["prompts"] = prompts
        }
    }

    companion object {
        private const val DESK = "desk-ask"
    }
}
