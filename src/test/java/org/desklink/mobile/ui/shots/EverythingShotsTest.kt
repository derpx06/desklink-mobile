/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.shots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import org.desklink.mobile.ui.compose.DeskLinkFixedTheme
import org.desklink.mobile.ui.compose.model.device.DeviceConnection
import org.desklink.mobile.ui.compose.screen.home.Blocker
import org.desklink.mobile.ui.compose.screen.home.ComputerRow
import org.desklink.mobile.ui.compose.screen.home.EverythingSection
import org.desklink.mobile.ui.compose.screen.home.Everything
import org.desklink.mobile.ui.compose.screen.home.FeatureStep
import org.desklink.mobile.ui.compose.screen.home.GlideFacts
import org.desklink.mobile.ui.compose.screen.home.HomeActions
import org.desklink.mobile.ui.compose.screen.home.HomeOffers
import org.desklink.mobile.ui.compose.screen.home.HomeScreen
import org.desklink.mobile.ui.compose.screen.home.HomeState
import org.desklink.mobile.ui.compose.screen.home.PhoneFacts
import org.desklink.mobile.ui.compose.tokens
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A computer with a bit of everything: ready rows, one-step rows (Glide asking, calls, a switch off) and rows not possible now. */
internal val everythingState = HomeState(
    "d1", "arch-desk", DeviceConnection.LOCAL, listOf(ComputerRow("d1", "arch-desk", DeviceConnection.LOCAL)),
    offers = HomeOffers(why = mapOf(Everything.NOTIFICATIONS to Blocker.PHONE_OFF, Everything.CONTACTS to Blocker.PHONE_OFF)),
    phone = PhoneFacts(missing = setOf("calls"), glide = GlideFacts(asking = true)),
)

/**
 * Home's "Everything DeskLink does", light and dark, at font 1.0 and 2.0: `./gradlew testDebugUnitTest --tests "*EverythingShots*"`.
 * Images land in `~/.cache/android-ui/shots/` as `<form>-everything-<theme>.png`. The window is tall enough for the whole list.
 */
abstract class EverythingShots : ShotTest() {
    @Test fun section() = shoot("everything") {
        Box(Modifier.fillMaxSize().background(tokens.bg).padding(16.dp)) { EverythingSection(everythingState) { _, _ -> } }
    }

    @Test fun offline() = shoot("everything-offline") {
        Box(Modifier.fillMaxSize().background(tokens.bg).padding(16.dp)) {
            EverythingSection(everythingState.copy(connection = DeviceConnection.OFFLINE)) { _, _ -> }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h2600dp-xhdpi")
class PhoneEverythingShots : EverythingShots() { override val form = "phone" }

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h5200dp-hdpi")
class HugeTextEverythingShots : EverythingShots() {
    override val form = "huge"
    @Before fun fontScale() = RuntimeEnvironment.setFontScale(2.0f)
}

/** What a tap on a row of the section does, through Home itself: the step runs, then the feature's page opens. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class EverythingBehaviourTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val calls = mutableListOf<String>()

    private val actions = HomeActions(
        onSelectComputer = {}, onPairAnother = { calls += "pair" }, onRetry = { calls += "retry" }, onEndLive = {},
        onPlayPause = {}, onNext = {}, onPrevious = {}, onSeek = {}, onVolume = {}, onSelectPlayer = {},
        onDeskScreen = { calls += "screen" }, onSendFiles = { calls += "files" }, onSendLink = {}, onLock = {}, onShareScreen = {},
        onPing = {}, onRing = { calls += "ring" }, onOpenFiles = {}, onUnlockDesk = {}, onUnavailable = {},
        onBlocked = { action, _, blocker -> calls += "blocked:$action:$blocker" },
        onOpenSettings = { calls += "settings:$it" }, onMessage = { calls += "message" },
        onFeatureStep = { id, step -> calls += "step:$id:$step" },
    )

    private fun tapRow(name: String) {
        compose.onNode(SemanticsMatcher("row $name") { it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.any { d -> d.startsWith("$name.") } })
            .performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun show(state: HomeState) {
        compose.setContent { DeskLinkFixedTheme(compose.activity, false) { HomeScreen(state, actions) } }
        compose.waitForIdle()
    }

    @Test
    fun glideAskingAllowsThenOpensItsPage() {
        show(everythingState)
        tapRow("Glide")
        assertEquals(listOf("step:${Everything.GLIDE}:${FeatureStep.Glide(asking = true)}", "settings:control"), calls)
    }

    @Test
    fun eachStatusDoesItsOneThing() {
        show(everythingState)
        tapRow("Send files")
        tapRow("Calls")
        tapRow("Unlock computer")
        assertEquals(
            listOf("files", "step:${Everything.CALLS}:${FeatureStep.Permission("calls")}", "settings:calls", "blocked:action:unlock:${Blocker.UNLOCK_OFF}"),
            calls,
        )
    }

    @Test
    fun offlineRowsLookAgain() {
        show(everythingState.copy(connection = DeviceConnection.OFFLINE))
        tapRow("Ring computer")
        assertEquals(listOf("retry"), calls)
    }
}
