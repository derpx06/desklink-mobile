/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.onboarding

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.DeskLinkFixedTheme
import org.desklink.mobile.ui.compose.model.device.DeviceConnection
import org.desklink.mobile.ui.compose.screen.guide.Guide
import org.desklink.mobile.ui.compose.screen.guide.GuideAction
import org.desklink.mobile.ui.compose.screen.guide.GuideScreen
import org.desklink.mobile.ui.compose.screen.guide.GuideStatus
import org.desklink.mobile.ui.compose.screen.home.Blocker
import org.desklink.mobile.ui.compose.screen.home.ComputerRow
import org.desklink.mobile.ui.compose.screen.home.HomeActions
import org.desklink.mobile.ui.compose.screen.home.HomeOffers
import org.desklink.mobile.ui.compose.screen.home.HomeState
import org.desklink.mobile.ui.compose.screen.home.PaletteModel
import org.desklink.mobile.ui.compose.screen.home.PaletteScreen
import org.desklink.mobile.ui.compose.screen.onboarding.Glance
import org.desklink.mobile.ui.compose.screen.onboarding.OnboardingRoute
import org.desklink.mobile.ui.compose.screen.onboarding.PermissionsChecklistScreen
import org.desklink.mobile.ui.compose.screen.onboarding.TourPages
import org.desklink.mobile.ui.compose.screen.pairing.WelcomeScreen
import org.desklink.mobile.ui.compose.screen.settings.SettingsActions
import org.desklink.mobile.ui.compose.screen.settings.SettingsTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The first screen, the tour and the "Get started" guide: their words, where they lead, and every way into the guide. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xhdpi")
class GuideAndGlanceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun s(id: Int) = compose.activity.getString(id)

    // ---- words -----------------------------------------------------------------------------------------------

    /** The desk's src/window/onboarding.rs pins the same lines: the two apps tell one story. */
    @Test
    fun theFirstScreenSaysWhatTheDeskSays() {
        assertEquals("Welcome to DeskLink", s(R.string.ob_glance_kicker))
        assertEquals("Your phone and computer, together", s(R.string.ob_glance_title))
        assertEquals(
            listOf("See and control one screen from the other", "Send files, and copy on one, paste on the other", "Phone notifications and terminals on both"),
            Glance.lines.map { s(it.text) },
        )
        assertEquals("Start in 3 steps", s(R.string.ob_glance_steps))
        assertEquals(listOf("Install DeskLink on both", "Pair once with a code", "Done: they connect on their own"), Glance.steps.map(::s))
        assertEquals("No account. Only your paired devices can connect.", s(R.string.ob_glance_private))
        assertEquals("Get started", s(R.string.ob_glance_start))
    }

    @Test
    fun theTourIsThreePagesOfOneSentenceEachAndMatchesTheDesk() {
        assertEquals(
            listOf(
                Triple("Screens", "See it. Steer it.", "Watch one screen on the other, and use your phone as a touchpad and keyboard."),
                Triple("Share", "Files and copies, both ways", "Send a file in a tap, and copy on one device to paste on the other."),
                Triple("Terminals", "Terminals that follow you", "Start a terminal on one device and pick it up on the other."),
            ),
            TourPages.map { Triple(s(it.kicker), s(it.title), s(it.body)) },
        )
        TourPages.forEach { assertEquals("one sentence: ${s(it.body)}", 1, s(it.body).count { c -> c == '.' }) }
    }

    @Test
    fun theGuideCardsAreTheDesksInTheSameOrder() {
        assertEquals("Get started", s(R.string.guide_title))
        assertEquals("What DeskLink can do, and how to start each one.", s(R.string.guide_subtitle))
        assertEquals(
            listOf(
                "Pair a computer", "Send a file", "Copy on one, paste on the other", "See your computer's screen on the phone",
                "Use the phone as a second screen", "Share your phone's screen to the computer", "Phone notifications on the computer",
                "Terminals on both", "Notes on both", "Find my phone", "Lock and unlock", "Glide: the mouse moves onto the phone",
                "Widgets and Quick Settings tiles", "Top-bar menu", "Can't connect?",
            ),
            Guide.cards.map { s(it.title) },
        )
    }

    /** Words a person new to DeskLink should never meet on these screens (and nothing about working away from home: off this release). */
    @Test
    fun theNewScreensUseNoJargon() {
        val banned = Regex(
            "\\b(webrtc|tls|relay|turn server|stun|ice|peer|daemon|protocol|plugin|packet|mdns|lan|ssh|api|fingerprint|certificate|away from home|anywhere|internet)\\b",
            RegexOption.IGNORE_CASE,
        )
        val ours = R.string::class.java.fields.filter { f -> listOf("ob_glance_", "ob_tour_", "guide_").any { f.name.startsWith(it) } && f.name != "guide_keywords" }
        assertTrue(ours.size > 40)
        ours.forEach { f ->
            val text = s(f.getInt(null))
            assertTrue("${f.name}: \"$text\"", !banned.containsMatchIn(text))
        }
    }

    // ---- first run: glance -> tour -> pairing ----------------------------------------------------------------

    @Test
    fun theGlanceLeadsIntoTheTourAndTheTourEndsInPairing() {
        var finished = 0
        var skipped = 0
        compose.setContent { DeskLinkFixedTheme(compose.activity, false) { OnboardingRoute(paired = false, onFinish = { finished++ }, onSkip = { skipped++ }) } }
        compose.onNodeWithText("Your phone and computer, together").assertExists()
        compose.onNodeWithText("Skip").assertExists()
        // One primary button.
        assertEquals(1, compose.onAllNodes(hasText("Get started") and hasClickAction()).fetchSemanticsNodes().size)
        compose.onNodeWithText("Get started").performClick()
        compose.onNodeWithText("See it. Steer it.").assertExists()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Files and copies, both ways").assertExists()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Terminals that follow you").assertExists()
        compose.onNodeWithText("Pair with a computer").performClick()
        assertEquals(1, finished)
        assertEquals(0, skipped)
    }

    @Test
    fun skipLeavesFromTheGlanceAndBackFromTheFirstPageReturnsToIt() {
        var skipped = 0
        compose.setContent { DeskLinkFixedTheme(compose.activity, false) { OnboardingRoute(paired = false, onFinish = {}, onSkip = { skipped++ }) } }
        compose.onNodeWithText("Get started").performClick()
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Back")).performClick()
        compose.onNodeWithText("Your phone and computer, together").assertExists()
        compose.onNodeWithText("Skip").performClick()
        assertEquals(1, skipped)
    }

    // ---- the guide ---------------------------------------------------------------------------------------------

    private val ready = HomeState("d1", "arch-desk", DeviceConnection.LOCAL, listOf(ComputerRow("d1", "arch-desk", DeviceConnection.LOCAL)))

    @Test
    fun everyActionHasACardAndEveryCardCanBeActedOnOrSaysWhere() {
        val used = Guide.cards.mapNotNull { it.action }.toSet() + GuideAction.WIFI // the trouble card's second button
        assertEquals(GuideAction.entries.toSet(), used)
        Guide.cards.forEach { assertTrue(it.id, (it.button != null && it.action != null) || it.atComputer) }
    }

    @Test
    fun beforePairingEveryCardThatNeedsAComputerSaysSoAndOffersPairing() {
        Guide.cards.forEach { card -> assertEquals(card.id, card.needsComputer, Guide.status(card, null) == GuideStatus.NeedsPair) }
        val actions = mutableListOf<GuideAction>()
        compose.setContent { DeskLinkFixedTheme(compose.activity, false) { GuideScreen(null, onBack = {}, onAction = { actions += it }) } }
        val needing = Guide.cards.count { it.needsComputer }
        assertEquals(needing, compose.onAllNodesWithText("Pair a computer first.").fetchSemanticsNodes().size)
        val buttons = compose.onAllNodes(hasText("Pair a computer") and hasClickAction())
        repeat(buttons.fetchSemanticsNodes().size) { buttons[it].performScrollTo().performClick() }
        assertTrue(actions.isNotEmpty() && actions.all { it == GuideAction.PAIR })
    }

    @Test
    fun withAComputerEveryCardsButtonDoesItsAction() {
        val actions = mutableListOf<GuideAction>()
        compose.setContent { DeskLinkFixedTheme(compose.activity, false) { GuideScreen(ready, onBack = {}, onAction = { actions += it }) } }
        val labels = listOf(
            "Pair another computer", "Send a file", "Send clipboard", "Open Desk screen", "Share this screen", "Notification settings",
            "Open terminals", "New note", "Ring your computer", "Lock your computer", "Glide settings", "Add a tile", "Look again", "Wi-Fi settings",
        )
        labels.forEach { label ->
            val nodes = compose.onAllNodes(hasText(label) and hasClickAction())
            repeat(nodes.fetchSemanticsNodes().size) { nodes[it].performScrollTo().performClick() }
        }
        assertEquals(GuideAction.entries.toSet(), actions.toSet())
        assertEquals("Desk screen and Extend both open Desk screen", 2, actions.count { it == GuideAction.DESK_SCREEN })
    }

    @Test
    fun aCardHomeCannotRunNowSaysWhyAndOffersTheFix() {
        val blocked = ready.copy(offers = HomeOffers(ring = false, why = mapOf(PaletteModel.Action.RING to Blocker.PHONE_OFF)))
        var asked: Pair<String, Blocker>? = null
        compose.setContent { DeskLinkFixedTheme(compose.activity, false) { GuideScreen(blocked, onBack = {}, onAction = {}, onBlocked = { card, _, b -> asked = card.id to b }) } }
        compose.onNodeWithText("Find my phone is turned off on this phone.").performScrollTo().assertExists()
        compose.onNode(hasText("Turn on") and hasClickAction()).performScrollTo().performClick()
        assertEquals("find" to Blocker.PHONE_OFF, asked)
    }

    // ---- every way in --------------------------------------------------------------------------------------------

    @Test
    fun theFindAnythingPaletteOpensTheGuide() {
        assertTrue(PaletteModel.Action.GUIDE in PaletteModel.offeredActions(ready))
        var opened = 0
        val actions = HomeActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, onGuide = { opened++ })
        compose.setContent { DeskLinkFixedTheme(compose.activity, false) { PaletteScreen(ready, actions, onClose = {}, onSendLink = {}) } }
        compose.onNodeWithText("Get started guide").performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun settingsOpensTheGuide() {
        var opened = 0
        val noop = SettingsActions(onPhoneClipboard = {}, onPhoneNotifications = {}, onDeskSetting = { _, _ -> }, onPhoneMedia = {}, onUnpair = {}, onEncryptionInfo = {}, onRecreate = {}, onMessage = {})
        compose.setContent {
            DeskLinkFixedTheme(compose.activity, false) {
                SettingsTab(desk = null, actions = noop, route = null, onOpen = {}, onBack = {}, waitingPermissions = 0, about = {}, onGuide = { opened++ })
            }
        }
        compose.onNodeWithText("Get started guide").performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun welcomeAndTheChecklistAfterPairingOpenTheGuide() {
        var opened = 0
        var screen by androidx.compose.runtime.mutableStateOf(0)
        compose.setContent {
            DeskLinkFixedTheme(compose.activity, false) {
                if (screen == 0) WelcomeScreen(onPair = {}, onSettings = {}, onGuide = { opened++ })
                else PermissionsChecklistScreen("arch-desk", emptyList(), onSeeWhatYouCanDo = { opened++ }) {}
            }
        }
        compose.onNodeWithText("Get started guide").performScrollTo().performClick()
        screen = 1
        compose.onNodeWithText("See what you can do").performScrollTo().performClick()
        assertEquals(2, opened)
    }
}
