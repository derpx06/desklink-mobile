/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.surfaces.widgets

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.model.device.DeviceConnection
import org.desklink.mobile.webrtc.WebRtcConnectionPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The widgets' state-to-model mapping: the words, which buttons work, what the notes show. */
@RunWith(RobolectricTestRunner::class)
class WidgetRulesTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private fun words(s: WidgetStatus) = context.getString(s.label)

    private fun facts(
        connection: DeviceConnection = DeviceConnection.LOCAL,
        path: WebRtcConnectionPath? = WebRtcConnectionPath.LAN,
        ready: Boolean = true,
        update: Boolean = false,
        lock: Boolean = true,
        terminals: TerminalAccess = TerminalAccess.ALLOWED,
    ) = ComputerFacts(
        id = "d", name = "arch-desk", connection = connection, path = path, ready = ready, updateNeeded = update,
        screen = true, files = true, clipboard = true, ring = true, lock = lock, terminals = terminals,
    )

    private fun offline() = facts(DeviceConnection.OFFLINE, null, ready = false)

    // ---- the status words -----------------------------------------------------------------------------

    @Test
    fun statusUsesHomesPlainWords() {
        assertEquals("Connected over Wi‑Fi", words(WidgetRules.status(facts())))
        assertEquals("Connected through the relay", words(WidgetRules.status(facts(DeviceConnection.AWAY, null))))
        assertEquals("Connected through a relay server", words(WidgetRules.status(facts(DeviceConnection.AWAY, WebRtcConnectionPath.TURN))))
        assertEquals("Connected over the internet", words(WidgetRules.status(facts(DeviceConnection.AWAY, WebRtcConnectionPath.DIRECT))))
        assertEquals("Connected", words(WidgetRules.status(facts(DeviceConnection.CONNECTED, null))))
        assertEquals("Connecting…", words(WidgetRules.status(facts(DeviceConnection.CONNECTING, null, ready = false))))
        assertEquals("Can't reach", words(WidgetRules.status(offline())))
        assertEquals("Can't reach", words(WidgetRules.status(facts(DeviceConnection.UNAVAILABLE, null, ready = false))))
        assertEquals("Update needed", words(WidgetRules.status(facts(DeviceConnection.OFFLINE, null, ready = false, update = true))))
        assertEquals("No computer paired", words(WidgetRules.status(null)))
    }

    @Test
    fun neverConnectedWithoutTheFeatureLink() {
        // A link that exists but whose handover has not finished is "Connecting", not "Connected".
        val s = WidgetRules.status(facts(DeviceConnection.CONNECTING, WebRtcConnectionPath.LAN, ready = false))
        assertEquals(WidgetStatus.CONNECTING, s)
        assertEquals(Tone.WAIT, s.tone)
        assertEquals(Tone.OK, WidgetStatus.WIFI.tone)
        assertEquals(Tone.OFF, WidgetStatus.CANT_REACH.tone)
    }

    // ---- quick actions --------------------------------------------------------------------------------------

    @Test
    fun allFiveButtonsWorkWhileConnected() {
        val ui = WidgetRules.actions(facts())
        assertEquals(listOf(WidgetAction.SCREEN, WidgetAction.FILES, WidgetAction.CLIPBOARD, WidgetAction.RING, WidgetAction.LOCK), ui.map { it.action })
        assertTrue(ui.all { it.enabled && it.reason == null })
    }

    @Test
    fun buttonsLookOffWithHomesReasonWhenNotReady() {
        val reasons = WidgetRules.actions(offline()).map { it.reason?.let(context::getString) }.distinct()
        assertEquals(listOf("Offline"), reasons)
        assertTrue(WidgetRules.actions(offline()).none { it.enabled })
        assertEquals("Connecting…", context.getString(WidgetRules.actions(facts(DeviceConnection.CONNECTING, null, ready = false)).first().reason!!))
        assertEquals("Can't connect", context.getString(WidgetRules.actions(facts(DeviceConnection.UNAVAILABLE, null, ready = false)).first().reason!!))
        assertEquals("Update needed", context.getString(WidgetRules.actions(facts(DeviceConnection.OFFLINE, null, ready = false, update = true)).first().reason!!))
        assertTrue(WidgetRules.actions(null).none { it.enabled })
    }

    @Test
    fun aSwitchedOffFeatureIsOffNotHidden() {
        val ui = WidgetRules.actions(facts().copy(clipboard = false)).associateBy { it.action }
        assertFalse(ui.getValue(WidgetAction.CLIPBOARD).enabled)
        assertEquals("Not available right now", context.getString(ui.getValue(WidgetAction.CLIPBOARD).reason!!))
        assertTrue(ui.getValue(WidgetAction.FILES).enabled)
    }

    @Test
    fun lockIsOffNotHiddenWhereTheComputerDoesNotAllowIt() {
        fun lock(f: ComputerFacts?) = WidgetRules.actions(f).single { it.action == WidgetAction.LOCK }
        assertFalse(lock(facts(lock = false)).enabled)
        assertEquals("Not available right now", context.getString(lock(facts(lock = false)).reason!!))
        assertFalse(lock(null).enabled)
        assertTrue(lock(facts()).enabled)
        assertEquals("Offline", context.getString(lock(offline()).reason!!))
    }

    @Test
    fun oneRowExtrasGoAtHugeTextOnly() {
        assertTrue(WidgetRules.roomForExtras(1.0f))
        assertTrue(WidgetRules.roomForExtras(1.3f))
        assertFalse(WidgetRules.roomForExtras(1.5f))
        assertFalse(WidgetRules.roomForExtras(2.0f))
    }

    @Test
    fun aTapIsAnsweredFromTheLiveStateInHomesWords() {
        assertEquals(Gate.Go, WidgetRules.gate(WidgetAction.FILES, facts()))
        val say = WidgetRules.gate(WidgetAction.SCREEN, offline()) as Gate.Say
        assertEquals("arch-desk is offline", context.getString(say.message, "arch-desk"))
        assertEquals("Still connecting to arch-desk", context.getString((WidgetRules.gate(WidgetAction.RING, facts(DeviceConnection.CONNECTING, null, ready = false)) as Gate.Say).message, "arch-desk"))
        assertEquals("That feature is turned off for this computer", context.getString((WidgetRules.gate(WidgetAction.CLIPBOARD, facts().copy(clipboard = false)) as Gate.Say).message, "arch-desk"))
        assertEquals("No computer paired", context.getString((WidgetRules.gate(WidgetAction.RING, null) as Gate.Say).message))
        // Never "queued": a refused tap is a Say, never Go.
        assertTrue(WidgetAction.entries.all { WidgetRules.gate(it, offline()) is Gate.Say })
    }

    // ---- notes privacy ----------------------------------------------------------------------------------------

    private val notes = (1..7).map { NoteLine("n$it", "Note $it") }

    @Test
    fun aLockedPhoneShowsNoNoteText() {
        val v = WidgetRules.notes(notes, locked = true, unavailable = false, max = 3)
        assertEquals(NotesView.Hidden, v)
        // Even with titles passed in, the locked view carries none of them.
        assertFalse(v.toString().contains("Note"))
    }

    @Test
    fun anUnlockedPhoneShowsAsManyTitlesAsFit() {
        val v = WidgetRules.notes(notes, locked = false, unavailable = false, max = 3) as NotesView.Titles
        assertEquals(listOf("Note 1", "Note 2", "Note 3"), v.lines.map { it.title })
        assertEquals(4, v.more)
        assertEquals(NotesView.Empty, WidgetRules.notes(emptyList(), locked = false, unavailable = false, max = 3))
        assertEquals(NotesView.Unavailable, WidgetRules.notes(emptyList(), locked = false, unavailable = true, max = 3))
        assertEquals(NotesView.Hidden, WidgetRules.notes(emptyList(), locked = true, unavailable = true, max = 3))
    }

    @Test
    fun rowsFollowTheHeightAndEachIs48Dp() {
        assertEquals(1, WidgetRules.noteRows(110f))
        assertEquals(2, WidgetRules.noteRows(160f))
        assertEquals(4, WidgetRules.noteRows(260f))
        assertEquals(5, WidgetRules.noteRows(900f))
        assertEquals(1, WidgetRules.noteRows(0f))
    }

    // ---- terminals ----------------------------------------------------------------------------------------------

    @Test
    fun terminalsLauncherRespectsTheComputersAccess() {
        assertEquals(TerminalsView.Ready(0), WidgetRules.terminals(facts()))
        assertEquals(TerminalsView.Ready(2), WidgetRules.terminals(facts().copy(terminalCount = 2)))
        assertEquals(TerminalsView.AccessOff, WidgetRules.terminals(facts(terminals = TerminalAccess.OFF)))
        assertEquals("Terminal access is off", context.getString(R.string.widget_terminals_access_off))
        assertTrue(WidgetRules.terminals(offline()) is TerminalsView.NotReady)
        assertTrue(WidgetRules.terminals(null) is TerminalsView.NotReady)
    }

    @Test
    fun aTerminalTapNeverLaunchesWhenAccessIsOff() {
        assertEquals(Gate.TerminalsTab, WidgetRules.gate(WidgetAction.TERMINAL, facts(terminals = TerminalAccess.OFF)))
        assertEquals(Gate.Go, WidgetRules.gate(WidgetAction.TERMINAL, facts()))
        // Unknown until the computer has answered once: the terminal screen asks (and shows its own panel).
        assertEquals(Gate.Go, WidgetRules.gate(WidgetAction.TERMINAL, facts(terminals = TerminalAccess.UNKNOWN)))
        assertTrue(WidgetRules.gate(WidgetAction.TERMINAL, offline()) is Gate.Say)
    }

    @Test
    fun anOffAnswerIsAHintTheLiveAnswerAlwaysBeats() {
        // Stored "off" while the link is down and no list is loaded: still a hint (the tap asks again), not a verdict.
        assertEquals(TerminalAccess.OFF, WidgetRules.terminalAccess(liveAllowed = null, storedAllowed = false))
        // The live answer wins either way, and a refused first request (no list) counts as an answer.
        assertEquals(TerminalAccess.ALLOWED, WidgetRules.terminalAccess(liveAllowed = true, storedAllowed = false))
        assertEquals(TerminalAccess.OFF, WidgetRules.terminalAccess(liveAllowed = false, storedAllowed = true))
        assertEquals(TerminalAccess.UNKNOWN, WidgetRules.terminalAccess(null, null))
        assertEquals("Tap to retry", context.getString(R.string.widget_terminals_access_hint))
    }

    // ---- transfers -----------------------------------------------------------------------------------------------

    @Test
    fun aRunningTransferComesBeforeTheLastOne() {
        val running = TransfersView.Running("a.zip", upload = true, percent = 40)
        val last = TransfersView.Last("b.txt", sent = false, time = 1L)
        assertEquals(running, WidgetRules.transfers(running, last))
        assertEquals(last, WidgetRules.transfers(null, last))
        assertEquals(TransfersView.Idle, WidgetRules.transfers(null, null))
    }

    // ---- the colours ------------------------------------------------------------------------------------------------

    private fun colorNames(path: String): Set<String> =
        Regex("""<color name="([^"]+)"""").findAll(java.io.File("src/main/res/$path/colors_widget.xml").readText()).map { it.groupValues[1] }.toSet()

    @Test
    fun theWallpaperColoursAreOnlyAnOverlayOnTheDeskLinkTokens() {
        // Before Android 12 (and wherever the system gives no palette) the tokens in values/ and values-night/ are what shows,
        // so they must define every colour the Android 12 sets override, and the light and dark token sets must match.
        assertEquals(colorNames("values"), colorNames("values-night"))
        assertTrue(colorNames("values").containsAll(colorNames("values-v31")))
        assertEquals(colorNames("values-v31"), colorNames("values-night-v31"))
        // The family's own colours are among them.
        assertTrue(colorNames("values-v31").containsAll(listOf("widget_bg", "widget_text", "widget_text_dim", "widget_tonal", "widget_on_tonal", "widget_accent", "widget_on_accent")))
    }

    // ---- a widget whose computer is gone ------------------------------------------------------------------------------

    @Test
    fun aWidgetWhoseComputerIsNoLongerPairedSaysSoAndActsOnNothing() {
        val intent = android.content.Intent(context, WidgetActionActivity::class.java)
            .putExtra(WidgetActionActivity.EXTRA_ACTION, WidgetAction.FILES.name)
            .putExtra(WidgetActionActivity.EXTRA_DEVICE_ID, "gone-0000-0000-0000-000000000000")
        org.robolectric.Robolectric.buildActivity(WidgetActionActivity::class.java, intent).create()
        assertEquals("This widget's computer is no longer paired", org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        // Nothing else was opened in its place (no file picker, no other computer's screen).
        assertEquals(null, org.robolectric.Shadows.shadowOf(context).nextStartedActivity)
    }
}
