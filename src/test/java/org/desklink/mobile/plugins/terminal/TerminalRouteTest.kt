/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.terminal

import android.content.Intent
import org.desklink.mobile.plugins.terminal.TerminalRoute.Destination
import org.desklink.mobile.plugins.terminal.TerminalRoute.Ending
import org.desklink.mobile.ui.MainActivity
import org.desklink.mobile.ui.compose.screen.tabs.SessionStatus
import org.desklink.mobile.ui.compose.screen.tabs.TerminalsGate
import org.desklink.mobile.ui.compose.screen.tabs.TerminalsPanel
import org.desklink.mobile.ui.compose.screen.tabs.matchingSessions
import org.desklink.mobile.ui.compose.screen.tabs.sessionStatus
import org.desklink.mobile.ui.compose.screen.tabs.terminalsPanel
import org.desklink.mobile.webrtc.SessionPasswordDenial
import org.desklink.mobile.webrtc.terminal.TerminalClientState
import org.desklink.mobile.webrtc.terminal.TerminalLinkState
import org.desklink.mobile.webrtc.terminal.TerminalSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The Terminals tab is the one list of sessions: the activity shows a session, starts one, or sends the person to the tab. */
@RunWith(RobolectricTestRunner::class)
class TerminalRouteTest {
    private fun session(id: String = "s", exit: Long? = null, gone: Boolean = false, attached: Boolean = false, elsewhere: Boolean = false, title: String = "") =
        TerminalSessionState(id, title, 24, 80, exit, attached, attached, gone, attachedElsewhere = elsewhere)

    @Test
    fun theWidgetsNewTerminalIntentAlwaysAsksForANewSession() {
        // What the widget sends (also to a TerminalActivity that is already on top: onNewIntent routes it the same way): a new session,
        // never the tab, whatever the activity was showing.
        val intent = TerminalActivity.intent(org.robolectric.RuntimeEnvironment.getApplication(), "dev", newTerminal = true)
        assertEquals(Destination.New(null, null), TerminalRoute.decide(intent.getStringExtra(TerminalActivity.EXTRA_TERMINAL_ID), intent.getBooleanExtra(TerminalActivity.EXTRA_NEW, false)))
        // And the Terminals tab intent the widget uses for "access is off" carries the computer.
        val tab = MainActivity.terminalsIntent(org.robolectric.RuntimeEnvironment.getApplication(), "dev")
        assertEquals("dev", tab.getStringExtra(MainActivity.EXTRA_DEVICE_ID))
        assertTrue(tab.getBooleanExtra(MainActivity.EXTRA_OPEN_TERMINALS, false))
    }

    @Test
    fun anIntentNamingASessionShowsIt() {
        assertEquals(Destination.Session("abc"), TerminalRoute.decide("abc", newRequested = false))
        assertEquals("a session wins over a stray new request", Destination.Session("abc"), TerminalRoute.decide("abc", newRequested = true, launcher = "claude"))
    }

    @Test
    fun aNewRequestStartsAKnownLauncherOrAShellInAFolder() {
        assertEquals(Destination.New(null, null), TerminalRoute.decide(null, true))
        assertEquals(Destination.New("claude", "~/code"), TerminalRoute.decide(null, true, "claude", "~/code"))
        assertEquals("an unknown launcher is a shell, never a command typed by an intent", Destination.New(null, null), TerminalRoute.decide("", true, "rm -rf", " "))
    }

    @Test
    fun anythingElseIsTheTerminalsTabNeverAList() {
        // Old shortcuts, the widget's "Open terminals", a stale notification, a deep link.
        assertEquals(Destination.Tab, TerminalRoute.decide(null, false))
        assertEquals(Destination.Tab, TerminalRoute.decide("", false, "claude", "~"))
        assertEquals(Destination.Tab, TerminalRoute.decide("  ", false))
    }

    @Test
    fun leavingNamesWhatHappenedOnlyToASessionThatWasRunning() {
        assertNull("never seen running: opening an exited session is the person's choice", TerminalRoute.ending(false, session(exit = 0)))
        assertNull(TerminalRoute.ending(true, session()))
        assertEquals(Ending.Exited(2), TerminalRoute.ending(true, session(exit = 2)))
        assertEquals(Ending.Ended, TerminalRoute.ending(true, session(gone = true)))
        assertEquals(Ending.Ended, TerminalRoute.ending(true, null))
        assertTrue(TerminalRoute.isRunning(session()))
        assertFalse(TerminalRoute.isRunning(session(exit = 0)))
        assertFalse(TerminalRoute.isRunning(null))
    }

