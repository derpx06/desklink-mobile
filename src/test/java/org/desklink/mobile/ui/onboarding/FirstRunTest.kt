/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.onboarding

import androidx.test.core.app.ApplicationProvider
import androidx.preference.PreferenceManager
import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.screen.onboarding.FirstRun
import org.desklink.mobile.ui.compose.screen.onboarding.PairingStack
import org.desklink.mobile.ui.compose.screen.onboarding.checklistCounted
import org.desklink.mobile.ui.compose.screen.onboarding.TourPages
import org.desklink.mobile.ui.compose.screen.onboarding.cursorAt
import org.desklink.mobile.ui.compose.screen.onboarding.checklistRows
import org.desklink.mobile.ui.compose.screen.onboarding.hasChecklistWork
import org.desklink.mobile.ui.compose.screen.settings.PermissionRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FirstRunTest {
    @Test
    fun theTourIsForAnUnpairedPersonWhoHasNotSeenIt() {
        assertTrue(FirstRun.showOnboarding(seen = false, pairedCount = 0))
        assertFalse("seen once", FirstRun.showOnboarding(seen = true, pairedCount = 0))
        assertFalse("a paired person never sees it unasked", FirstRun.showOnboarding(seen = false, pairedCount = 1))
        assertFalse(FirstRun.showOnboarding(seen = true, pairedCount = 3))
    }

    @Test
    fun theSeenFlagIsANewDefaultPreferencesKeyAndSticks() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext())
        assertEquals("onboarding_seen", FirstRun.KEY_ONBOARDING_SEEN)
        assertFalse(FirstRun.seen(prefs))
        FirstRun.markSeen(prefs)
        assertTrue(FirstRun.seen(prefs))
        FirstRun.markSeen(prefs) // idempotent
        assertTrue(FirstRun.seen(prefs))
    }

    @Test
    fun theSplashShowsOnlyOnAColdStartWhileAPairedComputerIsNotFoundYet() {
        assertTrue(FirstRun.showSplash(coldStart = true, pairedName = "arch-desk", reachable = false, expired = false))
        assertFalse("not a cold start", FirstRun.showSplash(false, "arch-desk", reachable = false, expired = false))
        assertFalse("nothing to look for: the state is known", FirstRun.showSplash(true, null, reachable = false, expired = false))
        assertFalse("found: never held back", FirstRun.showSplash(true, "arch-desk", reachable = true, expired = false))
        assertFalse("never longer than the cap", FirstRun.showSplash(true, "arch-desk", reachable = false, expired = true))
        assertTrue("the cap is about a second", FirstRun.SPLASH_MAX_MS in 500..1_000)
    }

    @Test
    fun theTourHasThreePagesAndEachSaysSomething() {
        assertEquals(3, TourPages.size)
        assertEquals(TourPages.size, TourPages.map { it.title }.toSet().size)
        assertEquals(TourPages.size, TourPages.map { it.scene }.toSet().size)
        assertEquals(R.string.ob_tour_screens_kicker, TourPages.first().kicker)
    }

    @Test
    fun thePointerStartsAndEndsWhereItBegan() {
        assertEquals(cursorAt(0f), cursorAt(1f))
        val (x, y) = cursorAt(0.3f)
        assertTrue(x in 0f..1f && y in 0f..1f)
    }

    private fun row(granted: Boolean, relevant: Boolean = true) = PermissionRow(R.drawable.sym_info, "t", "s", granted, relevant = relevant) { }

    @Test
    fun theChecklistListsWhatIsRelevantAndOffersWorkOnlyForWhatIsMissing() {
        val rows = listOf(row(granted = true), row(granted = false, relevant = false), row(granted = true))
        assertEquals(2, checklistRows(rows).size)
        assertFalse("an unused permission that is missing is not work", hasChecklistWork(rows))
        assertTrue(hasChecklistWork(rows + row(granted = false)))
        assertFalse(hasChecklistWork(emptyList()))
    }

    @Test
    fun anOemRowOfUnknownStateNeitherRaisesTheChecklistNorBlocksContinue() {
        val oem = PermissionRow(R.drawable.sym_info, "oem", "s", false, unknown = true) { }
        val rows = listOf(row(granted = true), oem)
        assertFalse("ColorOS must not get the screen after every pairing", hasChecklistWork(rows))
        assertEquals(1, checklistCounted(rows).size)
        assertEquals("it is still listed", 2, checklistRows(rows).size)
        assertTrue(hasChecklistWork(rows + row(granted = false)))
    }

    private fun isPairing(r: String) = r == "scan" || r.startsWith("pair/")

    @Test
    fun theChecklistPushedByOnePairingPathSurvivesTheOtherFinishingSecond() {
        // reconcile first: [home, pair/x] -> settled, checklist on top; then PairRoute settles again.
        var stack = PairingStack.settle(listOf("home", "pair/x"), "home", ::isPairing)
        assertEquals(listOf("home"), stack)
        assertTrue(PairingStack.canOfferChecklist(stack, "home"))
        stack = PairingStack.withChecklist(stack, "permissions")
        assertEquals(listOf("home", "permissions"), PairingStack.settle(stack, "home", ::isPairing))
        // the other order: PairRoute settles first, reconcile pushes after
        val first = PairingStack.settle(listOf("home", "scan"), "home", ::isPairing)
        assertEquals(listOf("home", "permissions"), PairingStack.withChecklist(first, "permissions"))
        assertEquals("pushed once", listOf("home", "permissions"), PairingStack.withChecklist(listOf("home", "permissions"), "permissions"))
    }

    @Test
    fun theChecklistIsOfferedOnlyStraightOnTopOfHome() {
        assertFalse(PairingStack.canOfferChecklist(listOf("home", "settings"), "home"))
        assertFalse(PairingStack.canOfferChecklist(listOf("home", "permissions"), "home"))
        assertEquals(listOf("home"), PairingStack.settle(emptyList(), "home", ::isPairing))
    }}