    @Test
    fun theTerminalsIntentSelectsTheTabOnThatComputerWithTheMessage() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val intent = MainActivity.terminalsIntent(context, "d1", "Session ended")
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertTrue(intent.getBooleanExtra(MainActivity.EXTRA_OPEN_TERMINALS, false))
        assertEquals("d1", intent.getStringExtra(MainActivity.EXTRA_DEVICE_ID))
        assertEquals("Session ended", intent.getStringExtra(MainActivity.EXTRA_MESSAGE))
        assertTrue("an app already open comes forward, it is not stacked", intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0 && intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertFalse("no computer named: the tab of the selected one", MainActivity.terminalsIntent(context, "").hasExtra(MainActivity.EXTRA_DEVICE_ID))
    }

    @Test
    fun theActivityStartedWithNoSessionGoesToTheTabAndFinishesWithoutShowingAnything() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        // What an old widget or shortcut sends: the activity and a computer, nothing else.
        val controller = Robolectric.buildActivity(TerminalActivity::class.java, TerminalActivity.intent(context, "d1")).create()
        val activity = controller.get()
        val next = shadowOf(activity).nextStartedActivity
        assertEquals(MainActivity::class.java.name, next.component?.className)
        assertTrue(next.getBooleanExtra(MainActivity.EXTRA_OPEN_TERMINALS, false))
        assertEquals("d1", next.getStringExtra(MainActivity.EXTRA_DEVICE_ID))
        assertTrue(activity.isFinishing)
    }

    @Test
    fun thePanelIsChosenFromTheClientTheGateAndTheDesk() {
        val up = TerminalClientState(link = TerminalLinkState.CONNECTED, listLoaded = true)
        assertEquals(TerminalsPanel.UNAVAILABLE, terminalsPanel(false, TerminalsGate.OPEN, up))
        assertEquals(TerminalsPanel.GATE, terminalsPanel(true, TerminalsGate.DENIED, up))
        assertEquals("while the fingerprint prompt is up", TerminalsPanel.LOADING, terminalsPanel(true, TerminalsGate.PENDING, up))
        assertEquals(TerminalsPanel.NOT_ALLOWED, terminalsPanel(true, TerminalsGate.OPEN, up.copy(notAllowed = true)))
        assertEquals(TerminalsPanel.LOCKED, terminalsPanel(true, TerminalsGate.OPEN, up.copy(passwordNeeded = SessionPasswordDenial.Required)))
        assertEquals(TerminalsPanel.LOCKED, terminalsPanel(true, TerminalsGate.OPEN, up.copy(passwordNeeded = SessionPasswordDenial.Locked(30))))
        assertEquals(TerminalsPanel.LOADING, terminalsPanel(true, TerminalsGate.OPEN, up.copy(listLoaded = false)))
        assertEquals(TerminalsPanel.LIST, terminalsPanel(true, TerminalsGate.OPEN, up))
        assertEquals("the phone's own check comes before anything the desk says", TerminalsPanel.GATE, terminalsPanel(true, TerminalsGate.DENIED, up.copy(notAllowed = true)))
    }

    @Test
    fun aCardSaysWhatTheDotCannot() {
        assertEquals(SessionStatus.Ended, sessionStatus(session(gone = true)))
        assertEquals(SessionStatus.Exited(1), sessionStatus(session(exit = 1)))
        assertEquals(SessionStatus.Here, sessionStatus(session(attached = true)))
        assertEquals(SessionStatus.Elsewhere, sessionStatus(session(elsewhere = true)))
        assertNull(sessionStatus(session()))
    }

    @Test
    fun searchMatchesTheNameAsShownIgnoringCase() {
        val names = mapOf("a" to "Claude Code", "b" to "~/code/desklink", "c" to "Build")
        val all = names.keys.toList()
        assertEquals(all, matchingSessions(all, "  ") { names.getValue(it) })
        assertEquals(listOf("a", "b"), matchingSessions(all, "CODE") { names.getValue(it) })
        assertEquals(emptyList<String>(), matchingSessions(all, "zzz") { names.getValue(it) })
    }
}
